package ai.snapdragon.edge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

// One session owns generation across both activities. Views only observe snapshots.
data class CueMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    var content: String,
    var state: String = "complete",
    val imagePath: String? = null,
)

data class CueThread(
    val id: String,
    var title: String,
    var updated: Long,
    var draft: String = "",
    val messages: MutableList<CueMessage> = mutableListOf(),
)

object CueSession {
    private lateinit var app: Context
    private val handler = Handler(Looper.getMainLooper())
    private val generation = Executors.newSingleThreadExecutor()
    private val control = Executors.newSingleThreadExecutor()
    private val persistence = Executors.newSingleThreadExecutor()
    @Volatile private var persistenceVersion = 0L
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val threads = mutableListOf<CueThread>()
    private var loaded = false
    lateinit var current: CueThread
        private set

    var generating = false
        private set

    var starting = false
        private set

    var stopping = false
        private set

    var ready = false
        private set

    var notice: String? = null
        private set

    var attachment: String? = null
        private set

    var request: AssistantClient.Request? = null
        private set

    private var generationId = 0
    private var controlId = 0
    private var healthPending = false
    private var lastPaint = 0L
    private var lastCheckpoint = 0L
    private var paintPending = false
    private val paint = Runnable {
        paintPending = false
        lastPaint = SystemClock.elapsedRealtime()
        notifyViews()
    }
    private val saveDraft = Runnable { persist() }

    fun init(context: Context) {
        if (loaded) return
        app = context.applicationContext
        val prefs = app.getSharedPreferences("mira", Context.MODE_PRIVATE)
        runCatching {
            val array = JSONArray(prefs.getString("threads", "[]"))
            for (i in 0 until array.length()) {
                val t = array.getJSONObject(i)
                val thread =
                    CueThread(
                        t.getString("id"),
                        t.getString("title"),
                        t.optLong("updated"),
                        t.optString("draft"),
                    )
                val messages = t.getJSONArray("messages")
                for (j in 0 until messages.length()) {
                    val m = messages.getJSONObject(j)
                    thread.messages.add(
                        CueMessage(
                            m.optString("id", UUID.randomUUID().toString()),
                            m.getString("role"),
                            m.getString("content"),
                            m.optString("state", "complete").let {
                                if (it == "streaming") "interrupted" else it
                            },
                            m.opt("image") as? String,
                        )
                    )
                }
                threads.add(thread)
            }
        }
        if (threads.isEmpty()) {
            val imported =
                CueThread(
                    UUID.randomUUID().toString(),
                    "Your conversation",
                    System.currentTimeMillis(),
                )
            val legacy =
                listOf("lfm_prefs", "muse")
                    .map { app.getSharedPreferences(it, Context.MODE_PRIVATE) }
                    .firstOrNull { it.contains("conversation") }
            runCatching {
                val array = JSONArray(legacy?.getString("conversation", "[]") ?: "[]")
                for (i in 0 until array.length()) {
                    val m = array.getJSONObject(i)
                    imported.messages.add(
                        CueMessage(role = m.getString("role"), content = m.getString("content"))
                    )
                }
                imported.draft = legacy?.getString("draft", "").orEmpty()
            }
            threads.add(imported)
        }
        current = threads.find { it.id == prefs.getString("current", null) } ?: threads.first()
        loaded = true
        persist()
    }

    fun observe(listener: () -> Unit) {
        listeners.add(listener)
        listener()
    }

