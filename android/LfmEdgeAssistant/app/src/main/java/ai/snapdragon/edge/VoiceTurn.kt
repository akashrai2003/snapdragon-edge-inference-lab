package ai.snapdragon.edge

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** One offline spoken turn, with provider endpointing and a stable-transcript backstop. */
class VoiceTurn(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    var active = false
        private set

    private var generation = 0
    private var partial = ""
    private var changedAt = 0L
    private var startedAt = 0L
    private var finishing = false
    private var complete: ((String) -> Unit)? = null
    private var finishingUi: (() -> Unit)? = null
    val supported
        get() =
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private val finalFallback = Runnable { deliver(partial) }
    private val endpoint =
        object : Runnable {
            override fun run() {
                if (!active) return
                val now = SystemClock.elapsedRealtime()
                if ((partial.isNotBlank() && now - changedAt > 1600) || now - startedAt > 45000)
                    finish()
                else if (partial.isBlank() && now - startedAt > 8000) deliver("")
                else handler.postDelayed(this, 100)
            }
        }

    fun start(
        onLevel: (Float) -> Unit,
        onPartial: (String) -> Unit,
        onFinishing: () -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 31 || !supported) return false
        cancel()
        active = true
        partial = ""
        finishing = false
        startedAt = SystemClock.elapsedRealtime()
        changedAt = startedAt
        complete = onFinal
        finishingUi = onFinishing
        val current = ++generation
        return try {
            recognizer =
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
                    setRecognitionListener(
                        object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {}

                            override fun onBeginningOfSpeech() {}

                            override fun onRmsChanged(value: Float) {
                                if (active && generation == current) onLevel(value)
                            }

                            override fun onBufferReceived(buffer: ByteArray?) {}

                            override fun onEndOfSpeech() {
                                if (!active || generation != current) return
                                finishing = true
                                onFinishing()
                                handler.removeCallbacks(endpoint)
                                handler.removeCallbacks(finalFallback)
                                handler.postDelayed(finalFallback, 1800)
                            }

                            override fun onPartialResults(results: Bundle?) {
                                if (!active || generation != current) return
                                val next =
                                    results
                                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                        ?.firstOrNull()
                                        .orEmpty()
                                if (next.isNotBlank() && next != partial) {
                                    partial = next
                                    changedAt = SystemClock.elapsedRealtime()
                                    onPartial(next)
                                }
                            }

                            override fun onResults(results: Bundle?) {
                                if (active && generation == current)
                                    deliver(
                                        results
                                            ?.getStringArrayList(
                                                SpeechRecognizer.RESULTS_RECOGNITION
                                            )
                                            ?.firstOrNull() ?: partial
                                    )
                            }

                            override fun onError(error: Int) {
                                if (!active || generation != current) return
                                CueDiagnostics.timing(context, "voice_turn_error", error.toLong())
                                if (partial.isNotBlank()) deliver(partial)
                                else if (
                                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                                )
                                    deliver("")
                                else {
                                    cancel()
                                    onError(
                                        "Voice input couldn’t finish. Try again or type your question."
                                    )
                                }
                            }

                            override fun onEvent(type: Int, params: Bundle?) {}
                        }
                    )
                    startListening(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                            putExtra(
                                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                                700L,
                            )
                            putExtra(
                                RecognizerIntent
                                    .EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                                500L,
                            )
                        }
                    )
                }
            handler.postDelayed(endpoint, 100)
            true
        } catch (_: Exception) {
            cancel()
            false
        }
    }

    fun finish() {
        if (!active || finishing) return
        finishing = true
        finishingUi?.invoke()
        handler.removeCallbacks(endpoint)
        runCatching { recognizer?.stopListening() }
        handler.removeCallbacks(finalFallback)
        handler.postDelayed(finalFallback, 1800)
    }

    private fun deliver(text: String) {
        if (!active) return
        val callback = complete
        cancel()
        callback?.invoke(text.trim())
    }

    fun cancel() {
        active = false
        generation++
        handler.removeCallbacksAndMessages(null)
        complete = null
        finishingUi = null
        val old = recognizer
        recognizer = null
        runCatching {
            old?.cancel()
            old?.destroy()
        }
    }
}
