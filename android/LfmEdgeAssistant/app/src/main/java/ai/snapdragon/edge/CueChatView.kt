package ai.snapdragon.edge

import ai.snapdragon.edge.CueUi.add
import ai.snapdragon.edge.CueUi.background
import ai.snapdragon.edge.CueUi.button
import ai.snapdragon.edge.CueUi.column
import ai.snapdragon.edge.CueUi.dp
import ai.snapdragon.edge.CueUi.formatted
import ai.snapdragon.edge.CueUi.icon
import ai.snapdragon.edge.CueUi.ink
import ai.snapdragon.edge.CueUi.lilac
import ai.snapdragon.edge.CueUi.muted
import ai.snapdragon.edge.CueUi.plum
import ai.snapdragon.edge.CueUi.row
import ai.snapdragon.edge.CueUi.text
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.util.concurrent.Executors

class BoundedScroll(context: Context) : ScrollView(context) {
    var limit = Int.MAX_VALUE
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(width: Int, height: Int) {
        val mode = MeasureSpec.getMode(height)
        val size =
            if (mode == MeasureSpec.UNSPECIFIED) limit
            else minOf(limit, MeasureSpec.getSize(height))
        super.onMeasure(
            width,
            MeasureSpec.makeMeasureSpec(
                size,
                if (mode == MeasureSpec.EXACTLY && size <= limit) mode else MeasureSpec.AT_MOST,
            ),
        )
    }
}

