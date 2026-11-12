package ai.snapdragon.edge

import ai.snapdragon.edge.CueUi.add
import ai.snapdragon.edge.CueUi.background
import ai.snapdragon.edge.CueUi.brand
import ai.snapdragon.edge.CueUi.column
import ai.snapdragon.edge.CueUi.dp
import ai.snapdragon.edge.CueUi.icon
import ai.snapdragon.edge.CueUi.paper
import ai.snapdragon.edge.CueUi.row
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

class FloatingAssistantActivity : CueActivity() {
    private var closing = false
    private var transferring = false
    private var created = 0L
    private var resumed = false
    private var pendingVoice = false

    override fun onResume() {
        super.onResume()
        resumed = true
        ai.snapdragon.edge.service.VoiceWakeService.panelVisible()
        startPendingVoice()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        created = SystemClock.elapsedRealtime()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(.24f)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = FrameLayout(this).apply { setOnClickListener { dismiss() } }
        val sheet =
            column(this).apply {
                background = background(this@FloatingAssistantActivity, paper, 28)
                setPadding(
                    dp(this@FloatingAssistantActivity, 14),
                    dp(this@FloatingAssistantActivity, 10),
                    dp(this@FloatingAssistantActivity, 14),
                    dp(this@FloatingAssistantActivity, 14),
                )
                isClickable = true
            }
        val params =
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                leftMargin = dp(this@FloatingAssistantActivity, 10)
                rightMargin = dp(this@FloatingAssistantActivity, 10)
                bottomMargin = dp(this@FloatingAssistantActivity, 12)
            }
        root.addView(sheet, params)
        add(
                sheet,
                View(this).apply {
                    background =
                        background(this@FloatingAssistantActivity, Color.parseColor("#D8CFDF"), 2)
                },
                42,
                3,
            )
            .also {
                (sheet.getChildAt(0).layoutParams as android.widget.LinearLayout.LayoutParams)
                    .gravity = Gravity.CENTER_HORIZONTAL
            }
        val header = row(this)
        add(header, brand(this), 0, 48, weight = 1f)
        add(header, VoiceReplyToggle(this), 48, 48)
        add(
            header,
            icon(this, "↗", "Expand conversation") {
                transferring = true
                chat?.cancelVoice()
                CueSession.persist()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra("destination", "chat")
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
                overridePendingTransition(0, 0)
            },
            48,
            48,
        )
        add(header, icon(this, "×", "Close Cue") { dismiss() }, 48, 48)
        add(sheet, header, -1, 54, 7)
        chat = CueChatView(this, true, { requestVoice() }, { chooseImage() })
        add(sheet, chat!!, -1, -2, 2)
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val available = bottom - top
            chat?.scroll?.limit =
                (available * .72f).toInt().minus(dp(this, 165)).coerceAtLeast(dp(this, 90))
        }
        // One view animation. The translucent window itself has no second slide animation.
        val wakeAt = intent.getLongExtra("wake_detected_at", 0L)
        var firstDraw = true
        root.viewTreeObserver.addOnDrawListener {
            if (firstDraw) {
                firstDraw = false
                if (wakeAt > 0)
                    CueDiagnostics.timing(
                        this,
                        "wake_to_draw_ms",
                        SystemClock.elapsedRealtime() - wakeAt,
                    )
                CueDiagnostics.timing(
                    this,
                    "panel_create_to_draw_ms",
                    SystemClock.elapsedRealtime() - created,
                )
            }
        }
        setContentView(root)
        sheet.translationY = dp(this, 30).toFloat()
        sheet.alpha = 0f
        sheet
            .animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
        root.post {
            android.util.Log.i(
                "CueTiming",
                "panel_first_layout_ms=${SystemClock.elapsedRealtime()-created}",
            )
        }
        listenIfRequested(intent, saved == null)
    }

    override fun onNewIntent(value: Intent) {
        super.onNewIntent(value)
        intent = value
        listenIfRequested(value, true)
    }

    private fun listenIfRequested(value: Intent, allowed: Boolean) {
        if (allowed && value.getBooleanExtra("auto_listen", false)) {
            value.removeExtra("auto_listen")
            val manager = getSystemService(android.app.KeyguardManager::class.java)
            if (!manager.isKeyguardLocked) pendingVoice = true
            startPendingVoice()
        }
    }

    private fun startPendingVoice() {
        if (!resumed || !pendingVoice || isFinishing) return
        pendingVoice = false
        window.decorView.post {
            if (resumed && !isFinishing) {
                if (CueSession.generating) CueSession.stop()
                chat?.startVoice()
            }
        }
    }

    private fun dismiss() {
        if (closing) return
        closing = true
        chat?.cancelVoice()
        SpeechOutput.stop()
        if (!transferring) CueSession.stop()
        CueSession.persist()
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onBackPressed() {
        dismiss()
    }
}
