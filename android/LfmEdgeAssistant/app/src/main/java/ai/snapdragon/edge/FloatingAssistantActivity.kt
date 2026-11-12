package ai.snapdragon.edge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Gemini-Style Floating Bottom-Sheet Assistant.
 * Slides up gracefully over any active application without disrupting the user's workspace.
 * Features 100% on-device Adreno 840 GPU token streaming and Oryon NEON Whisper STT.
 */
class FloatingAssistantActivity : Activity() {
    private val darkSurface = Color.parseColor("#1C1924")
    private val cardSurface = Color.parseColor("#262232")
    private val cardBorder = Color.parseColor("#3C354D")
    private val lilac = Color.parseColor("#9B7ED9")
    private val lilacDark = Color.parseColor("#6B50A1")
    private val inkLight = Color.parseColor("#F5F3F8")
    private val muted = Color.parseColor("#8E8799")
    private val emerald = Color.parseColor("#34C759")

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val voiceRecorder = VoiceRecorder()

    private var sheetContainer: LinearLayout? = null
    private var transcript: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var input: EditText? = null
    private var sendButton: TextView? = null
    private var micButton: FrameLayout? = null
    private var voiceWaveContainer: LinearLayout? = null
    private var voiceStatusText: TextView? = null
    private val waveBars = mutableListOf<View>()