class CueChatView(
    private val activity: Activity,
    private val compact: Boolean,
    private val permission: () -> Unit,
    private val attachImage: () -> Unit,
) : LinearLayout(activity) {
    val scroll = BoundedScroll(activity)
    private val transcript = column(activity)
    private val input: EditText
    private val action = FrameLayout(activity)
    private val actionText = text(activity, "↑", 24f, Color.WHITE)
    private val actionIcon = ImageView(activity)
    private val voicePanel = column(activity)
    private val voiceLabel = text(activity, "I’m listening.", 26f, ink, "serif")
    private val wave = row(activity)
    private val conversationBar = row(activity)
    private val conversationLabel = text(activity, "Conversation on", 12f, plum)
    private val interruptButton = button(activity, "Interrupt") { interruptAndListen() }
    private var conversationActive = false
    private var conversationThread = ""
    private var waitingForAnswer = false
    private var monitoringInterruption = false
    private var interrupting = false
    private var interruptedEcho = ""
    private var followUpListening = false
    private var followUpPending = false
    private var nextMonitorAt = 0L
    private var monitorErrors = 0
    private var voiceEpoch = 0
    private val reconcile = Runnable { reconcileConversation() }
    private val resumeListening = Runnable {
        followUpPending = false
        if (conversationActive && !destroyed) startVoice(followUp = true)
    }
    private val conversationTimeout = Runnable {
        if (conversationActive) {
            endConversation(stopAnswer = true)
            error.text = "Conversation paused. Tap the microphone when you’re ready."
            error.visibility = View.VISIBLE
        }
    }
    private val error = text(activity, "", 13f, muted)
    private val attachmentLabel = text(activity, "Image attached · Remove", 12f, plum)
    private val latest =
        button(activity, "Jump to latest ↓") {
            follow = true
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    private val recorder = VoiceRecorder()
    private val voiceTurn = VoiceTurn(activity)
    private val voicePreview = text(activity, "", 14f, plum)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var transcription: AssistantClient.Request? = null
    private var voiceState = "idle"
    private var signature = ""
    private val targets = mutableMapOf<String, TextView>()
    private var updatingDraft = false
    private var follow = true
    private var destroyed = false
    private val waves = mutableListOf<View>()
    private val observed = {
        render()
        scheduleConversation()
    }

    init {
        orientation = VERTICAL
        scroll.isFillViewport = !compact
        scroll.clipToPadding = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(transcript)
        scroll.setOnScrollChangeListener { _: View, _: Int, y: Int, _: Int, old: Int ->
            if (y < old - 2) follow = false
            if (scroll.getChildAt(0).height <= y + scroll.height + dp(activity, 32)) follow = true
            latest.visibility = if (!follow && CueSession.generating) View.VISIBLE else View.GONE
        }
        add(this, scroll, -1, if (compact) -2 else 0, weight = if (compact) 0f else 1f)
        add(this, latest, -1, 44)
        latest.visibility = View.GONE
        voicePanel.setPadding(
            dp(activity, 12),
            dp(activity, 15),
            dp(activity, 12),
            dp(activity, 15),
        )
        voicePanel.background = background(activity, lilac, 24)
        voicePanel.gravity = Gravity.CENTER
        voiceLabel.gravity = Gravity.CENTER
        add(voicePanel, voiceLabel)
        add(
            voicePanel,
            voicePreview.apply {
                gravity = Gravity.CENTER
                maxLines = 3
            },
            top = 12,
        )
        wave.gravity = Gravity.CENTER
        for (i in 0..8) {
            val bar = View(activity).apply { background = background(activity, plum, 3) }
            wave.addView(
                bar,
                LayoutParams(dp(activity, 4), dp(activity, 12)).apply {
                    marginStart = dp(activity, 4)
                    marginEnd = dp(activity, 4)
                },
            )
            waves.add(bar)
        }
        add(voicePanel, wave, -1, 54, 8)
        val controls = row(activity).apply { gravity = Gravity.CENTER }
        add(controls, button(activity, "Done speaking", true) { finishVoice() }, -2)
        add(controls, button(activity, "Cancel") { cancelVoice() }, -2)
        add(voicePanel, controls, -1, -2, 10)
        add(this, voicePanel, -1, -2, 8)
        voicePanel.visibility = View.GONE
        conversationBar.gravity = Gravity.CENTER_VERTICAL
        conversationBar.background = background(activity, lilac, 18)
        conversationBar.setPadding(
            dp(activity, 12),
            dp(activity, 3),
            dp(activity, 4),
            dp(activity, 3),
        )
        add(conversationBar, conversationLabel, 0, -2, weight = 1f)
        add(conversationBar, interruptButton, -2, 44)
        add(conversationBar, button(activity, "End") { endConversation(stopAnswer = true) }, -2, 44)
        add(this, conversationBar, -1, -2, 8)
        conversationBar.visibility = View.GONE
        add(this, error, -1, -2, 6)
        error.visibility = View.GONE
        add(this, attachmentLabel, -1, 40, 4)
        attachmentLabel.visibility = View.GONE
        attachmentLabel.setOnClickListener { CueSession.attach(null) }
        val composer =
            row(activity).apply {
                background = background(activity, Color.WHITE, 28, Color.parseColor("#E3DDE7"))
                setPadding(dp(activity, 5), dp(activity, 5), dp(activity, 6), dp(activity, 5))
            }
        val plus =
            icon(activity, "+", "Attach an image") { if (!CueSession.generating) attachImage() }
        add(composer, plus, 44, 48)
        input =
            EditText(activity).apply {
                textSize = 15f
                setTextColor(ink)
                setHintTextColor(muted)
                hint = "Ask anything…"
                background = null
                minLines = 1
                maxLines = 4
                setPadding(dp(activity, 4), dp(activity, 10), dp(activity, 5), dp(activity, 10))
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_SEND
                contentDescription = "Message to Cue"
                setOnEditorActionListener { _, id, _ ->
                    if (id == EditorInfo.IME_ACTION_SEND) {
                        send()
                        true
                    } else false
                }
                addTextChangedListener(
                    object : android.text.TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) {}

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) {
                            if (!updatingDraft) CueSession.draft(s.toString())
                            updateAction()
                        }

                        override fun afterTextChanged(s: android.text.Editable?) {}
                    }
                )
            }
        add(composer, input, 0, -2, weight = 1f)
        action.background = background(activity, ink, 24)
        action.isFocusable = true
        action.isClickable = true
        actionText.gravity = Gravity.CENTER
        action.addView(actionText, FrameLayout.LayoutParams(-1, -1))
        actionIcon.setImageResource(R.drawable.ic_mic)
        actionIcon.setColorFilter(Color.WHITE)
        action.addView(
            actionIcon,
            FrameLayout.LayoutParams(dp(activity, 21), dp(activity, 21), Gravity.CENTER),
        )
        action.setOnClickListener {
            when {
                CueSession.generating -> endConversation(stopAnswer = true)
                voiceState != "idle" -> cancelVoice()
                input.text.isNotBlank() || CueSession.attachment != null -> send()
                else -> startVoice()
            }
        }
        add(composer, action, 48, 48)
        add(this, composer, -1, -2, 10)
        add(
            this,
            text(activity, "A little space to think.", 10f, muted).apply {
                gravity = Gravity.CENTER
            },
            top = 9,
        )
        CueSession.observe(observed)
        SpeechOutput.observe(observed)
    }

    private fun send() {
        cancelVoice()
        SpeechOutput.stop()
        CueSession.send(input.text.toString())
    }

    fun insertDraft(value: String) {
        CueSession.draft(value)
        updatingDraft = true
        input.setText(value)
        input.setSelection(input.length())
        updatingDraft = false
        updateAction()
    }

    fun render() {
        if (destroyed) return
        updateConversationBar()
        val t = CueSession.current
        val list = if (compact) t.messages.takeLast(2) else t.messages
        val key = t.id + ":" + list.joinToString { it.id }
        if (signature != key) {
            signature = key
            targets.clear()
            transcript.removeAllViews()
            follow = true
            if (list.isEmpty()) welcome() else list.forEach { message(it) }
        }
        list.forEach { message ->
            targets[message.id]?.let { view ->
                val display =
                    when {
                        message.content.isEmpty() && message.state == "streaming" ->
                            "Cue is thinking…"
                        message.content.isEmpty() -> "No answer yet."
                        else -> message.content
                    }
                if (view.tag != display + message.state) {
                    view.text = if (message.state == "streaming") display else formatted(display)
                    view.tag = display + message.state
                }
            }
            transcript.findViewWithTag<TextView>("speech-${message.id}")?.text =
                if (SpeechOutput.speaking) "Stop speaking" else "Read aloud"
            transcript.findViewWithTag<TextView>("state-${message.id}")?.apply {
                text =
                    when (message.state) {
                        "stopped" -> "Stopped · Retry"
                        "interrupted" -> "Interrupted · Retry"
                        "failed" -> "Couldn’t finish · Retry"
                        "length" -> "Answer limit reached · Continue"
                        else -> ""
                    }
                visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
        }
        if (input.text.toString() != t.draft) {
            updatingDraft = true
            input.setText(t.draft)
            input.setSelection(input.length())
            updatingDraft = false
        }
        attachmentLabel.visibility = if (CueSession.attachment != null) View.VISIBLE else View.GONE
        error.text = CueSession.notice.orEmpty()
        error.visibility = if (CueSession.notice != null) View.VISIBLE else View.GONE
        updateAction()
        if (follow) scroll.post { if (!destroyed && follow) scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun welcome() {
        val c = activity
        transcript.setPadding(
            dp(c, 8),
            dp(c, compact.let { if (it) 12 else 28 }),
            dp(c, 8),
            dp(c, 16),
        )
        if (!compact)
            add(
                transcript,
                ImageView(c).apply { setImageResource(R.drawable.ic_spark) },
                48,
                48,
                12,
            )
        add(
            transcript,
            text(
                c,
                if (compact) "A little help,\nright when you need it." else "What’s on\nyour mind?",
                if (compact) 27f else 36f,
                ink,
                "serif",
            ),
            top = if (compact) 4 else 20,
        )
        add(transcript, text(c, "A question, an idea, or a fresh start.", 13f, muted), top = 12)
        val starters = column(c)
        add(
            starters,
            button(c, "Spark an idea  ↗") {
                insertDraft("Suggest three small, creative things I could try today.")
                CueSession.send()
            },
            top = 14,
        )
        add(
            starters,
            button(c, "Explain something simply  ↗") {
                insertDraft("Explain ")
                input.requestFocus()
                (c.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            },
            top = 8,
        )
        add(transcript, starters)
    }

    private fun message(message: CueMessage) {
        val c = activity
        val box =
            column(c).apply {
                setPadding(dp(c, 15), dp(c, 14), dp(c, 15), dp(c, 12))
                background = background(c, if (message.role == "user") lilac else Color.WHITE, 22)
            }
        add(
            box,
            text(c, if (message.role == "user") "YOU" else "CUE", 10f, muted, "sans-serif-medium")
                .apply { letterSpacing = .12f },
        )
        message.imagePath?.let { path ->
            val bitmap = android.graphics.BitmapFactory.decodeFile(path)
            add(
                box,
                ImageView(c).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "Attached image"
                },
                -1,
                140,
                8,
            )
        }
        val body =
            text(c, "", 15.5f).apply {
                setLineSpacing(dp(c, 4).toFloat(), 1f)
                setTextIsSelectable(true)
            }
        targets[message.id] = body
        add(box, body, top = 9)
        if (message.role == "assistant") {
            val actions = row(c)
            add(
                actions,
                button(c, "Copy") {
                    (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Cue’s answer", message.content))
                    Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show()
                },
                -2,
                48,
            )
            add(
                actions,
                button(c, "Read aloud") { SpeechOutput.read(c, message.content) }
                    .apply { tag = "speech-${message.id}" },
                -2,
                48,
            )
            add(box, actions, -1, -2, 8)
            add(
                box,
                text(c, "", 12f, plum).apply {
                    tag = "state-${message.id}"
                    minHeight = dp(c, 44)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener {
                        if (message.state == "length") {
                            insertDraft("Please continue.")
                            CueSession.send()
                        } else CueSession.retry()
                    }
                },
            )
        }
        add(transcript, box, top = 10)
    }

    private fun updateAction() {
        val textPresent = input.text.isNotBlank() || CueSession.attachment != null
        val stopping = CueSession.generating || voiceState != "idle"
        actionIcon.visibility = if (!stopping && !textPresent) View.VISIBLE else View.GONE
        actionText.visibility = if (stopping || textPresent) View.VISIBLE else View.GONE
        actionText.text = if (stopping) "■" else "↑"
        action.contentDescription =
            when {
                CueSession.generating -> "Stop answer"
                voiceState != "idle" -> "Cancel voice input"
                textPresent -> "Send message"
                else -> "Speak to Cue"
            }
        input.isEnabled = voiceState == "idle"
    }

    fun startVoice(followUp: Boolean = false) {
        if (destroyed || CueSession.generating || voiceState != "idle") return
        if (
            activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permission()
            return
        }
        if (!followUp) {
            val prefs = VoiceReplyPreferences.store(activity)
            conversationActive =
                prefs.getBoolean("continue_conversation", true) &&
                    !prefs.getBoolean("review_voice", false)
            conversationThread = CueSession.current.id
        }
        followUpListening = followUp
        followUpPending = false
        waitingForAnswer = false
        monitoringInterruption = false
        interrupting = false
        voiceTurn.cancel()
        val epoch = ++voiceEpoch
        SpeechOutput.stop()
        if (VoiceReplyPreferences.enabled(activity)) SpeechOutput.warm(activity)
        AudioGate.hold("voice-input")
        voiceState = "preparing"
        voiceLabel.text = "Getting ready…"
        showVoice()
        AudioGate.whenQuiet { quiet ->
            if (destroyed || epoch != voiceEpoch || voiceState != "preparing") return@whenQuiet
            if (!quiet) {
                cancelVoice()
                error.text = "The microphone is busy. Try again in a moment."
                error.visibility = View.VISIBLE
                return@whenQuiet
            }
            voiceState = "recording"
            voiceLabel.text = if (followUp) "Anything else?" else "I’m listening."
            updateAction()
            voicePreview.text = ""
            if (voiceTurn.supported) {
                val began =
                    voiceTurn.start(
                        onReady = {
                            voiceState = "recording"
                            voiceLabel.text = if (followUp) "Anything else?" else "I’m listening."
                            updateAction()
                        },
                        onLevel = { level ->
                            waves.forEachIndexed { i, bar ->
                                bar.layoutParams =
                                    bar.layoutParams.apply {
                                        height =
                                            dp(
                                                activity,
                                                ((level.coerceAtLeast(0f) / 10).coerceIn(.1f, 1f) *
                                                        (1 - kotlin.math.abs(i - 4) * .12f) *
                                                        45)
                                                    .toInt()
                                                    .coerceAtLeast(4),
                                            )
                                    }
                            }
                        },
                        onPartial = { voicePreview.text = it },
                        onFinishing = {
                            voiceState = "transcribing"
                            voiceLabel.text = "One moment…"
                            updateAction()
                        },
                        onFinal = { finishRecognizedVoice(it) },
                        onError = { problem ->
                            cancelVoice()
                            error.text = problem
                            error.visibility = View.VISIBLE
                        },
                    )
                if (began) {
                    voiceState = "preparing"
                    voiceLabel.text = "Getting ready…"
                    updateAction()
                    return@whenQuiet
                }
            }
            val started =
                recorder.startRecording(
                    onRmsChanged = { db ->
                        handler.post {
                            if (voiceEpoch == epoch && voiceState == "recording")
                                waves.forEachIndexed { i, v ->
                                    v.layoutParams =
                                        v.layoutParams.apply {
                                            height =
                                                dp(
                                                    activity,
                                                    (((db + 60) / 50).coerceIn(.08f, 1f) *
                                                            (1 - kotlin.math.abs(i - 4) * .12f) *
                                                            45)
                                                        .toInt()
                                                        .coerceAtLeast(4),
                                                )
                                        }
                                }
                        }
                    },
                    onSilenceDetected = { handler.post { if (voiceEpoch == epoch) finishVoice() } },
                    onError = {
                        handler.post {
                            if (voiceEpoch != epoch) return@post
                            cancelVoice()
                            error.text = "Couldn’t use the microphone. You can type instead."
                            error.visibility = View.VISIBLE
                        }
                    },
                )
            if (!started) {
                cancelVoice()
                error.text = "The microphone is busy. You can type instead."
                error.visibility = View.VISIBLE
            }
        }
    }

    private fun showVoice() {
        voicePanel.visibility = View.VISIBLE
        scroll.visibility = View.GONE
        updateAction()
    }

    fun finishVoice() {
        if (voiceTurn.active) {
            voiceTurn.finish()
            return
        }
        if (voiceState != "recording") return
        voiceState = "transcribing"
        voiceLabel.text = "Finding your words…"
        updateAction()
        val active = AssistantClient.Request()
        transcription = active
        worker.execute {
            val wav = recorder.stopRecording()
            val result = runCatching {
                if (wav.size < 3200) "" else AssistantClient.transcribeWav(wav, active)
            }
            handler.post {
                if (destroyed || transcription !== active) return@post
                transcription = null
                result
                    .onSuccess { finishRecognizedVoice(it) }
                    .onFailure {
                        cancelVoice()
                        error.text = "Voice input couldn’t connect. Your typed draft is safe."
                        error.visibility = View.VISIBLE
                    }
            }
        }
    }

    private fun finishRecognizedVoice(value: String) {
        val wasInterrupt = interrupting
        val wasFollowUp = followUpListening || interrupting
        followUpListening = false
        interrupting = false
        monitoringInterruption = false
        voiceState = "idle"
        voicePanel.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        AudioGate.release("voice-input")
        val decision = ConversationPolicy.classify(value, "", false)
        if (decision.intent == ConversationPolicy.Intent.END) {
            endConversation(stopAnswer = true)
            return
        }
        if (
            wasInterrupt &&
                conversationActive &&
                decision.intent == ConversationPolicy.Intent.QUESTION &&
                ConversationPolicy.classify(decision.text, interruptedEcho, false).intent ==
                    ConversationPolicy.Intent.ECHO
        ) {
            // A clipped playback echo must never become the next submitted question.
            followUpPending = true
            handler.postDelayed(resumeListening, 300)
            updateAction()
            return
        }
        if (value.isBlank() || decision.intent == ConversationPolicy.Intent.INTERRUPT) {
            cancelVoice()
            error.text =
                if (wasFollowUp) "Conversation paused. Say “Hey Cue” whenever you need me."
                else "I didn’t catch that. Tap the microphone to try again."
            error.visibility = View.VISIBLE
        } else {
            insertDraft(decision.text)
            val preferences = activity.getSharedPreferences("mira", Context.MODE_PRIVATE)
            if (preferences.getBoolean("review_voice", false)) {
                error.text = "Your words are ready. Edit them or tap Send."
                error.visibility = View.VISIBLE
            } else {
                error.visibility = View.GONE
                waitingForAnswer = conversationActive
                monitorErrors = 0
                nextMonitorAt = 0L
                handler.removeCallbacks(conversationTimeout)
                if (conversationActive) handler.postDelayed(conversationTimeout, 180000)
                CueSession.send(decision.text, spokenRequest = true)
                scheduleConversation()
            }
        }
        updateAction()
    }

    fun cancelVoice() {
        voiceEpoch++
        conversationActive = false
        waitingForAnswer = false
        monitoringInterruption = false
        interrupting = false
        followUpListening = false
        followUpPending = false
        handler.removeCallbacks(reconcile)
        handler.removeCallbacks(resumeListening)
        handler.removeCallbacks(conversationTimeout)
        conversationBar.visibility = View.GONE
        voiceTurn.cancel()
        transcription?.cancel()
        transcription = null
        recorder.cancel()
        voiceState = "idle"
        voicePanel.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        AudioGate.release("voice-input")
        updateAction()
    }

    private fun updateConversationBar() {
        conversationBar.visibility = if (conversationActive) View.VISIBLE else View.GONE
        conversationLabel.text =
            when {
                voiceState != "idle" -> "Conversation on · Listening"
                followUpPending -> "Your turn next"
                monitorErrors >= 2 -> "Conversation on · Tap to interrupt"
                monitoringInterruption -> "Say “Hey Cue” to interrupt"
                else -> "Conversation on"
            }
        interruptButton.visibility =
            if (voiceState == "idle" && waitingForAnswer) View.VISIBLE else View.GONE
    }

    private fun scheduleConversation(delay: Long = 0) {
        if (!conversationActive || destroyed) return
        handler.removeCallbacks(reconcile)
        handler.postDelayed(reconcile, delay)
    }

    private fun reconcileConversation() {
        if (!conversationActive || destroyed) return
        if (CueSession.current.id != conversationThread) {
            cancelVoice()
            return
        }
        updateConversationBar()
        if (voiceState != "idle" || !waitingForAnswer) return
        val busy = CueSession.generating || CueSession.starting || SpeechOutput.speaking
        if (busy) {
            if (
                !CueSession.starting &&
                    !voiceTurn.active &&
                    !monitoringInterruption &&
                    monitorErrors < 2
            ) {
                val wait = nextMonitorAt - SystemClock.elapsedRealtime()
                if (wait > 0) scheduleConversation(wait) else monitorInterruption()
            }
        } else {
            // All queued audio must finish before starting the follow-up microphone window.
            waitingForAnswer = false
            voiceTurn.cancel()
            monitoringInterruption = false
            AudioGate.release("voice-input")
            if (CueSession.notice != null) {
                cancelVoice()
                return
            }
            followUpPending = true
            handler.removeCallbacks(resumeListening)
            handler.postDelayed(resumeListening, 650)
            updateConversationBar()
        }
    }

    private fun interruptionDecision(value: String): ConversationPolicy.Decision =
        ConversationPolicy.classify(
            value,
            if (SpeechOutput.speaking) SpeechOutput.echoText else "",
            true,
            VoiceReplyPreferences.store(activity).getBoolean("open_interruption", false),
        )

    private fun monitorInterruption() {
        if (!voiceTurn.supported) {
            monitorErrors = 2
            updateConversationBar()
            return
        }
        monitoringInterruption = true
        AudioGate.hold("voice-input")
        val began =
            voiceTurn.start(
                forInterruption = true,
                onLevel = {},
                onPartial = { value ->
                    if (interrupting)
                        voicePreview.text = ConversationPolicy.classify(value, "", false).text
                    else {
                        val decision = interruptionDecision(value)
                        if (
                            decision.intent == ConversationPolicy.Intent.ECHO &&
                                WakeCandidate.matches(value)
                        )
                            voiceTurn.rejectWake()
                        else acceptInterruption(decision)
                    }
                },
                onFinishing = {
                    if (interrupting) {
                        voiceState = "transcribing"
                        voiceLabel.text = "One moment…"
                        updateAction()
                    }
                },
                onFinal = { value ->
                    monitoringInterruption = false
                    if (!interrupting) acceptInterruption(interruptionDecision(value))
                    if (interrupting) {
                        val decision = ConversationPolicy.classify(value, "", false)
                        if (
                            decision.intent == ConversationPolicy.Intent.INTERRUPT ||
                                value.isBlank()
                        ) {
                            voiceState = "idle"
                            interrupting = false
                            followUpPending = true
                            handler.postDelayed(resumeListening, 250)
                        } else finishRecognizedVoice(value)
                    } else if (conversationActive) {
                        AudioGate.release("voice-input")
                        nextMonitorAt = SystemClock.elapsedRealtime() + 350
                        scheduleConversation(350)
                    }
                    updateConversationBar()
                },
                onError = {
                    monitoringInterruption = false
                    AudioGate.release("voice-input")
                    if (interrupting) {
                        cancelVoice()
                        error.text =
                            "Couldn’t hear that interruption. Tap the microphone to try again."
                        error.visibility = View.VISIBLE
                    } else {
                        monitorErrors++
                        nextMonitorAt = SystemClock.elapsedRealtime() + 1000
                        scheduleConversation(1000)
                        updateConversationBar()
                    }
                },
            )
        if (!began) {
            monitoringInterruption = false
            AudioGate.release("voice-input")
            monitorErrors = 2
        }
        updateConversationBar()
    }

    private fun acceptInterruption(decision: ConversationPolicy.Decision) {
        if (!conversationActive || decision.intent == ConversationPolicy.Intent.ECHO) return
        if (decision.intent == ConversationPolicy.Intent.END) {
            endConversation(stopAnswer = true)
            return
        }
        interrupting = true
        interruptedEcho = SpeechOutput.echoText
        voiceTurn.continueAsQuestion()
        waitingForAnswer = false
        voiceState = "recording"
        // Keep this recognizer alive: restarting would lose the start of the user's question.
        CueSession.stop()
        voiceLabel.text = "I’m listening."
        voicePreview.text = decision.text
        showVoice()
        updateConversationBar()
        CueDiagnostics.event(activity, "conversation_interruption")
    }

    private fun interruptAndListen() {
        if (!conversationActive) return
        voiceTurn.cancel()
        monitoringInterruption = false
        waitingForAnswer = false
        CueSession.stop()
        startVoice(followUp = true)
    }

    private fun endConversation(stopAnswer: Boolean) {
        cancelVoice()
        if (stopAnswer) CueSession.stop()
    }

    fun dispose() {
        cancelVoice()
        destroyed = true
        voiceTurn.cancel()
        CueSession.remove(observed)
        SpeechOutput.remove(observed)
        transcription?.cancel()
        recorder.cancel()
        AudioGate.release("voice-input")
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }
}
