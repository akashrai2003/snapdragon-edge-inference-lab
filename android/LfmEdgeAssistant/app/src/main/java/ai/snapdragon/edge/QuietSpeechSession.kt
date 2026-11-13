package ai.snapdragon.edge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.log10
import kotlin.math.sqrt
import org.json.JSONObject
import org.vosk.Recognizer

/**
 * Owns one continuous AudioRecord. No platform recognition service, tones, or timed restart loop.
 */
class QuietSpeechSession(
    private val context: Context,
    private val wakeOnly: Boolean = false,
    private val detectWake: Boolean = false,
    singleTurn: Boolean = true,
    private val onReady: () -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onLevel: (Float) -> Unit = {},
    private val onError: () -> Unit,
    private val onStopped: () -> Unit = {},
) {
    companion object {
        private val microphone = ReentrantLock()
        @Volatile
        var wakeCapturing = false
            private set

        @Volatile
        var captureSessionId = -1
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val wakeReset = AtomicBoolean(false)
    private val endOnSpeech = AtomicBoolean(singleTurn)
    private var started = false

    fun start() {
        check(!started)
        started = true
        Thread({ capture() }, "CueQuietSpeech").start()
    }

    fun finish() {
        finishing.set(true)
    }

    fun rejectWake() {
        wakeReset.set(true)
    }

    fun continueAsQuestion() {
        endOnSpeech.set(true)
    }

    fun cancel() {
        cancelled.set(true)
    }

    private fun post(callback: () -> Unit) {
        main.post { if (!cancelled.get()) callback() }
    }

    private fun capture() {
        var audio: AudioRecord? = null
        var decoder: Recognizer? = null
        var keyword: Recognizer? = null
        var finalText: String? = null
        var failure = false
        var locked = false
        var wakeConfirmed = false
        var previous = ""
        var wake = WakeCandidate()
        try {
            if (
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
            )
                throw SecurityException("Microphone permission required")
            val model = QuietSpeechModel.get(context)
            microphone.lock()
            locked = true
            if (cancelled.get()) return
            decoder =
                if (wakeOnly) Recognizer(model, 16000f, WakeCandidate.GRAMMAR)
                else Recognizer(model, 16000f)
            if (detectWake && !wakeOnly) keyword = Recognizer(model, 16000f, WakeCandidate.GRAMMAR)
            val bufferSize =
                AudioRecord.getMinBufferSize(
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                    .coerceAtLeast(6400)
            audio =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            if (wakeOnly) wakeCapturing = true
            captureSessionId = audio.audioSessionId
            post(onReady)
            val samples =
                ShortArray(1600) // 100 ms: cancellation never waits on a long capture buffer.
            while (!cancelled.get() && !finishing.get()) {
                val count = audio.read(samples, 0, samples.size)
                check(count > 0) { "Microphone read failed" }
                if (cancelled.get()) break
                var square = 0.0
                for (i in 0 until count) square += samples[i].toDouble() * samples[i]
                val db = 20 * log10((sqrt(square / count) / 32768).coerceAtLeast(.00001))
                post { onLevel(((db + 60) / 6).coerceIn(0.0, 10.0).toFloat()) }
                if (wakeReset.getAndSet(false)) {
                    keyword?.reset()
                    wake = WakeCandidate()
                    wakeConfirmed = false
                }
                if (keyword != null && !wakeConfirmed) {
                    val endpoint = keyword.acceptWaveForm(samples, count)
                    val json = JSONObject(if (endpoint) keyword.result else keyword.partialResult)
                    val value = json.optString(if (endpoint) "text" else "partial")
                    if (wake.accept(value, SystemClock.elapsedRealtime(), endpoint)) {
                        wakeConfirmed = true
                        post { onPartial("hey queue") }
                    }
                }
                val endpoint = decoder.acceptWaveForm(samples, count)
                val result = JSONObject(if (endpoint) decoder.result else decoder.partialResult)
                val value = result.optString(if (endpoint) "text" else "partial").trim()
                if (endpoint) {
                    if (value.isNotBlank() && endOnSpeech.get()) {
                        finalText = if (wakeConfirmed) normalizeWake(value) else value
                        break
                    }
                    if (wakeOnly || value.isNotBlank()) post { onFinal(value) }
                    previous = ""
                } else if ((wakeOnly || value.isNotBlank()) && (value != previous || wakeOnly)) {
                    previous = value
                    post { onPartial(value) }
                }
            }
            if (!cancelled.get() && finalText == null) {
                finalText = JSONObject(decoder.finalResult).optString("text").trim()
                if (wakeConfirmed && finalText!!.isNotBlank())
                    finalText = normalizeWake(finalText!!)
            }
        } catch (_: Exception) {
            failure = !cancelled.get()
        } catch (_: LinkageError) {
            failure = !cancelled.get()
        } finally {
            // Native teardown and model work stay off the UI thread, including on cancellation.
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            runCatching { decoder?.close() }
            runCatching { keyword?.close() }
            if (locked) {
                if (wakeOnly) wakeCapturing = false
                captureSessionId = -1
                microphone.unlock()
            }
            main.post(onStopped)
        }
        if (failure) post(onError) else finalText?.let { value -> post { onFinal(value) } }
    }

    private fun normalizeWake(value: String): String {
        // Only after independent acoustic keyword confirmation; never broaden the public wake
        // matcher.
        val prefix =
            Regex("(?i)\\b(?:hey|hay)\\s+(?:cue|queue|q|cute|you)\\b").findAll(value).lastOrNull()
        return "hey queue " +
            if (prefix == null) value else value.substring(prefix.range.last + 1).trim()
    }
}