    private var isRecording = false
    private var isGenerating = false
    private val history = mutableListOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set window to transparent with dim scrim
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.48f)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        restoreRecentConversation()
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val autoListen = intent?.getBooleanExtra("auto_listen", false) ?: false
        if (autoListen) {
            handler.postDelayed({
                startVoiceInput()
            }, 250)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radius: Int = 24, strokeColor: Int? = null, strokeWidthDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) setStroke(dp(strokeWidthDp), strokeColor)
        }

    private fun text(
        value: String,
        size: Float,
        color: Int = inkLight,
        font: String = "sans-serif"
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(font, Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun buildUi() {
        // Root container: Fullscreen frame; tapping scrim dismisses
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setOnClickListener { dismissSheet() }
        }

        // Bottom Sheet Card container
        sheetContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(darkSurface, 28, cardBorder, 1)
            setPadding(dp(18), dp(14), dp(18), dp(18))
            isClickable = true
            isFocusable = true
            elevation = dp(16).toFloat()
        }

        val sheetParams = FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.BOTTOM
            marginStart = dp(12)
            marginEnd = dp(12)
            bottomMargin = dp(24)
        }
        root.addView(sheetContainer, sheetParams)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val navBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            sheetParams.bottomMargin = dp(14) + navBottom
            sheetContainer?.layoutParams = sheetParams
            insets
        }

        // Top Drag Handle & Status Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Pill Chip: "✦ Snapdragon 8 Elite • Local"
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(cardSurface, 14, cardBorder, 1)
            setPadding(dp(10), dp(5), dp(12), dp(5))
        }
        val sparkIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_spark)
            setColorFilter(lilac)
        }
        chip.addView(sparkIcon, LinearLayout.LayoutParams(dp(14), dp(14)))
        val chipText = text(" 100% Local • Snapdragon 8 Elite", 11.5f, lilac, "sans-serif-medium")
        chip.addView(chipText)
        header.addView(chip, LinearLayout.LayoutParams(-2, -2, 1f))

        // Fullscreen expand button
        val expandBtn = text("↗", 16f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), bg(Color.TRANSPARENT, 12), null)
            setOnClickListener {
                startActivity(Intent(this@FloatingAssistantActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                })
                dismissSheet()
            }
        }
        header.addView(expandBtn, LinearLayout.LayoutParams(-2, -2))

        // Close button '✕'
        val closeBtn = text("✕", 15f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(6), dp(4))
            background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), bg(Color.TRANSPARENT, 12), null)
            setOnClickListener { dismissSheet() }
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(-2, -2))

        sheetContainer?.addView(header, LinearLayout.LayoutParams(-1, -2))

        // Scrollable Transcript / Messages
        chatScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        transcript = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        chatScroll?.addView(transcript, LinearLayout.LayoutParams(-1, -2))

        // Set max scroll height to 360dp
        val maxScrollHeight = dp(340)
        val scrollParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(10)
        }
        sheetContainer?.addView(chatScroll, scrollParams)

        // Populate transcript with existing messages or welcome card
        refreshTranscript()

        // Voice Waveform / Listening Visualizer (hidden by default)
        voiceWaveContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bg(cardSurface, 20, lilacDark, 1)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
        }

        voiceStatusText = text("Listening… speak now", 13.5f, lilac, "sans-serif-medium").apply {
            gravity = Gravity.CENTER
        }
        voiceWaveContainer?.addView(voiceStatusText, LinearLayout.LayoutParams(-1, -2))

        val waveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        }
        waveBars.clear()
        for (i in 0 until 9) {
            val bar = View(this).apply {
                background = bg(lilac, 3)
            }
            val barParams = LinearLayout.LayoutParams(dp(4), dp(16)).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            waveRow.addView(bar, barParams)
            waveBars.add(bar)
        }
        voiceWaveContainer?.addView(waveRow, LinearLayout.LayoutParams(-1, -2))

        val tapToStopText = text("Tap microphone to finish", 11f, muted).apply {
            gravity = Gravity.CENTER
        }
        voiceWaveContainer?.addView(tapToStopText, LinearLayout.LayoutParams(-1, -2))

        sheetContainer?.addView(voiceWaveContainer, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })

        // Floating Composer Capsule Bar (Gemini-style)
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(cardSurface, 26, cardBorder, 1)
            setPadding(dp(14), dp(4), dp(6), dp(4))
        }

        // Plus / Suggestion icon
        val plusIcon = text("+", 20f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(10), dp(4))
            setOnClickListener {
                submitPrompt("Give me a fast creative idea or summary.")
            }
        }
        composer.addView(plusIcon, LinearLayout.LayoutParams(-2, -2))

        // Input field
        input = EditText(this).apply {
            hint = "Ask LFM anything…"
            setHintTextColor(muted)
            setTextColor(inkLight)
            textSize = 15f
            background = null
            maxLines = 4
            minLines = 1
            setPadding(0, dp(8), dp(8), dp(8))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    val q = text.toString().trim()
                    if (q.isNotEmpty()) submitPrompt(q)
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val hasText = !s.isNullOrBlank()
                    sendButton?.visibility = if (hasText) View.VISIBLE else View.GONE
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))

        // Mic Button
        micButton = FrameLayout(this).apply {
            background = bg(lilacDark, 20)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleVoice() }
        }
        val micIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(Color.WHITE)
        }
        micButton?.addView(micIcon, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        composer.addView(micButton, LinearLayout.LayoutParams(dp(40), dp(40)))

        // Send Button (up-arrow)
        sendButton = text("↑", 18f, Color.WHITE, "sans-serif-medium").apply {
            gravity = Gravity.CENTER
            background = bg(lilacDark, 20)
            visibility = View.GONE
            setOnClickListener {
                val q = input?.text?.toString()?.trim().orEmpty()
                if (q.isNotEmpty()) submitPrompt(q)
            }
        }
        val sendParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            marginStart = dp(6)
        }
        composer.addView(sendButton, sendParams)

        sheetContainer?.addView(composer, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)

        // Slide up entry animation
        sheetContainer?.translationY = dp(400).toFloat()
        sheetContainer?.animate()
            ?.translationY(0f)
            ?.setDuration(260)
            ?.setInterpolator(DecelerateInterpolator())
            ?.start()
    }

    private fun dismissSheet() {
        if (voiceRecorder.recording) voiceRecorder.cancel()
        sheetContainer?.animate()
            ?.translationY(dp(450).toFloat())
            ?.alpha(0f)
            ?.setDuration(200)
            ?.withEndAction {
                finish()
                overridePendingTransition(0, 0)
            }
            ?.start()
    }

    private fun refreshTranscript() {
        transcript?.removeAllViews()
        if (history.isEmpty()) {
            val welcome = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            welcome.addView(text("Hey! How can I help you?", 18f, inkLight, "sans-serif-medium"))
            welcome.addView(text("Ask a question, transcribe voice, or brainstorm with local AI.", 13f, muted).apply {
                setPadding(0, dp(4), 0, dp(10))
            })

            // Suggestion chips row
            val chips = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            fun addChip(title: String, prompt: String) {
                val chip = text(title, 12f, lilac, "sans-serif-medium").apply {
                    background = bg(cardSurface, 12, cardBorder, 1)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener { submitPrompt(prompt) }
                }
                val p = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
                chips.addView(chip, p)
            }
            addChip("Spark an idea ↗", "Give me 3 practical, creative thoughts for today.")
            addChip("Quick summary ↗", "Explain quantum computing in two clear sentences.")
            welcome.addView(chips)

            transcript?.addView(welcome)
        } else {
            history.takeLast(6).forEach { (role, content) ->
                appendMessageBubble(role, content)
            }
        }
        scrollToBottom()
    }

    private fun appendMessageBubble(role: String, content: String): TextView {
        val isUser = role == "user"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(if (isUser) cardSurface else Color.parseColor("#211D2B"), 18, if (isUser) cardBorder else null, 1)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }

        val tagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tag = text(if (isUser) "YOU" else "LFM", 9.5f, if (isUser) lilac else emerald, "sans-serif-medium").apply {
            letterSpacing = 0.12f
        }
        tagRow.addView(tag, LinearLayout.LayoutParams(0, -2, 1f))

        if (!isUser) {
            val copyBtn = text("Copy", 10.5f, muted).apply {
                setOnClickListener {
                    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("LFM Response", content))
                    Toast.makeText(this@FloatingAssistantActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            }
            tagRow.addView(copyBtn)
        }
        box.addView(tagRow)

        val msgText = text(content, 14.5f, inkLight).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(msgText)

        val boxParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        }
        transcript?.addView(box, boxParams)
        return msgText
    }

    private fun scrollToBottom() {
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toggleVoice() {
        if (isRecording) {
            stopVoiceInput()
        } else {
            startVoiceInput()
        }
    }

    private fun startVoiceInput() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2002)
            return
        }

        vibrateClick()
        isRecording = true
        micButton?.background = bg(Color.parseColor("#E53935"), 20) // Red while recording
        voiceWaveContainer?.visibility = View.VISIBLE
        voiceStatusText?.text = "Listening… speak your question"

        val started = voiceRecorder.startRecording(
            onRmsChanged = { rmsDb ->
                handler.post {
                    updateWaveform(rmsDb)
                }
            },
            onSilenceDetected = {
                handler.post {
                    if (isRecording) stopVoiceInput()
                }
            }
        )

        if (!started) {
            isRecording = false
            micButton?.background = bg(lilacDark, 20)
            voiceWaveContainer?.visibility = View.GONE
            Toast.makeText(this, "Could not access microphone", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVoiceInput() {
        if (!isRecording) return
        isRecording = false
        vibrateClick()
        micButton?.background = bg(lilacDark, 20)
        voiceStatusText?.text = "Transcribing with local Whisper…"

        executor.execute {
            val wavBytes = voiceRecorder.stopRecording()
            if (wavBytes.size < 1000) {
                handler.post {
                    voiceWaveContainer?.visibility = View.GONE
                }
                return@execute
            }

            val transcribed = runCatching {
                AssistantClient.transcribeWav(wavBytes)
            }.getOrDefault("")

            handler.post {
                voiceWaveContainer?.visibility = View.GONE
                if (transcribed.isNotBlank()) {
                    input?.setText(transcribed)
                    submitPrompt(transcribed)
                } else {
                    Toast.makeText(this@FloatingAssistantActivity, "No speech detected. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateWaveform(rmsDb: Float) {
        val norm = ((rmsDb - 10.0f) / 35.0f).coerceIn(0.15f, 1.0f)
        for (i in waveBars.indices) {
            val factor = 1.0f - (kotlin.math.abs(i - 4) * 0.12f)
            val barHeight = dp((norm * factor * 28).toInt().coerceAtLeast(4))
            val lp = waveBars[i].layoutParams
            lp.height = barHeight
            waveBars[i].layoutParams = lp
        }
    }

    private fun submitPrompt(promptText: String) {
        if (isGenerating || promptText.isBlank()) return
        input?.setText("")
        isGenerating = true
        sendButton?.visibility = View.GONE

        // Append user query to transcript
        if (history.isEmpty()) transcript?.removeAllViews()
        history.add("user" to promptText)
        appendMessageBubble("user", promptText)

        // Thinking indicator bubble
        val thinkingBubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(Color.parseColor("#211D2B"), 18)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            tag = "thinking_bubble"
        }
        val thinkText = text("LFM is thinking…", 13f, lilac).apply {
            tag = "think_label"
        }
        thinkingBubble.addView(thinkText)
        transcript?.addView(thinkingBubble, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        scrollToBottom()

        val reqHistory = history.toList()
        executor.execute {
            var answerView: TextView? = null
            val fullText = StringBuilder()

            val result = runCatching {
                AssistantClient.streamReply(reqHistory) { token ->
                    handler.post {
                        if (isDestroyed) return@post
                        if (answerView == null) {
                            transcript?.findViewWithTag<View>("thinking_bubble")?.let { transcript?.removeView(it) }
                            answerView = appendMessageBubble("assistant", "")
                        }
                        fullText.append(token)
                        answerView?.text = fullText.toString()
                        scrollToBottom()
                    }
                }
            }

            handler.post {
                if (isDestroyed) return@post
                isGenerating = false
                transcript?.findViewWithTag<View>("thinking_bubble")?.let { transcript?.removeView(it) }

                result.onSuccess { answer ->
                    history.add("assistant" to answer)
                    persistConversation()
                }.onFailure { err ->
                    if (history.lastOrNull()?.first == "user") history.removeAt(history.lastIndex)
                    val errorBox = LinearLayout(this@FloatingAssistantActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = bg(Color.parseColor("#341C1C"), 18, Color.parseColor("#7A3232"), 1)
                        setPadding(dp(14), dp(10), dp(14), dp(12))
                    }
                    errorBox.addView(text("Engine Resting or Busy", 13.5f, Color.parseColor("#FF8B8B"), "sans-serif-medium"))
                    errorBox.addView(text("Make sure LFM engine is active. Tap to wake.", 12f, inkLight).apply {
                        setPadding(0, dp(4), 0, dp(6))
                    })
                    val wakeBtn = text("⚡ Start Engine", 12f, Color.WHITE, "sans-serif-medium").apply {
                        background = bg(lilacDark, 12)
                        setPadding(dp(10), dp(5), dp(10), dp(5))
                        setOnClickListener {
                            AssistantClient.startEngine(this@FloatingAssistantActivity)
                            Toast.makeText(this@FloatingAssistantActivity, "Waking on-device engine…", Toast.LENGTH_SHORT).show()
                        }
                    }
                    errorBox.addView(wakeBtn, LinearLayout.LayoutParams(-2, -2))
                    transcript?.addView(errorBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                }
                scrollToBottom()
            }
        }
    }

    private fun vibrateClick() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(35)
            }
        } catch (_: Exception) {}
    }

    private fun persistConversation() {
        val array = JSONArray()
        history.takeLast(40).forEach { array.put(JSONObject().put("role", it.first).put("content", it.second)) }
        getSharedPreferences("lfm_prefs", MODE_PRIVATE).edit().putString("conversation", array.toString()).apply()
    }

    private fun restoreRecentConversation() {
        runCatching {
            val prefs = getSharedPreferences("lfm_prefs", MODE_PRIVATE)
            val array = JSONArray(prefs.getString("conversation", "[]"))
            history.clear()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                history.add(item.getString("role") to item.getString("content"))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (voiceRecorder.recording) voiceRecorder.cancel()
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }
}
