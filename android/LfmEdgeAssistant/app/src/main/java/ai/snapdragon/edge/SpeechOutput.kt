package ai.snapdragon.edge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

object SpeechOutput {
    private var tts: TextToSpeech? = null
    private var pending: String? = null
    private var initialized = false
    private var activeId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    var speaking = false
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

    fun read(context: Context, value: String) {
        if (speaking) {
            stop()
            return
        }
        if (value.isBlank()) return
        val app = context.applicationContext
        pending = value
        speaking = true
        changed()
        AudioGate.hold("speech-output")
        if (tts != null && initialized) {
            speak(app)
            return
        }
        tts =
            TextToSpeech(app) { status ->
                initialized = status == TextToSpeech.SUCCESS
                if (initialized) speak(app)
                else {
                    stop()
                    Toast.makeText(app, "Voice playback is unavailable.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun speak(context: Context) {
        val value = pending ?: return
        val engine = tts ?: return
        val voice =
            engine.voices?.firstOrNull {
                !it.isNetworkConnectionRequired &&
                    it.locale.language == Locale.getDefault().language
            }
                ?: engine.voices?.firstOrNull {
                    !it.isNetworkConnectionRequired && it.locale.language == "en"
                }
        if (voice == null) {
            stop()
            Toast.makeText(
                    context,
                    "Download an offline voice in your phone’s speech settings first.",
                    Toast.LENGTH_LONG,
                )
                .show()
            return
        }
        engine.voice = voice
        val prefix = UUID.randomUUID().toString()
        val chunks =
            value.chunked((TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500))
        val lastId = "$prefix-${chunks.lastIndex}"
        activeId = prefix
        pending = null
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}

                override fun onDone(id: String?) {
                    if (id == lastId) handler.post { if (activeId == prefix) stop() }
                }

                @Deprecated("Android callback")
                override fun onError(id: String?) {
                    handler.post { if (activeId == prefix) stop() }
                }
            }
        )
        chunks.forEachIndexed { index, chunk ->
            if (
                engine.speak(
                    chunk,
                    if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null,
                    "$prefix-$index",
                ) == TextToSpeech.ERROR
            ) {
                stop()
                return
            }
        }
    }

    fun stop() {
        activeId = null
        pending = null
        tts?.stop()
        speaking = false
        AudioGate.release("speech-output")
        changed()
    }
}
