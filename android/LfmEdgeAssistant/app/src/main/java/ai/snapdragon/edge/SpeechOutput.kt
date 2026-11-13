package ai.snapdragon.edge

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/** Main-thread speech sessions. An invalidated session can never enqueue another token. */
object SpeechOutput {
    private class Stream(val id: String) {
        val chunker = SpeechChunker()
        val waiting = ArrayDeque<String>()
        val queued = linkedMapOf<String, String>()
        var ended = false
        var serial = 0
        var firstStart = true
        val startedAt = SystemClock.elapsedRealtime()
    }

    private var tts: TextToSpeech? = null
    private var initialized = false
    private var engineEpoch = 0
    private var stream: Stream? = null
    private var app: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    var speaking = false
        private set

    var echoText = ""
        private set

    var firstAudioAt = 0L
        private set

    fun observe(observer: () -> Unit) {
        observers.add(observer)
    }

    fun remove(observer: () -> Unit) {
        observers.remove(observer)
    }

    private fun changed() {
        observers.forEach { it() }
    }

    /** Initialize while the user speaks, before there is any answer text to synthesize. */
    fun warm(context: Context) {
        app = context.applicationContext
        if (tts != null) return
        val epoch = ++engineEpoch
        tts =
            TextToSpeech(app) { status ->
                handler.post {
                    if (epoch != engineEpoch) return@post
                    val engine = tts ?: return@post
                    if (status != TextToSpeech.SUCCESS) {
                        unavailable("Voice playback is unavailable.")
                        return@post
                    }
                    val localVoices =
                        engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired }
                    val voice =
                        engine.defaultVoice?.takeIf {
                            !it.isNetworkConnectionRequired && it in localVoices
                        }
                            ?: localVoices
                                .filter {
                                    it.locale.language == Locale.getDefault().language ||
                                        it.locale.language == "en"
                                }
                                .maxWithOrNull(
                                    compareBy<android.speech.tts.Voice> {
                                            it.locale.language == Locale.getDefault().language
                                        }
                                        .thenBy { it.locale.country == Locale.getDefault().country }
                                        .thenBy { it.quality }
                                        .thenBy { -it.latency }
                                )
                    if (voice == null) {
                        unavailable(
                            "Download an offline voice in your phone’s speech settings first."
                        )
                        return@post
                    }
                    engine.voice = voice
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    engine.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {
                                handler.post started@{
                                    val active = stream ?: return@started
                                    val text = active.queued[id] ?: return@started
                                    echoText = (echoText + " " + text).takeLast(1200)
                                    if (active.firstStart) {
                                        active.firstStart = false
                                        firstAudioAt = SystemClock.elapsedRealtime()
                                        val elapsed =
                                            SystemClock.elapsedRealtime() - active.startedAt
                                        app?.let {
                                            CueDiagnostics.timing(
                                                it,
                                                "speech_first_audio_ms",
                                                elapsed,
                                            )
                                        }
                                        android.util.Log.i(
                                            "CueTiming",
                                            "speech_first_audio_ms=$elapsed",
                                        )
                                    }
                                }
                            }

                            override fun onDone(id: String?) {
                                handler.post completed@{
                                    val active = stream ?: return@completed
                                    if (active.queued.remove(id) != null) completeIfDrained(active)
                                }
                            }

                            @Deprecated("Android callback")
                            override fun onError(id: String?) {
                                handler.post {
                                    if (stream?.queued?.containsKey(id) == true)
                                        unavailable(
                                            "Voice playback stopped. Your answer is still here to read."
                                        )
                                }
                            }
                        }
                    )
                    initialized = true
                    flush()
                }
            }
    }

    fun begin(context: Context): String {
        stop()
        val active = Stream(UUID.randomUUID().toString())
        stream = active
        echoText = ""
        firstAudioAt = 0L
        speaking = true
        AudioGate.hold("speech-output")
        warm(context)
        changed()
        return active.id
    }

    fun append(id: String?, delta: String) {
        val active = stream?.takeIf { it.id == id && !it.ended } ?: return
        active.waiting.addAll(active.chunker.append(delta))
        flush()
    }

    fun finish(id: String?) {
        val active = stream?.takeIf { it.id == id && !it.ended } ?: return
        active.waiting.addAll(active.chunker.finish())
        active.ended = true
        flush()
        completeIfDrained(active)
    }

    fun cancel(id: String?) {
        if (id != null && stream?.id == id) stop()
    }

    fun read(context: Context, value: String) {
        if (speaking) {
            stop()
            return
        }
        if (value.isBlank()) return
        val id = begin(context)
        append(id, value)
        finish(id)
    }

    private fun flush() {
        if (!initialized) return
        val active = stream ?: return
        val engine = tts ?: return
        while (active.waiting.isNotEmpty()) {
            val text = active.waiting.removeFirst()
            val id = "${active.id}-${active.serial++}"
            active.queued[id] = text
            if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
                unavailable("Voice playback stopped. Your answer is still here to read.")
                return
            }
        }
    }

    private fun completeIfDrained(active: Stream) {
        if (
            stream === active && active.ended && active.waiting.isEmpty() && active.queued.isEmpty()
        )
            stop()
    }

    private fun unavailable(message: String) {
        val hadSpeech = speaking
        stop()
        initialized = false
        engineEpoch++
        val old = tts
        tts = null
        old?.shutdown()
        if (hadSpeech) app?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
    }

    fun stop() {
        val wasSpeaking = speaking
        stream = null // Invalidate before stop() can dispatch stale engine callbacks.
        tts?.stop()
        speaking = false
        AudioGate.release("speech-output")
        if (wasSpeaking) changed()
    }
}
