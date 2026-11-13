package ai.snapdragon.edge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** A quiet app-owned speech turn. Capture never invokes Android's audible recognizer service. */
class VoiceTurn(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var session: QuietSpeechSession? = null
    var active = false
        private set

    private var generation = 0
    private var partial = ""
    private var changedAt = 0L
    private var startedAt = 0L
    private var ready = false
    private var monitoring = false
    private var finishing = false
    private var complete: ((String) -> Unit)? = null
    private var finishingUi: (() -> Unit)? = null
    val supported
        get() = true

    private val endpoint =
        object : Runnable {
            override fun run() {
                if (!active) return
                if (ready && !monitoring) {
                    val now = SystemClock.elapsedRealtime()
                    if ((partial.isNotBlank() && now - changedAt > 1600) || now - startedAt > 45000)
                        finish()
                    else if (partial.isBlank() && now - startedAt > 8000) finish()
                }
                if (active && !finishing) handler.postDelayed(this, 100)
            }
        }

    fun start(
        onLevel: (Float) -> Unit,
        onPartial: (String) -> Unit,
        onFinishing: () -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        forInterruption: Boolean = false,
        onReady: () -> Unit = {},
    ): Boolean {
        cancel()
        active = true
        ready = false
        partial = ""
        monitoring = forInterruption
        finishing = false
        complete = onFinal
        finishingUi = onFinishing
        val current = ++generation
        session =
            QuietSpeechSession(
                    context.applicationContext,
                    detectWake = forInterruption,
                    singleTurn = !forInterruption,
                    onReady = {
                        if (active && generation == current) {
                            ready = true
                            startedAt = SystemClock.elapsedRealtime()
                            changedAt = startedAt
                            onReady()
                            handler.post(endpoint)
                        }
                    },
                    onLevel = { if (active && generation == current) onLevel(it) },
                    onPartial = { value ->
                        if (active && generation == current && value.isNotBlank()) {
                            if (partial != value) changedAt = SystemClock.elapsedRealtime()
                            partial = value
                            onPartial(value)
                        }
                    },
                    onFinal = { value ->
                        if (active && generation == current && !monitoring)
                            deliver(value.ifBlank { partial })
                    },
                    onError = {
                        if (active && generation == current) {
                            cancel()
                            onError(
                                "Voice input couldn’t use the microphone. Try again or type your question."
                            )
                        }
                    },
                )
                .also { it.start() }
        return true
    }

    /** Keep the same AudioRecord and all leading words when the user interrupts an answer. */
    fun rejectWake() {
        session?.rejectWake()
    }

    fun continueAsQuestion() {
        if (!active) return
        monitoring = false
        startedAt = SystemClock.elapsedRealtime()
        changedAt = startedAt
        session?.continueAsQuestion()
    }

    fun finish() {
        if (!active || finishing) return
        finishing = true
        finishingUi?.invoke()
        handler.removeCallbacks(endpoint)
        session?.finish()
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
        session?.cancel()
        session = null
    }
}
