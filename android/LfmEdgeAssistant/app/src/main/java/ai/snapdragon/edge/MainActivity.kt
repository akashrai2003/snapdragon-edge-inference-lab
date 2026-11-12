package ai.snapdragon.edge

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import ai.snapdragon.edge.widget.EdgeAIWidgetProvider
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ink = Color.parseColor("#242128")
    private val muted = Color.parseColor("#78737B")
    private val paper = Color.parseColor("#F8F6F1")
    private val plum = Color.parseColor("#6B50A1")
    private val emerald = Color.parseColor("#2E6F40")
    private val cardBg = Color.parseColor("#F2EDE6")
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var ready = false
    private var transitioning = false
    private var chatting = false
    private var sending = false

    private var statusPill: TextView? = null
    private var engineCardTitle: TextView? = null
    private var engineCardSubtitle: TextView? = null
    private var engineCardAction: TextView? = null

    private var transcript: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var input: EditText? = null
    private var sendButton: TextView? = null
    private var pendingPrompt: String? = null
    private val history = mutableListOf<Pair<String, String>>()

    private val poll = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 15000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = paper
        window.navigationBarColor = paper
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        restoreConversation()
        route(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    override fun onBackPressed() {
        if (chatting) showHome() else super.onBackPressed()
    }

    private fun route(intent: Intent) {
        when (intent.getStringExtra("destination")) {
            "chat" -> showChat()
            "ideas" -> showChat("Give me three small, creative things I could try today. Keep it playful and practical.")
            else -> showHome()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radius: Int = 24, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun text(value: String, size: Float, color: Int = ink, font: String = "sans-serif"): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(font, Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun LinearLayout.add(view: View, width: Int = -1, height: Int = -2, top: Int = 0, weight: Float = 0f) {
        addView(view, LinearLayout.LayoutParams(if (width < 0) width else dp(width), if (height < 0) height else dp(height), weight).apply {
            topMargin = dp(top)
        })
    }

    private fun clickable(view: View, color: Int, radius: Int = 24, action: () -> Unit) {
        view.background = RippleDrawable(ColorStateList.valueOf(0x186B50A1), bg(color, radius), null)
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener { action() }
    }

    private fun button(label: String, dark: Boolean = true, action: () -> Unit): TextView = text(label, 15f, if (dark) Color.WHITE else ink, "sans-serif-medium").apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(11), dp(14), dp(11))
        clickable(this, if (dark) ink else Color.parseColor("#EEEAE3"), 28, action)
    }

    private fun showHome() {
        currentFocus?.windowToken?.let { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it, 0) }
        chatting = false
        transcript = null
        input = null
        sendButton = null

        val scroll = ScrollView(this).apply {
            setBackgroundColor(paper)
            isFillViewport = true
            clipToPadding = false
        }
        val page = column().apply { setPadding(dp(24), dp(14), dp(24), dp(20)) }
        scroll.addView(page)

        // Top Header
        val header = row()
        val brand = row()
        brand.add(ImageView(this).apply { setImageResource(R.drawable.ic_spark); contentDescription = "LFM" }, 25, 25)
        brand.add(text("lfm", 27f, ink, "sans-serif-medium").apply {
            setPadding(dp(8), 0, 0, 0)
            letterSpacing = -0.055f
        }, -2)
        header.add(brand, 0, -2, weight = 1f)

        statusPill = text(statusPillText(), 12f, if (ready) emerald else plum, "sans-serif-medium").apply {
            setPadding(dp(13), dp(8), dp(13), dp(8))
            clickable(this, if (ready) Color.parseColor("#E4EFE3") else Color.parseColor("#EEE8F3"), 18) { showAssistantDetails() }
            contentDescription = "Assistant status and options"
        }
        header.add(statusPill!!, -2, -2)
        page.add(header)

        // Greeting
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) { in 5..11 -> "GOOD MORNING"; in 12..16 -> "GOOD AFTERNOON"; else -> "GOOD EVENING" }
        page.add(text(greeting, 10f, muted, "sans-serif-medium").apply { letterSpacing = 0.17f }, top = 20)
        page.add(text("Big thoughts.\nLittle beginnings.", 35f, ink, "serif").apply { setLineSpacing(dp(-1).toFloat(), 1f) }, top = 8)
        page.add(text("Snapdragon Edge AI • Private and on-device.", 13.5f, muted).apply { setLineSpacing(dp(3).toFloat(), 1f) }, top = 8)

        // Engine Control & Status Card
        val engineCard = column().apply {
            background = bg(cardBg, 22, Color.parseColor("#E6DFD5"))
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val engineRow = row()
        val engineTextCol = column()
        engineCardTitle = text(engineTitleText(), 15f, if (ready) emerald else ink, "sans-serif-medium")
        engineCardSubtitle = text(engineSubtitleText(), 11.5f, muted).apply { setLineSpacing(dp(2).toFloat(), 1f) }
        engineTextCol.add(engineCardTitle!!)
        engineTextCol.add(engineCardSubtitle!!, top = 4)
        engineRow.add(engineTextCol, 0, -2, weight = 1f)

        engineCardAction = text(engineActionText(), 13.5f, if (ready) ink else Color.WHITE, "sans-serif-medium").apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            val btnColor = if (ready) Color.parseColor("#E2DDD5") else plum
            clickable(this, btnColor, 20) { toggleEngine() }
        }
        engineRow.add(engineCardAction!!, -2, -2)
        engineCard.addView(engineRow)
        page.add(engineCard, -1, -2, top = 18)

        // Programmatic Radial Flower Artwork
        page.add(MuseArtView(this).apply {
            contentDescription = "LFM intelligence art"
        }, -1, 180, 18)

        // Ask Button
        val ask = row().apply {
            setPadding(dp(22), 0, dp(22), 0)
            clickable(this, ink, 30) { showChat() }
            contentDescription = "Ask LFM anything"
        }
        ask.add(text("Ask anything", 16f, Color.WHITE, "sans-serif-medium"), 0, -2, weight = 1f)
        ask.add(text("↗", 26f, Color.parseColor("#E0CFF5")), -2)
        page.add(ask, -1, 56, 16)

        // Inspiration Section
        val section = row()
        section.add(text("A little inspiration", 17f, ink, "sans-serif-medium"), 0, -2, weight = 1f)
        val saved = text("Your chat  ↗", 12f, muted).apply {
            setPadding(dp(8), dp(12), 0, dp(12))
            setOnClickListener { showChat() }
            isFocusable = true
            contentDescription = "Open conversation"
        }
        section.add(saved, -2, 48)
        page.add(section, -1, -2, 12)

        val cards = row()
        fun card(symbol: String, title: String, subtitle: String, fill: String, prompt: String): LinearLayout {
            return column().apply {
                setPadding(dp(15), dp(13), dp(11), dp(14))
                clickable(this, Color.parseColor(fill), 20) { showChat(prompt) }
                add(text(symbol, 24f, if (fill == "#F0E3D9") Color.parseColor("#A26345") else Color.parseColor("#647556")))
                add(text(title, 15.5f, ink, "sans-serif-medium"), top = 8)
                add(text(subtitle, 11f, muted).apply { setLineSpacing(dp(2).toFloat(), 1f) }, top = 6)
            }
        }
        cards.add(card("✧", "A fresh idea", "Find your next spark  ↗", "#F0E3D9", "Give me three small, creative things I could try today. Keep it playful and practical."), 0, -2, weight = 1f)
        cards.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        cards.add(card("≈", "A clearer mind", "Untangle a thought  ↗", "#E5E9DF", "Help me untangle something on my mind. Start by asking me one thoughtful, simple question."), 0, -2, weight = 1f)
        page.add(cards)

        // Footer
        val footer = row().apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) }
        footer.add(text("LFM • 100% On-Device Intelligence", 11f, muted), -2)
        page.add(footer)

        setContentView(scroll)
        refreshStatus()
    }

    private fun statusPillText(): String {
        return when {
            transitioning -> "• Starting…"
            ready -> "• Active"
            else -> "• Resting"
        }
    }

    private fun engineTitleText(): String {
        return when {
            transitioning -> "Waking AI Engine…"
            ready -> "● AI Engine Active"
            else -> "○ AI Engine Resting"
        }
    }

    private fun engineSubtitleText(): String {
        return when {
            transitioning -> "Loading LFM3-VL-2B into Adreno 840 GPU…"
            ready -> "Snapdragon 8 Elite • Ready for queries"
            else -> "Paused • Zero battery drain & memory freed"
        }
    }

    private fun engineActionText(): String {
        return when {
            transitioning -> "Starting…"
            ready -> "⏸ Rest"
            else -> "⚡ Start"
        }
    }

    private fun updateHomeEngineViews() {
        statusPill?.text = statusPillText()
        statusPill?.setTextColor(if (ready) emerald else plum)
        statusPill?.background = bg(if (ready) Color.parseColor("#E4EFE3") else Color.parseColor("#EEE8F3"), 18)

        engineCardTitle?.text = engineTitleText()
        engineCardTitle?.setTextColor(if (ready) emerald else ink)
        engineCardSubtitle?.text = engineSubtitleText()
        engineCardAction?.text = engineActionText()
        engineCardAction?.setTextColor(if (ready) ink else Color.WHITE)
        val btnColor = if (ready) Color.parseColor("#E2DDD5") else plum
        clickable(engineCardAction ?: return, btnColor, 20) { toggleEngine() }
    }

    private fun toggleEngine() {
        if (transitioning) return
        if (ready) {
            // Stop Engine
            Toast.makeText(this, "Pausing LFM Engine…", Toast.LENGTH_SHORT).show()
            executor.execute {
                AssistantClient.stopEngine()
                handler.post {
                    ready = false
                    updateHomeEngineViews()
                    EdgeAIWidgetProvider.updateAllWidgets(this)
                }
            }
        } else {
            // Start Engine
            transitioning = true
            updateHomeEngineViews()
            Toast.makeText(this, "Starting LFM Engine on Snapdragon GPU…", Toast.LENGTH_SHORT).show()
            executor.execute {
                AssistantClient.startEngine()
                var isUp = false
                for (i in 0 until 8) {
                    Thread.sleep(1500)
                    if (AssistantClient.isReady()) {
                        isUp = true
                        break
                    }
                }
                handler.post {
                    transitioning = false
                    ready = isUp
                    updateHomeEngineViews()
                    EdgeAIWidgetProvider.updateAllWidgets(this)
                    if (isUp) {
                        Toast.makeText(this@MainActivity, "LFM Engine is ready!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Still starting. Please wait a moment.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showAssistantDetails() {
        val statusMsg = if (ready) {
            "LFM is active and running on your Qualcomm Snapdragon 8 Elite.\n\n" +
            "• LLM: LFM3-VL-2B (Adreno 840 GPU)\n" +
            "• STT: Whisper (Oryon CPU NEON)\n" +
            "• Privacy: 100% on-device, zero data leaves phone."
        } else {
            "LFM is currently resting to preserve battery and memory.\n\n" +
            "Tap 'Start Engine' to wake the model into GPU memory."
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (ready) "LFM Engine Active" else "LFM Engine Resting")
            .setMessage(statusMsg)
            .setPositiveButton("Got it", null)
            .setNegativeButton(if (ready) "Pause Engine" else "Start Engine") { _, _ -> toggleEngine() }
            .setNeutralButton("Add Widget") { _, _ ->
                val manager = AppWidgetManager.getInstance(this)
                if (manager.isRequestPinAppWidgetSupported) {
                    manager.requestPinAppWidget(ComponentName(this, EdgeAIWidgetProvider::class.java), null, null)
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("Touch and hold your home screen, choose Widgets, then look for LFM.")
                        .setPositiveButton("Got it", null)
                        .show()
                }
            }
        builder.show()
    }

    private fun refreshStatus() {
        if (executor.isShutdown) return
        executor.execute {
            val value = AssistantClient.isReady()
            handler.post {
                if (!isDestroyed) {
                    ready = value
                    updateHomeEngineViews()
                }
            }
        }
        EdgeAIWidgetProvider.updateAllWidgets(this)
    }

    private fun showChat(prompt: String? = null) {
        chatting = true
        val root = column().apply {
            setBackgroundColor(paper)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        // Header
        val header = row()
        header.add(button("‹", false) { showHome() }.apply { contentDescription = "Back to home" }, 46, 46)

        val titles = column().apply { setPadding(dp(14), 0, 0, 0) }
        titles.add(text("lfm", 25f, ink, "sans-serif-medium").apply { letterSpacing = -0.055f })
        statusPill = text(if (ready) "• Active" else "• Resting", 11.5f, if (ready) emerald else plum)
        titles.add(statusPill!!, top = 4)
        header.add(titles, 0, -2, weight = 1f)

        header.add(button("+", false) {
            if (sending) {
                Toast.makeText(this, "Please let LFM finish this thought first.", Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Start a fresh conversation?")
                    .setMessage("This clears the current chat history from your phone.")
                    .setPositiveButton("Start fresh") { _, _ ->
                        history.clear()
                        pendingPrompt = null
                        getSharedPreferences("lfm_prefs", MODE_PRIVATE).edit().remove("draft").apply()
                        persistConversation()
                        showChat()
                    }
                    .setNegativeButton("Keep chatting", null)
                    .show()
            }
        }.apply { contentDescription = "Start a new conversation" }, 46, 46)
        root.add(header)

        chatScroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        transcript = column().apply { setPadding(0, dp(22), 0, dp(20)) }
        chatScroll!!.addView(transcript)
        root.add(chatScroll!!, -1, 0, weight = 1f)

        if (history.isEmpty()) {
            transcript!!.add(ImageView(this).apply { setImageResource(R.drawable.ic_spark); contentDescription = "LFM spark" }, 48, 48, 22)
            transcript!!.add(text("What’s on\nyour mind?", 36f, ink, "serif"), top = 20)
            transcript!!.add(text("Bring a question, an idea,\nor just explore local edge AI.", 15f, muted).apply {
                setLineSpacing(dp(4).toFloat(), 1f)
            }, top = 12)
            transcript!!.add(button("Help me find a fresh idea  ↗", false) {
                submit("Give me three small, creative things I could try today. Keep it playful and practical.")
            }, -1, -2, 24)
            transcript!!.add(button("Explain something simply  ↗", false) {
                input?.setText("Explain ")
                input?.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }, -1, -2, 10)
        } else {
            history.forEach { appendMessage(it.first, it.second) }
        }

        if (sending) appendThinking()

        // Composer Row
        val compose = row().apply {
            background = bg(Color.WHITE, 28, Color.parseColor("#E6E0E8"))
            setPadding(dp(16), dp(5), dp(6), dp(5))
        }
        input = EditText(this).apply {
            hint = "Ask LFM anything…"
            setHintTextColor(muted)
            setTextColor(ink)
            textSize = 15f
            background = null
            maxLines = 5
            minLines = 1
            setPadding(0, dp(10), dp(8), dp(10))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) {
                    submitInput()
                    true
                } else false
            }
            contentDescription = "Message to LFM"
        }
        compose.add(input!!, 0, -2, weight = 1f)
        sendButton = button("↑") { submitInput() }.apply {
            contentDescription = "Send message"
            alpha = if (sending) 0.4f else 1f
            isEnabled = !sending
        }
        compose.add(sendButton!!, 48, 48)
        root.add(compose)
        root.add(text("Snapdragon Edge AI • Completely private.", 10.5f, muted).apply { gravity = Gravity.CENTER }, top = 10)

        setContentView(root)
        if (pendingPrompt != null && !sending) {
            input?.setText(pendingPrompt)
            pendingPrompt = null
        }
        if (prompt != null) {
            if (sending) {
                input?.setText(prompt)
                Toast.makeText(this, "Your next question is queued.", Toast.LENGTH_SHORT).show()
            } else {
                submit(prompt)
            }
        }
        scrollToEnd()
    }

    private fun submitInput() {
        val value = input?.text?.toString()?.trim().orEmpty()
        if (value.isNotEmpty()) submit(value)
    }

    private fun appendMessage(role: String, content: String) {
        val box = column().apply {
            setPadding(dp(17), dp(16), dp(17), dp(18))
            background = bg(if (role == "user") Color.parseColor("#EBE4F2") else Color.WHITE, 22)
        }
        box.add(text(if (role == "user") "YOU" else "LFM", 9.5f, if (role == "user") plum else muted, "sans-serif-medium").apply {
            letterSpacing = 0.13f
        })
        box.add(text(content, 15.5f).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextIsSelectable(true)
        }, top = 9)
        transcript?.add(box, top = 12)
    }

    private fun appendThinking() {
        transcript?.add(text("LFM is thinking…", 14f, plum).apply {
            tag = "thinking"
            setPadding(dp(8), dp(18), 0, dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
    }

    private fun scrollToEnd() {
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun submit(value: String) {
        if (sending || value.isBlank()) return
        if (!ready) {
            AlertDialog.Builder(this)
                .setTitle("AI Engine is Resting")
                .setMessage("LFM engine is currently paused to save battery. Would you like to wake the engine now?")
                .setPositiveButton("Wake Engine") { _, _ ->
                    toggleEngine()
                    input?.setText(value)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        if (history.isEmpty()) transcript?.removeAllViews()
        input?.setText("")
        history.add("user" to value)
        appendMessage("user", value)
        getSharedPreferences("lfm_prefs", MODE_PRIVATE).edit().putString("draft", value).apply()
        sending = true
        sendButton?.isEnabled = false
        sendButton?.alpha = 0.4f
        appendThinking()
        scrollToEnd()

        val requestHistory = history.toList()
        executor.execute {
            val result = runCatching { AssistantClient.reply(requestHistory) }
            handler.post {
                if (isDestroyed) return@post
                sending = false
                sendButton?.isEnabled = true
                sendButton?.alpha = 1f
                transcript?.findViewWithTag<View>("thinking")?.let { transcript?.removeView(it) }

                result.onSuccess { answer ->
                    history.add("assistant" to answer)
                    persistConversation()
                    getSharedPreferences("lfm_prefs", MODE_PRIVATE).edit().remove("draft").apply()
                    if (chatting) appendMessage("assistant", answer)
                    ready = true
                    statusPill?.text = "• Active"
                }.onFailure {
                    if (history.lastOrNull()?.first == "user") history.removeAt(history.lastIndex)
                    persistConversation()
                    pendingPrompt = value
                    if (chatting) {
                        transcript?.removeAllViews()
                        history.forEach { appendMessage(it.first, it.second) }
                        val error = column().apply {
                            setPadding(dp(17), dp(18), dp(17), dp(18))
                            background = bg(Color.parseColor("#F0E3D9"), 22)
                        }
                        error.add(text("Let’s try that again.", 17f, ink, "serif"))
                        error.add(text("LFM couldn’t respond. The local engine may be busy or reloading.", 13.5f, muted), top = 7)
                        transcript?.add(error, top = 12)
                        input?.setText(value)
                        pendingPrompt = null
                    }
                    refreshStatus()
                }
                scrollToEnd()
            }
        }
    }

    private fun persistConversation() {
        val array = JSONArray()
        history.takeLast(60).forEach { array.put(JSONObject().put("role", it.first).put("content", it.second)) }
        getSharedPreferences("lfm_prefs", MODE_PRIVATE).edit().putString("conversation", array.toString()).apply()
    }

    private fun restoreConversation() {
        val prefs = getSharedPreferences("lfm_prefs", MODE_PRIVATE)
        // Check for migration from previous "muse" store if present
        val oldPrefs = getSharedPreferences("muse", MODE_PRIVATE)
        if (!prefs.contains("conversation") && oldPrefs.contains("conversation")) {
            val oldConv = oldPrefs.getString("conversation", "[]")
            prefs.edit().putString("conversation", oldConv).apply()
        }
        pendingPrompt = prefs.getString("draft", null)
        runCatching {
            val array = JSONArray(prefs.getString("conversation", "[]"))
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                history.add(entry.getString("role") to entry.getString("content"))
            }
        }
    }
}
