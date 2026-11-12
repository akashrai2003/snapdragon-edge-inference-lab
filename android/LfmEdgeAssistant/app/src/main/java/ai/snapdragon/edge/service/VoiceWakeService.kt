package ai.snapdragon.edge.service

import ai.snapdragon.edge.*
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Explicit on-device recognition only. Dedicated KWS remains an unsuccessful research spike. */
class VoiceWakeService : Service() {
    companion object {
        const val CHANNEL_ID = "cue_handsfree"
        const val NOTIF_ID = 4040
        const val ACTION_STOP = "ai.snapdragon.edge.STOP_CUE_WAKE"
        private var instance: VoiceWakeService? = null

        fun panelVisible() {
            instance?.hideOpeningCard()
        }

        @Volatile
        var isServiceRunning = false
            private set

        @Volatile
        var isCapturing = false
            private set

        @Volatile
        var status = "Hands-free off"
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoiceWakeService::class.java))
        }

        fun stop(context: Context) {
            context
                .getSharedPreferences("mira", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("handsfree", false)
                .apply()
            context.stopService(Intent(context, VoiceWakeService::class.java))
        }

        internal fun matches(value: String): Boolean {
            val normalized = value.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z ]"), " ")
            return Regex("\\bhey\\s*(cue|queue|q)\\b").containsMatchIn(normalized)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var alive = false
    private var listening = false
    private var restartPending = false
    private var retryDelay = 100L
    private var nextWake = 0L
    private var speechStart = 0L
    private var sessionStarted = 0L
    private var recognitionGeneration = 0
    private var openingCard: View? = null
    private val restart = Runnable {
        restartPending = false
        listen()
    }
    private val coordinator =
        object : Runnable {
            override fun run() {
                if (!alive) return
                if (AudioGate.held) {
                    if (recognizer != null) releaseRecognizer()
                    setStatus("Paused while you use Cue")
                } else if (SystemClock.elapsedRealtime() >= nextWake) {
                    if (!listening && !restartPending) schedule(0)
                    else if (listening && SystemClock.elapsedRealtime() - sessionStarted > 20000) {
                        releaseRecognizer()
                        schedule(100)
                    }
                }
                handler.postDelayed(this, 100)
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Hey Cue", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        status = "Starting wake listener"
        startForeground(NOTIF_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop(this)
            return START_NOT_STICKY
        }
        // Debug-only integration probe: verify Android's background presentation policy without
        // collecting or synthesizing microphone input. The service remains non-exported.
        if (BuildConfig.DEBUG && intent?.action == "ai.snapdragon.edge.VERIFY_OPENING") {
            releaseRecognizer()
            nextWake = SystemClock.elapsedRealtime() + 1200
            present(SystemClock.elapsedRealtime(), false)
            return START_NOT_STICKY
        }
        if (alive) return START_NOT_STICKY
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            status = "Offline wake recognition is unavailable on this phone. Use Speak instead."
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return START_NOT_STICKY
        }
        alive = true
        isServiceRunning = true
        getSharedPreferences("mira", MODE_PRIVATE).edit().putBoolean("handsfree", true).apply()
        handler.post(coordinator)
        return START_NOT_STICKY
    }

    private fun initialize() {
        if (Build.VERSION.SDK_INT < 31) return
        if (recognizer != null) return
        val generation = ++recognitionGeneration
        recognizer =
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
                setRecognitionListener(
                    object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            if (generation != recognitionGeneration) return
                            if (alive) {
                                isCapturing = true
                                setStatus("Listening for Hey Cue")
                            }
                        }

                        override fun onBeginningOfSpeech() {
                            if (generation != recognitionGeneration) return
                            speechStart = SystemClock.elapsedRealtime()
                        }

                        override fun onRmsChanged(value: Float) {}

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        // End-of-speech is not terminal: wait for results/error before restarting.
                        override fun onEndOfSpeech() {}

                        override fun onPartialResults(results: Bundle?) {
                            if (generation != recognitionGeneration) return
                            examine(results)
                        }

                        override fun onResults(results: Bundle?) {
                            if (generation != recognitionGeneration) return
                            listening = false
                            isCapturing = false
                            retryDelay = 100
                            if (!examine(results)) schedule(80)
                        }

                        override fun onError(error: Int) {
                            if (generation != recognitionGeneration) return
                            listening = false
                            isCapturing = false
                            if (
                                !alive || AudioGate.held || SystemClock.elapsedRealtime() < nextWake
                            )
                                return
                            when (error) {
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                    setStatus("Microphone access is off")
                                    stopSelf()
                                }
                                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                                    setStatus(
                                        "Download offline English speech in your phone’s voice settings, then try again."
                                    )
                                    alive = false
                                    isServiceRunning = false
                                    stopForeground(STOP_FOREGROUND_DETACH)
                                    stopSelf()
                                }
                                SpeechRecognizer.ERROR_NO_MATCH,
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                    retryDelay = 100
                                    schedule(80)
                                }
                                else -> {
                                    retryDelay = (retryDelay * 2).coerceAtMost(3000)
                                    schedule(retryDelay)
                                }
                            }
                        }

                        override fun onEvent(type: Int, params: Bundle?) {}
                    }
                )
            }
    }

    private fun examine(results: Bundle?): Boolean {
        if (!alive || AudioGate.held || SystemClock.elapsedRealtime() < nextWake) return false
        val alternatives =
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        if (alternatives.none { matches(it) }) return false
        val detected = SystemClock.elapsedRealtime()
        CueDiagnostics.event(this, "wake_match")
        nextWake = detected + 1200
        releaseRecognizer()
        android.util.Log.i(
            "CueTiming",
            "wake_match_from_speech_start_ms=${if(speechStart>0) detected-speechStart else -1}",
        )
        present(detected)
        return true
    }

    private fun listen() {
        if (!alive || AudioGate.held || listening || SystemClock.elapsedRealtime() < nextWake)
            return
        try {
            initialize()
            listening = true
            sessionStarted = SystemClock.elapsedRealtime()
            speechStart = 0L
            recognizer?.startListening(
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
                        400L,
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        300L,
                    )
                    if (Build.VERSION.SDK_INT >= 33)
                        putStringArrayListExtra(
                            RecognizerIntent.EXTRA_BIASING_STRINGS,
                            arrayListOf("Hey Cue", "Hey Q", "Hey queue"),
                        )
                }
            )
        } catch (_: Exception) {
            listening = false
            isCapturing = false
            retryDelay = (retryDelay * 2).coerceAtMost(3000)
            schedule(retryDelay)
        }
    }

    private fun schedule(delay: Long) {
        if (!alive || AudioGate.held || restartPending) return
        restartPending = true
        handler.postDelayed(restart, delay)
    }

    private fun releaseRecognizer() {
        recognitionGeneration++
        handler.removeCallbacks(restart)
        restartPending = false
        listening = false
        isCapturing = false
        val old = recognizer
        recognizer = null
        runCatching {
            old?.cancel()
            old?.destroy()
        }
    }

    private fun setStatus(value: String) {
        if (status == value) return
        status = value
        if (alive)
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    private fun floatingIntent() =
        Intent(this, FloatingAssistantActivity::class.java)
            .putExtra("auto_listen", true)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

    private fun present(detected: Long, autoListen: Boolean = true) {
        setStatus("Cue heard you. Tap to open.")
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        runCatching {
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(
                VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
        if (Settings.canDrawOverlays(this)) showOpeningCard(detected, autoListen)
        else
            runCatching {
                startActivity(
                    floatingIntent()
                        .putExtra("wake_detected_at", detected)
                        .putExtra("auto_listen", autoListen)
                )
            }
        android.util.Log.i(
            "CueTiming",
            "wake_dispatch_ms=${SystemClock.elapsedRealtime()-detected}",
        )
    }

    private fun showOpeningCard(detected: Long, autoListen: Boolean) {
        hideOpeningCard()
        AudioGate.hold("wake-opening-card")
        val windowManager = getSystemService(WindowManager::class.java)
        val card =
            CueUi.column(this).apply {
                background = CueUi.background(this@VoiceWakeService, CueUi.paper, 28)
                setPadding(
                    CueUi.dp(context, 16),
                    CueUi.dp(context, 12),
                    CueUi.dp(context, 12),
                    CueUi.dp(context, 16),
                )
                elevation = CueUi.dp(context, 12).toFloat()
            }
        val row = CueUi.row(this)
        CueUi.add(row, CueUi.brand(this), 0, 48, weight = 1f)
        CueUi.add(row, CueUi.icon(this, "×", "Dismiss Cue") { hideOpeningCard() }, 48, 48)
        CueUi.add(card, row)
        CueUi.add(
            card,
            CueUi.text(this, "A little help, right here.", 23f, CueUi.ink, "serif"),
            top = 8,
        )
        CueUi.add(
            card,
            CueUi.button(this, "Open your conversation  ↗", true) {
                runCatching {
                    startActivity(
                        floatingIntent()
                            .putExtra("wake_detected_at", detected)
                            .putExtra("auto_listen", autoListen)
                    )
                }
            },
            top = 16,
        )
        val params =
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT,
                )
                .apply {
                    gravity = Gravity.BOTTOM
                    width =
                        resources.displayMetrics.widthPixels - CueUi.dp(this@VoiceWakeService, 24)
                    y = CueUi.dp(this@VoiceWakeService, 28)
                }
        runCatching {
                windowManager.addView(card, params)
                openingCard = card
                CueDiagnostics.event(this, "opening_card")
                // Wait for this real, visible assistant card to draw before requesting the
                // activity.
                var requested = false
                card.viewTreeObserver.addOnDrawListener {
                    if (!requested) {
                        requested = true
                        card.postDelayed(
                            {
                                if (openingCard === card)
                                    runCatching {
                                        startActivity(
                                            floatingIntent()
                                                .putExtra("wake_detected_at", detected)
                                                .putExtra("auto_listen", autoListen)
                                        )
                                    }
                            },
                            120,
                        )
                    }
                }
                handler.postDelayed({ if (openingCard === card) hideOpeningCard() }, 10000)
            }
            .onFailure { hideOpeningCard() }
    }

    private fun hideOpeningCard() {
        openingCard?.let {
            runCatching { getSystemService(WindowManager::class.java).removeView(it) }
        }
        openingCard = null
        AudioGate.release("wake-opening-card")
    }

    private fun notification(): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                440,
                floatingIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                441,
                Intent(this, VoiceWakeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_spark)
            .setContentTitle("Hey Cue")
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Open Cue", open).build())
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }

    override fun onDestroy() {
        alive = false
        isServiceRunning = false
        releaseRecognizer()
        hideOpeningCard()
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
