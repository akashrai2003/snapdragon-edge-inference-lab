package ai.snapdragon.edge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import ai.snapdragon.edge.FloatingAssistantActivity
import ai.snapdragon.edge.MainActivity
import ai.snapdragon.edge.R

class VoiceWakeService : Service() {
    companion object {
        const val CHANNEL_ID = "lfm_voice_wake_channel"
        const val NOTIF_ID = 4040
        const val ACTION_START = "ai.snapdragon.edge.service.START_VOICE_WAKE"
        const val ACTION_STOP = "ai.snapdragon.edge.service.STOP_VOICE_WAKE"

        @Volatile
        var isServiceRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, VoiceWakeService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceWakeService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isPaused = false

    private val wakeTriggers = listOf(
        "hey lfm",
        "hey l f m",
        "hey el ef em",
        "hey liam",
        "hey elfm",
        "hey l fm",
        "hey l.f.m",
        "ok lfm",
        "hey assistant"
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        initRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startListening()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LFM Voice Wake",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "On-device wake phrase detection for 'Hey LFM'"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VoiceWakeService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("LFM Voice Wake Active")
            .setContentText("Say \"Hey LFM\" anytime to open the on-device assistant")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Turn Off",
                    stopPi
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        destroyRecognizer()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    scheduleRestart(350)
                }

                override fun onError(error: Int) {
                    isListening = false
                    // Common non-fatal speech codes: ERROR_NO_MATCH (7), ERROR_SPEECH_TIMEOUT (6), ERROR_CLIENT (5)
                    scheduleRestart(600)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    checkMatches(matches)
                    scheduleRestart(300)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (checkMatches(matches)) {
                        // Successfully triggered on partial result
                        speechRecognizer?.cancel()
                        isListening = false
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun checkMatches(matches: List<String>?): Boolean {
        if (matches == null || isPaused) return false
        for (raw in matches) {
            val text = raw.lowercase().trim()
            for (trigger in wakeTriggers) {
                if (text.contains(trigger)) {
                    triggerWake()
                    return true
                }
            }
        }
        return false
    }

    private fun triggerWake() {
        isPaused = true

        // Haptic feedback
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 70, 60, 90),
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 70, 60, 90), -1)
            }
        } catch (_: Exception) {}

        // Launch Floating Assistant
        val intent = Intent(this, FloatingAssistantActivity::class.java).apply {
            action = "ai.snapdragon.edge.VOICE_WAKE"
            putExtra("auto_listen", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)

        // Pause for 5 seconds to let the assistant open and complete without re-triggering
        handler.postDelayed({
            isPaused = false
            startListening()
        }, 5000)
    }

    private fun startListening() {
        if (isPaused || !isServiceRunning) return
        if (speechRecognizer == null) initRecognizer()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            scheduleRestart(1000)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!isServiceRunning || isPaused) return
        handler.postDelayed({
            startListening()
        }, delayMs)
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        isListening = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
    }
}
