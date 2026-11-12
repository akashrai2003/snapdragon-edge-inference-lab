package ai.snapdragon.edge

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

class VoiceRecorder {
    private val active = AtomicBoolean(false)
    private val speechDetected = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()
    val recording
        get() = active.get()

    @SuppressLint("MissingPermission")
    fun startRecording(
        onRmsChanged: ((Float) -> Unit)? = null,
        onSilenceDetected: (() -> Unit)? = null,
        onError: (() -> Unit)? = null,
    ): Boolean {
        if (active.get()) return true
        return try {
            val size =
                AudioRecord.getMinBufferSize(
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                    .coerceAtLeast(4096)
            val audio =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    size,
                )
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                audio.release()
                return false
            }
            recorder = audio
            speechDetected.set(false)
            synchronized(pcm) { pcm.reset() }
            active.set(true)
            audio.startRecording()
            worker =
                Thread(
                        {
                            android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_AUDIO
                            )
                            val samples = ShortArray(320)
                            val bytes = ByteArray(640)
                            val start = SystemClock.elapsedRealtime()
                            var lastSpeech = start
                            var heard = false
                            var noise = -55f
                            var first = true
                            try {
                                while (active.get()) {
                                    val count = audio.read(samples, 0, samples.size)
                                    if (count <= 0) {
                                        if (active.get()) onError?.invoke()
                                        break
                                    }
                                    var squares = 0.0
                                    for (i in 0 until count) {
                                        val s = samples[i].toInt()
                                        bytes[i * 2] = s.toByte()
                                        bytes[i * 2 + 1] = (s shr 8).toByte()
                                        squares += s.toDouble() * s
                                    }
                                    synchronized(pcm) { pcm.write(bytes, 0, count * 2) }
                                    val rms = sqrt(squares / count) / 32768.0
                                    val db = (20 * log10(rms.coerceAtLeast(.00001))).toFloat()
                                    if (first) {
                                        noise = db.coerceAtMost(-40f)
                                        first = false
                                    }
                                    val now = SystemClock.elapsedRealtime()
                                    if (db > (noise + 10).coerceAtLeast(-43f)) {
                                        heard = true
                                        speechDetected.set(true)
                                        lastSpeech = now
                                    } else noise = (noise * .98f + db * .02f).coerceAtMost(-35f)
                                    onRmsChanged?.invoke(db)
                                    if (
                                        (heard && now - lastSpeech > 1200) ||
                                            (!heard && now - start > 8000) ||
                                            now - start > 60000
                                    ) {
                                        onSilenceDetected?.invoke()
                                        break
                                    }
                                }
                            } catch (_: Exception) {
                                if (active.get()) onError?.invoke()
                            }
                        },
                        "CueVoiceInput",
                    )
                    .apply { start() }
            true
        } catch (_: Exception) {
            cancel()
            false
        }
    }

    private fun release() {
        active.set(false)
        runCatching { recorder?.stop() }
        if (Thread.currentThread() != worker) runCatching { worker?.join(800) }
        runCatching { recorder?.release() }
        recorder = null
        worker = null
    }

    fun stopRecording(): ByteArray {
        release()
        return synchronized(pcm) {
            val samples = if (speechDetected.get()) pcm.toByteArray() else byteArrayOf()
            createWav(samples, 16000, 1, 16).also { pcm.reset() }
        }
    }

    fun cancel() {
        release()
        synchronized(pcm) { pcm.reset() }
    }

    companion object {
        fun createWav(
            pcmData: ByteArray,
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int,
        ): ByteArray {
            val h = ByteBuffer.allocate(44 + pcmData.size).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray())
                .putInt(pcmData.size + 36)
                .put("WAVEfmt ".toByteArray())
                .putInt(16)
                .putShort(1)
                .putShort(channels.toShort())
                .putInt(sampleRate)
                .putInt(sampleRate * channels * bitsPerSample / 8)
                .putShort((channels * bitsPerSample / 8).toShort())
                .putShort(bitsPerSample.toShort())
                .put("data".toByteArray())
                .putInt(pcmData.size)
                .put(pcmData)
            return h.array()
        }
    }
}
