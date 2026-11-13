package ai.snapdragon.edge.service

import ai.snapdragon.edge.*
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Continuous, app-owned offline capture. No timed platform recognizer restart or system tones. */
class VoiceWakeService : Service() {
    companion object {
        const val CHANNEL_ID = "cue_handsfree_quiet"
        const val NOTIF_ID = 4040
        const val ACTION_STOP = "ai.snapdragon.edge.STOP_CUE_WAKE"
        private var instance: VoiceWakeService? = null

        fun panelVisible() {
            instance?.hideOpeningCard()
        }

        @Volatile
        var isServiceRunning = false
            private set

        val isCapturing
            get() = QuietSpeechSession.wakeCapturing

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

        internal fun matches(value: String): Boolean = WakeCandidate.matches(value)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var capture: QuietSpeechSession? = null
    private var alive = false
    private var nextWake = 0L
    private var openingCard: View? = null
    private var candidate = WakeCandidate()
    private val coordinator =
        object : Runnable {
            override fun run() {
                if (!alive) return
                if (AudioGate.held) {
                    releaseCapture()
                    setStatus("Paused while you use Cue")
                } else if (
                    SystemClock.elapsedRealtime() >= nextWake && capture == null && !isCapturing
                ) {
                    listen()
                }
                handler.postDelayed(this, 150)
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                        CHANNEL_ID,
                        "Hey Cue · silent",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply {
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                    }
            )
        status = "Starting Hey Cue"
        startForeground(NOTIF_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop(this)
            return START_NOT_STICKY
        }
        if (BuildConfig.DEBUG && intent?.action == "ai.snapdragon.edge.VERIFY_OPENING") {
            releaseCapture()
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
        alive = true
        isServiceRunning = true
        getSharedPreferences("mira", MODE_PRIVATE).edit().putBoolean("handsfree", true).apply()
        handler.post(coordinator)
        return START_NOT_STICKY
    }

    private fun listen() {
        if (!alive || AudioGate.held || capture != null || isCapturing) return
        candidate = WakeCandidate()
        setStatus("Getting Hey Cue ready…")
        lateinit var current: QuietSpeechSession
        current =
            QuietSpeechSession(
                applicationContext,
                wakeOnly = true,
                singleTurn = false,
                onReady = {
                    if (capture === current && alive) {
                        CueDiagnostics.event(this, "wake_capture_started")
                        setStatus("Listening for Hey Cue")
                    }
                },
                onPartial = { value -> if (capture === current) examine(value, false) },
                onFinal = { value -> if (capture === current) examine(value, true) },
                onError = {
                    if (capture === current && alive) {
                        capture = null
                        alive = false
                        isServiceRunning = false
                        getSharedPreferences("mira", MODE_PRIVATE)
                            .edit()
                            .putBoolean("handsfree", false)
                            .apply()
                        setStatus("Hey Cue paused. Open Cue to try again.")
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIF_ID, notification())
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf() // Fail once, quietly. Never retry in an audible loop.
                    }
                },
                onStopped = { CueDiagnostics.event(this, "wake_capture_stopped") },
            )
        capture = current
        current.start()
    }

    private fun examine(value: String, final: Boolean) {
        if (!alive || AudioGate.held || SystemClock.elapsedRealtime() < nextWake) return
        val detected = SystemClock.elapsedRealtime()
        if (!candidate.accept(value, detected, final)) return
        CueDiagnostics.event(this, "wake_match")
        nextWake = detected + 1500
        releaseCapture()
        present(detected)
    }

    private fun releaseCapture() {
        capture?.cancel()
        capture = null
        // The worker clears isCapturing only after AudioRecord is actually closed.
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
            .setSound(null)
            .setDefaults(0)
            .addAction(Notification.Action.Builder(null, "Open Cue", open).build())
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }

    override fun onDestroy() {
        alive = false
        isServiceRunning = false
        status = "Hands-free off"
        releaseCapture()
        hideOpeningCard()
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