    fun remove(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyViews() {
        listeners.forEach { it() }
    }

    fun allThreads() = threads.sortedByDescending { it.updated }.toList()

    fun draft(value: String) {
        if (!loaded) return
        current.draft = value
        handler.removeCallbacks(saveDraft)
        handler.postDelayed(saveDraft, 250)
    }

    fun attach(path: String?) {
        attachment = path
        notifyViews()
    }

    fun select(id: String) {
        if (generating) stop()
        persist()
        current = threads.first { it.id == id }
        attachment = null
        notice = null
        persist()
        notifyViews()
    }

    fun fresh() {
        if (generating) stop()
        val thread =
            CueThread(UUID.randomUUID().toString(), "A new thought", System.currentTimeMillis())
        threads.add(0, thread)
        current = thread
        notice = null
        attachment = null
        persist()
        notifyViews()
    }

    fun rename(id: String, title: String) {
        threads.find { it.id == id }?.title = title.trim().take(70).ifEmpty { "A new thought" }
        persist()
        notifyViews()
    }

    fun delete(id: String) {
        if (current.id == id && generating) stop()
        val removed = threads.find { it.id == id }
        removed?.messages?.mapNotNull { it.imagePath }?.forEach { java.io.File(it).delete() }
        threads.removeAll { it.id == id }
        if (threads.isEmpty()) {
            fresh()
            return
        }
        if (current.id == id) current = threads.first()
        persist()
        notifyViews()
    }

    fun clearAll() {
        stop()
        threads
            .flatMap { it.messages }
            .mapNotNull { it.imagePath }
            .forEach { java.io.File(it).delete() }
        threads.clear()
        java.io.File(app.filesDir, "attachments").deleteRecursively()
        fresh()
        // Delete both legacy stores so a future migration cannot resurrect deleted conversations.
        listOf("lfm_prefs", "muse").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    fun clearNotice() {
        notice = null
        notifyViews()
    }

    fun refresh() {
        if (healthPending || starting) return
        healthPending = true
        control.execute {
            val value = AssistantClient.isReady()
            handler.post {
                healthPending = false
                ready = value
                notifyViews()
            }
        }
    }

    fun setEngine(enabled: Boolean, afterReady: (() -> Unit)? = null) {
        if (starting) return
        if (!enabled && generating) stop()
        starting = true
        stopping = !enabled
        notice = null
        val task = ++controlId
        notifyViews()
        control.execute {
            val previousState = AssistantClient.isReady()
            val accepted =
                if (previousState == enabled) true
                else if (enabled) AssistantClient.startEngine(app)
                else AssistantClient.stopEngine(app)
            var actual = AssistantClient.isReady()
            val deadline = SystemClock.elapsedRealtime() + if (enabled) 25000 else 6000
            while (accepted && actual != enabled && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(400)
                actual = AssistantClient.isReady()
            }
            handler.post {
                if (task != controlId) return@post
                starting = false
                stopping = false
                ready = actual
                if (actual != enabled)
                    notice =
                        if (enabled)
                            "Cue couldn’t get ready. Open Settings to reconnect, then try again."
                        else "Cue is still running. Try pausing again."
                notifyViews()
                ai.snapdragon.edge.widget.EdgeAIWidgetProvider.updateAllWidgets(app)
                if (actual && enabled) afterReady?.invoke()
            }
        }
    }

    fun send(value: String = current.draft, spokenRequest: Boolean = false) {
        val prompt = value.trim().take(18000)
        if (generating || (prompt.isEmpty() && attachment == null)) return
        current.draft = prompt
        if (!ready) {
            val intendedThread = current.id
            setEngine(true) {
                if (current.id == intendedThread && current.draft == prompt)
                    send(prompt, spokenRequest)
            }
            return
        }
        val image = attachment
        current.draft = ""
        attachment = null
        notice = null
        if (current.messages.isEmpty())
            current.title = prompt.ifEmpty { "An image to explore" }.take(48)
        val user =
            CueMessage(
                role = "user",
                content = prompt.ifEmpty { "What can you tell me about this image?" },
                imagePath = image,
            )
        current.messages.add(user)
        val context =
            current.messages
                .filter { it.role == "user" || it.state in listOf("complete", "length", "stopped") }
                .map { it.copy() }
        val answer = CueMessage(role = "assistant", content = "", state = "streaming")
        current.messages.add(answer)
        current.updated = System.currentTimeMillis()
        val threadId = current.id
        val task = ++generationId
        val active = AssistantClient.Request()
        request = active
        generating = true
        val start = SystemClock.elapsedRealtime()
        lastCheckpoint = start
        lastPaint = start
        persist()
        notifyViews()
        generation.execute {
            var first = true
            val result = runCatching {
                AssistantClient.streamReply(context, active) { token ->
                    handler.post {
                        if (generationId != task || !generating) return@post
                        answer.content += token
                        if (first) {
                            first = false
                            android.util.Log.i(
                                "CueTiming",
                                "first_token_ms=${SystemClock.elapsedRealtime()-start}",
                            )
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastCheckpoint >= 1000) {
                            lastCheckpoint = now
                            persist()
                        }
                        if (!paintPending) {
                            paintPending = true
                            handler.postDelayed(paint, (33 - (now - lastPaint)).coerceAtLeast(0))
                        }
                    }
                }
            }
            handler.post {
                if (generationId != task) return@post
                generating = false
                request = null
                handler.removeCallbacks(paint)
                paintPending = false
                result
                    .onSuccess {
                        answer.content = it.text
                        answer.state = if (it.finishReason == "length") "length" else "complete"
                        if (spokenRequest && VoiceReplyPreferences.enabled(app))
                            SpeechOutput.read(app, answer.content)
                    }
                    .onFailure {
                        answer.state = if (active.cancelled.get()) "stopped" else "failed"
                        if (!active.cancelled.get())
                            notice =
                                "That answer was interrupted. Your question is saved—tap Retry."
                    }
                persist()
                notifyViews()
                android.util.Log.i(
                    "CueTiming",
                    "generation_ms=${SystemClock.elapsedRealtime()-start} state=${answer.state} thread=$threadId",
                )
            }
        }
    }

    fun stop() {
        if (!generating) return
        request?.cancel()
        request = null
        generationId++
        generating = false
        current.messages.lastOrNull { it.state == "streaming" }?.state = "stopped"
        handler.removeCallbacks(paint)
        paintPending = false
        persist()
        notifyViews()
    }

    fun retry() {
        if (generating) return
        val last = current.messages.lastOrNull() ?: return
        if (last.role == "assistant") current.messages.removeAt(current.messages.lastIndex)
        val user = current.messages.lastOrNull()
        if (user?.role == "user") {
            current.messages.removeAt(current.messages.lastIndex)
            attachment = user.imagePath
            send(user.content)
        }
    }

    fun persist() {
        if (!loaded) return
        val version = ++persistenceVersion
        val selectedId = current.id
        val snapshot =
            threads.take(50).map { thread ->
                thread.copy(
                    messages = thread.messages.takeLast(100).map { it.copy() }.toMutableList()
                )
            }
        persistence.execute {
            if (version != persistenceVersion) return@execute
            writeSnapshot(snapshot, selectedId, version)
        }
    }

    private fun writeSnapshot(snapshot: List<CueThread>, selectedId: String, version: Long) {
        val array = JSONArray()
        snapshot.forEach { thread ->
            val messages = JSONArray()
            thread.messages.takeLast(100).forEach { message ->
                messages.put(
                    JSONObject()
                        .put("id", message.id)
                        .put("role", message.role)
                        .put("content", message.content)
                        .put("state", message.state)
                        .put("image", message.imagePath ?: JSONObject.NULL)
                )
            }
            array.put(
                JSONObject()
                    .put("id", thread.id)
                    .put("title", thread.title)
                    .put("updated", thread.updated)
                    .put("draft", thread.draft)
                    .put("messages", messages)
            )
        }
        if (version != persistenceVersion) return
        app.getSharedPreferences("mira", Context.MODE_PRIVATE)
            .edit()
            .putString("threads", array.toString())
            .putString("current", selectedId)
            .apply()
    }
}
