package ai.snapdragon.edge

import ai.snapdragon.edge.CueUi.add
import ai.snapdragon.edge.CueUi.background
import ai.snapdragon.edge.CueUi.brand
import ai.snapdragon.edge.CueUi.button
import ai.snapdragon.edge.CueUi.column
import ai.snapdragon.edge.CueUi.dp
import ai.snapdragon.edge.CueUi.icon
import ai.snapdragon.edge.CueUi.ink
import ai.snapdragon.edge.CueUi.lilac
import ai.snapdragon.edge.CueUi.muted
import ai.snapdragon.edge.CueUi.paper
import ai.snapdragon.edge.CueUi.plum
import ai.snapdragon.edge.CueUi.row
import ai.snapdragon.edge.CueUi.tap
import ai.snapdragon.edge.CueUi.text
import ai.snapdragon.edge.service.VoiceWakeService
import ai.snapdragon.edge.widget.EdgeAIWidgetProvider
import android.Manifest
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : CueActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var status: TextView? = null
    private var settingsStatus: TextView? = null
    private var spokenReplySwitch: Switch? = null
    private val observer = { updateStatus() }
    private var waitingForOverlay = false
    private val poll =
        object : Runnable {
            override fun run() {
                CueSession.refresh()
                updateStatus()
                handler.postDelayed(this, 15000)
            }
        }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.statusBarColor = paper
        window.navigationBarColor = paper
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        CueSession.observe(observer)
        route(intent, saved?.getString("page"))
    }

    override fun onNewIntent(value: Intent) {
        super.onNewIntent(value)
        intent = value
        route(value)
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
        if (page == "settings")
            spokenReplySwitch?.let {
                val enabled = VoiceReplyPreferences.enabled(this)
                if (it.isChecked != enabled) it.isChecked = enabled
            }
        if (waitingForOverlay && Settings.canDrawOverlays(this)) {
            waitingForOverlay = false
            toggleWake()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        CueSession.remove(observer)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putString("page", page)
        super.onSaveInstanceState(state)
    }

    override fun onBackPressed() {
        if (page != "home") home() else super.onBackPressed()
    }

    private fun route(value: Intent, restore: String? = null) {
        if (value.action == Intent.ACTION_SEND) {
            showChat()
            value.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let {
                chat?.insertDraft(it.take(18000))
            }
            @Suppress("DEPRECATION")
            val uri = value.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            uri?.let { importImage(it) }
            value.action = null
            return
        }
        val destination = value.getStringExtra("destination")
        value.removeExtra("destination")
        when (destination ?: restore) {
            "chat" -> showChat()
            "ideas" -> {
                showChat()
                chat?.insertDraft("Suggest three small, creative things I could try today.")
            }
            "settings" -> settings()
            "conversations" -> conversations()
            else -> home()
        }
    }

    private fun disposeChat() {
        chat?.dispose()
        chat = null
        status = null
        settingsStatus = null
    }

    private fun page(title: String, back: Boolean = true): LinearLayout {
        disposeChat()
        val root =
            column(this).apply {
                setBackgroundColor(paper)
                setPadding(
                    dp(this@MainActivity, 22),
                    dp(this@MainActivity, 14),
                    dp(this@MainActivity, 20),
                    dp(this@MainActivity, 18),
                )
            }
        val header = row(this)
        if (back) add(header, icon(this, "‹", "Back to home") { home() }, 48, 48)
        add(
            header,
            if (back)
                text(this, title, 23f, ink, "sans-serif-medium").apply {
                    setPadding(dp(this@MainActivity, 12), 0, 0, 0)
                }
            else brand(this),
            0,
            -2,
            weight = 1f,
        )
        add(root, header, -1, 48)
        return root
    }

    private fun home() {
        page = "home"
        val root = page("", false)
        val header = root.getChildAt(0) as LinearLayout
        status =
            button(this, "• Ready") { availability() }
                .apply {
                    textSize = 12f
                    setPadding(dp(this@MainActivity, 13), 0, dp(this@MainActivity, 13), 0)
                }
        add(header, status!!, -2, 48)
        val scroll =
            ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
            }
        val content = column(this)
        scroll.addView(content)
        add(root, scroll, -1, 0, weight = 1f)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        add(
            content,
            text(
                    this,
                    when (hour) {
                        in 5..11 -> "GOOD MORNING"
                        in 12..16 -> "GOOD AFTERNOON"
                        else -> "GOOD EVENING"
                    },
                    10f,
                    muted,
                    "sans-serif-medium",
                )
                .apply { letterSpacing = .16f },
            top = 18,
        )
        add(content, text(this, "Make room\nfor a good idea.", 33f, ink, "serif"), top = 10)
        add(content, text(this, "A fresh perspective, whenever you need it.", 13f, muted), top = 12)
        add(
            content,
            MuseArtView(this).apply {
                contentDescription = "A lilac flower surrounded by delicate orbits"
            },
            -1,
            164,
            17,
        )
        val askRow = row(this)
        add(askRow, button(this, "Ask anything    ↗", true) { showChat() }, 0, 56, weight = 1f)
        askRow.addView(View(this), LinearLayout.LayoutParams(dp(this, 10), 1))
        add(
            askRow,
            android.widget.FrameLayout(this).apply {
                contentDescription = "Speak to Cue"
                tap(this, lilac, 28) {
                    startActivity(
                        Intent(this@MainActivity, FloatingAssistantActivity::class.java)
                            .putExtra("auto_listen", true)
                    )
                }
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_mic)
                        setColorFilter(plum)
                    },
                    android.widget.FrameLayout.LayoutParams(
                        dp(this@MainActivity, 23),
                        dp(this@MainActivity, 23),
                        Gravity.CENTER,
                    ),
                )
            },
            56,
            56,
        )
        add(content, askRow, -1, 56, 15)
        add(content, text(this, "Start somewhere", 17f, ink, "sans-serif-medium"), top = 16)
        val cards = row(this)
        fun starter(symbol: String, title: String, subtitle: String, color: Int, prompt: String) =
            column(this).apply {
                background = background(this@MainActivity, color, 22)
                setPadding(
                    dp(this@MainActivity, 15),
                    dp(this@MainActivity, 14),
                    dp(this@MainActivity, 12),
                    dp(this@MainActivity, 17),
                )
                tap(this, color, 22) {
                    showChat()
                    chat?.insertDraft(prompt)
                }
                add(this, text(this@MainActivity, symbol, 24f, plum))
                add(this, text(this@MainActivity, title, 15f, ink, "sans-serif-medium"), top = 9)
                add(this, text(this@MainActivity, subtitle, 11f, muted), top = 7)
            }
        add(
            cards,
            starter(
                "✧",
                "A fresh idea",
                "Find your next spark ↗",
                Color.parseColor("#F0E1D6"),
                "Suggest three small, creative things I could try today.",
            ),
            0,
            -2,
            weight = 1f,
        )
        cards.addView(View(this), LinearLayout.LayoutParams(dp(this, 10), 1))
        add(
            cards,
            starter(
                "≈",
                "Make it simple",
                "Untangle a thought ↗",
                Color.parseColor("#E4E9DE"),
                "Help me untangle something on my mind. Ask me one simple question.",
            ),
            0,
            -2,
            weight = 1f,
        )
        add(content, cards, top = 12)
        val nav = row(this)
        add(nav, button(this, "Conversations") { conversations() }, 0, 48, weight = 1f)
        nav.addView(View(this), LinearLayout.LayoutParams(dp(this, 10), 1))
        add(nav, button(this, "Settings") { settings() }, 0, 48, weight = 1f)
        add(root, nav, -1, 48, 12)
        setContentView(root)
        updateStatus()
    }

    private fun showChat() {
        page = "chat"
        val root = page("Cue")
        val header = root.getChildAt(0) as LinearLayout
        add(header, VoiceReplyToggle(this), 48, 48)
        add(
            header,
            icon(this, "+", "Start a new conversation") {
                CueSession.fresh()
                showChat()
            },
            48,
            48,
        )
        chat = CueChatView(this, false, { requestVoice() }, { chooseImage() })
        add(root, chat!!, -1, 0, 12, 1f)
        setContentView(root)
    }

    private fun availability() {
        AlertDialog.Builder(this)
            .setTitle(if (CueSession.ready) "Here when you need me." else "A quiet moment.")
            .setMessage(
                if (CueSession.ready)
                    "Cue is ready for a question, a fresh idea, or a little perspective."
                else "Cue is resting. Start your assistant when you’re ready to chat."
            )
            .setPositiveButton(if (CueSession.ready) "Let Cue rest" else "Start Cue") { _, _ ->
                CueSession.setEngine(!CueSession.ready)
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Settings") { _, _ -> settings() }
            .show()
    }

    private fun settings() {
        page = "settings"
        val root = page("Settings")
        val scroll = ScrollView(this)
        val body = column(this)
        scroll.addView(body)
        add(root, scroll, -1, 0, 12, 1f)
        add(body, text(this, "A little more your way.", 28f, ink, "serif"), top = 18)
        settingsStatus = text(this, "", 14f, muted)
        add(body, settingsStatus!!, top = 14)
        add(body, button(this, "Assistant availability") { availability() }, top = 16)
        add(
            body,
            text(this, "HANDS-FREE", 11f, muted, "sans-serif-medium").apply {
                letterSpacing = .12f
            },
            top = 26,
        )
        add(body, text(this, "“Hey Cue”", 27f, ink, "serif"), top = 10)
        add(
            body,
            text(
                    this,
                    "Say “Hey Cue” to open your assistant. While a conversation is on, ask a follow-up or say “Hey Cue” to interrupt. Hands-free is experimental and may use extra battery.",
                    14f,
                    muted,
                )
                .apply { setLineSpacing(dp(this@MainActivity, 4).toFloat(), 1f) },
            top = 10,
        )
        val voicePreferences = getSharedPreferences("mira", MODE_PRIVATE)
        add(
            body,
            Switch(this).apply {
                text = "Review speech before sending"
                textSize = 14f
                setTextColor(ink)
                minHeight = dp(this@MainActivity, 52)
                isChecked = voicePreferences.getBoolean("review_voice", false)
                setOnCheckedChangeListener { _, checked ->
                    voicePreferences.edit().putBoolean("review_voice", checked).apply()
                }
            },
            top = 12,
        )
        add(
            body,
            Switch(this).apply {
                spokenReplySwitch = this
                text = "Read voice replies aloud"
                textSize = 14f
                setTextColor(ink)
                minHeight = dp(this@MainActivity, 52)
                isChecked = VoiceReplyPreferences.enabled(this@MainActivity)
                setOnCheckedChangeListener { _, checked ->
                    VoiceReplyPreferences.setEnabled(this@MainActivity, checked)
                }
            },
            top = 4,
        )
        add(
            body,
            Switch(this).apply {
                text = "Quick voice answers"
                textSize = 14f
                setTextColor(ink)
                minHeight = dp(this@MainActivity, 52)
                isChecked = voicePreferences.getBoolean("quick_voice_replies", true)
                setOnCheckedChangeListener { _, checked ->
                    voicePreferences.edit().putBoolean("quick_voice_replies", checked).apply()
                }
            },
            top = 4,
        )
        add(
            body,
            text(
                this,
                "Reply sooner for everyday questions. Turn off to allow more thinking on difficult questions.",
                13f,
                muted,
            ),
            top = 6,
        )
        add(
            body,
            Switch(this).apply {
                text = "Interrupt without “Hey Cue”"
                textSize = 14f
                setTextColor(ink)
                minHeight = dp(this@MainActivity, 52)
                isChecked = voicePreferences.getBoolean("open_interruption", false)
                setOnCheckedChangeListener { _, checked ->
                    voicePreferences.edit().putBoolean("open_interruption", checked).apply()
                }
            },
            top = 4,
        )
        add(
            body,
            text(
                this,
                "Respond to other speech while answering. Nearby voices may interrupt too. Leave off in busy places.",
                13f,
                muted,
            ),
            top = 6,
        )
        add(
            body,
            Switch(this).apply {
                text = "Keep the conversation going"
                textSize = 14f
                setTextColor(ink)
                minHeight = dp(this@MainActivity, 52)
                isChecked = voicePreferences.getBoolean("continue_conversation", true)
                setOnCheckedChangeListener { _, checked ->
                    voicePreferences.edit().putBoolean("continue_conversation", checked).apply()
                }
            },
            top = 4,
        )
        add(
            body,
            text(
                this,
                "After a voice answer, Cue listens for your next question for up to 8 seconds. Say “end conversation” or tap End to finish. Listening stops when you leave Cue. Nearby voices can be picked up in this window; turn this off in noisy places. Review before sending pauses automatic conversation.",
                13f,
                muted,
            ),
            top = 6,
        )
        add(
            body,
            button(
                this,
                if (VoiceWakeService.isServiceRunning) "Turn hands-free off" else "Enable Hey Cue",
            ) {
                toggleWake()
            },
            top = 15,
        )
        add(
            body,
            button(this, "Try voice input") {
                startActivity(
                    Intent(this, FloatingAssistantActivity::class.java)
                        .putExtra("auto_listen", true)
                )
            },
            top = 9,
        )
        if (!Settings.canDrawOverlays(this))
            add(
                body,
                button(this, "Allow Cue to appear over other apps") {
                    AlertDialog.Builder(this)
                        .setTitle("Show Cue when you call?")
                        .setMessage(
                            "Android may require ‘Display over other apps’ for hands-free opening. This is optional; the widget always opens Cue directly."
                        )
                        .setPositiveButton("Open permission settings") { _, _ ->
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:$packageName"),
                                )
                            )
                        }
                        .setNegativeButton("Later", null)
                        .show()
                },
                top = 9,
            )
        add(
            body,
            text(this, "YOUR SPACE", 11f, muted, "sans-serif-medium").apply {
                letterSpacing = .12f
            },
            top = 28,
        )
        add(
            body,
            button(this, "Add home screen widget") {
                val manager = AppWidgetManager.getInstance(this)
                if (manager.isRequestPinAppWidgetSupported)
                    manager.requestPinAppWidget(
                        ComponentName(this, EdgeAIWidgetProvider::class.java),
                        null,
                        null,
                    )
                else
                    Toast.makeText(
                            this,
                            "Touch and hold your home screen, then choose Widgets → Cue.",
                            Toast.LENGTH_LONG,
                        )
                        .show()
            },
            top = 12,
        )
        add(
            body,
            button(this, "Delete all conversations") {
                AlertDialog.Builder(this)
                    .setTitle("Clear your space?")
                    .setMessage(
                        "This deletes all saved conversations and their attached images from Cue."
                    )
                    .setPositiveButton("Delete all") { _, _ -> CueSession.clearAll() }
                    .setNegativeButton("Keep them", null)
                    .show()
            },
            top = 9,
        )
        add(
            body,
            button(this, "Connection help") {
                AlertDialog.Builder(this)
                    .setTitle("Reconnect your assistant")
                    .setMessage(
                        "If Cue can’t get ready, open your existing assistant shortcut in Termux, then try Start Cue again."
                    )
                    .setPositiveButton("Open Termux") { _, _ ->
                        packageManager.getLaunchIntentForPackage("com.termux")?.let {
                            startActivity(it)
                        }
                    }
                    .setNeutralButton("Allow shortcut access") { _, _ ->
                        requestPermissions(arrayOf("com.termux.permission.RUN_COMMAND"), 3004)
                    }
                    .setNegativeButton("Close", null)
                    .show()
            },
            top = 9,
        )
        add(
            body,
            text(
                    this,
                    "Your chats and images stay on this phone. Voice input uses your existing speech assistant; wake detection uses Android’s on-device speech recognition. No analytics. Read aloud uses an installed offline voice.",
                    12f,
                    muted,
                )
                .apply { setLineSpacing(dp(this@MainActivity, 4).toFloat(), 1f) },
            top = 22,
        )
        add(
            body,
            button(this, "Diagnostics") {
                AlertDialog.Builder(this)
                    .setTitle("Cue diagnostics")
                    .setMessage(CueDiagnostics.summary(this))
                    .setPositiveButton("Close", null)
                    .show()
            },
            top = 12,
        )
        setContentView(root)
        updateStatus()
    }

    private fun toggleWake() {
        if (VoiceWakeService.isServiceRunning) {
            VoiceWakeService.stop(this)
            handler.postDelayed({ settings() }, 180)
            return
        }
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3003)
            return
        }
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3005)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Let Cue appear when you call")
                .setMessage(
                    "Android needs ‘Display over other apps’ to open Cue hands-free. Without it, you can open Cue from its notification or widget."
                )
                .setPositiveButton("Allow panel") { _, _ ->
                    waitingForOverlay = true
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName"),
                        )
                    )
                }
                .setNeutralButton("Notification only") { _, _ -> startWakeListener() }
                .setNegativeButton("Later", null)
                .show()
            return
        }
        startWakeListener()
    }

    private fun startWakeListener() {
        runCatching { VoiceWakeService.start(this) }
            .onFailure {
                Toast.makeText(
                        this,
                        "Cue couldn’t start hands-free. Try again while this screen is open.",
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        handler.postDelayed({ settings() }, 400)
    }

    override fun onRequestPermissionsResult(
        code: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (
            code == 3003 &&
                results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
            toggleWake()
        if (code == 3005) {
            if (results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
                toggleWake()
            else
                Toast.makeText(
                        this,
                        "Notifications help you see and stop the wake listener. Enable them in app settings to use hands-free.",
                        Toast.LENGTH_LONG,
                    )
                    .show()
        }
    }

    private fun conversations() {
        page = "conversations"
        val root = page("Your conversations")
        add(
            root,
            button(this, "A new thought  +", true) {
                CueSession.fresh()
                showChat()
            },
            -1,
            52,
            18,
        )
        val search =
            EditText(this).apply {
                hint = "Find a conversation"
                textSize = 15f
                setSingleLine()
                setTextColor(ink)
            }
        add(root, search, -1, 52, 14)
        val scroll = ScrollView(this)
        val list = column(this)
        scroll.addView(list)
        add(root, scroll, -1, 0, 12, 1f)
        fun populate(query: String) {
            list.removeAllViews()
            CueSession.allThreads()
                .filter {
                    it.title.contains(query, true) ||
                        it.messages.any { m -> m.content.contains(query, true) }
                }
                .forEach { thread ->
                    val card =
                        column(this).apply {
                            setPadding(
                                dp(this@MainActivity, 16),
                                dp(this@MainActivity, 14),
                                dp(this@MainActivity, 16),
                                dp(this@MainActivity, 14),
                            )
                            tap(this, lilac, 20) {
                                CueSession.select(thread.id)
                                showChat()
                            }
                        }
                    add(card, text(this, thread.title, 17f, ink, "sans-serif-medium"))
                    add(
                        card,
                        text(
                            this,
                            thread.messages.lastOrNull()?.content?.take(90)
                                ?: "Room for something new.",
                            12f,
                            muted,
                        ),
                        top = 7,
                    )
                    card.setOnLongClickListener {
                        AlertDialog.Builder(this).setItems(arrayOf("Rename", "Delete")) { _, which
                            ->
                            if (which == 0) {
                                val edit = EditText(this).apply { setText(thread.title) }
                                AlertDialog.Builder(this)
                                    .setTitle("Name this conversation")
                                    .setView(edit)
                                    .setPositiveButton("Save") { _, _ ->
                                        CueSession.rename(thread.id, edit.text.toString())
                                        populate(search.text.toString())
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            } else
                                AlertDialog.Builder(this)
                                    .setTitle("Delete this conversation?")
                                    .setPositiveButton("Delete") { _, _ ->
                                        CueSession.delete(thread.id)
                                        populate(search.text.toString())
                                    }
                                    .setNegativeButton("Keep", null)
                                    .show()
                        }
                        true
                    }
                    add(list, card, top = 10)
                }
        }
        search.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    populate(s.toString())
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )
        populate("")
        add(
            root,
            text(this, "Touch and hold a conversation to rename or delete it.", 11f, muted),
            top = 10,
        )
        setContentView(root)
    }

    private fun updateStatus() {
        status?.text =
            when {
                CueSession.stopping -> "• Pausing"
                CueSession.starting -> "• Getting ready"
                CueSession.ready -> "• Ready"
                else -> "• Resting"
            }
        settingsStatus?.text =
            when {
                CueSession.stopping -> "Letting Cue rest…"
                CueSession.starting -> "Getting your assistant ready…"
                CueSession.notice != null -> CueSession.notice
                else ->
                    (if (CueSession.ready) "Assistant ready" else "Assistant resting") +
                        " · " +
                        VoiceWakeService.status
            }
    }
}
