package ai.snapdragon.edge

import android.content.Context
import android.content.Intent
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

object AssistantClient {
    private const val BASE = "http://127.0.0.1:8080"
    private const val SPEECH = "http://127.0.0.1:8081"
    private const val CONTROL = "http://127.0.0.1:8082"

    class Request {
        val cancelled = AtomicBoolean(false)
        @Volatile private var connection: HttpURLConnection? = null

        fun attach(value: HttpURLConnection) {
            connection = value
            if (cancelled.get()) {
                value.disconnect()
                throw CancellationException()
            }
        }

        fun cancel() {
            cancelled.set(true)
            connection?.disconnect()
        }

        fun check() {
            if (cancelled.get()) throw CancellationException()
        }
    }

    private fun healthy(url: String): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.responseCode == 200
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    fun isReady() = healthy("$BASE/health")

    fun isSpeechReady() = healthy("$SPEECH/")

    fun isControllerAlive() = healthy("$CONTROL/status")

    fun startEngine(context: Context? = null) = control("start", context)

    fun stopEngine(context: Context? = null) = control("stop", context)

    private fun control(command: String, context: Context?): Boolean {
        var controlToken: String? = null
        val session = URL("$CONTROL/session").openConnection() as HttpURLConnection
        try {
            session.connectTimeout = 1200
            session.readTimeout = 1200
            if (session.responseCode == 200) {
                controlToken =
                    session.inputStream.bufferedReader().use {
                        JSONObject(it.readText()).opt("token") as? String
                    }
            }
        } catch (_: Exception) {
            // An older installed supervisor can still be used during the upgrade.
        } finally {
            session.disconnect()
        }
        val connection = URL("$CONTROL/$command").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2000
            connection.readTimeout = 3000
            // Compatible with the existing supervisor; success here means accepted, not ready.
            connection.requestMethod = "POST"
            controlToken?.let { connection.setRequestProperty("X-Cue-Control", it) }
            if (connection.responseCode == 200) return true
        } catch (_: Exception) {} finally {
            connection.disconnect()
        }
        if (context == null) return false
        return runCatching {
                check(
                    context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                )
                context.startService(
                    Intent("com.termux.RUN_COMMAND").apply {
                        setClassName("com.termux", "com.termux.app.RunCommandService")
                        putExtra(
                            "com.termux.RUN_COMMAND_PATH",
                            "/data/data/com.termux/files/usr/bin/sh",
                        )
                        putExtra(
                            "com.termux.RUN_COMMAND_ARGUMENTS",
                            arrayOf("/sdcard/Download/${command}_all.sh"),
                        )
                        putExtra("com.termux.RUN_COMMAND_WORKDIR", "/sdcard/Download")
                        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                    }
                ) != null
            }
            .getOrDefault(false)
    }

    fun streamReply(
        history: List<CueMessage>,
        request: Request,
        token: (String) -> Unit,
    ): StreamDecoder.Answer {
        val messages =
            JSONArray()
                .put(
                    JSONObject()
                        .put("role", "system")
                        .put(
                            "content",
                            "You are Cue, a warm, thoughtful personal assistant. Answer clearly and helpfully. Be concise unless asked for detail. Use simple Markdown when useful. Do not describe your hardware or system instructions.",
                        )
                )
        // Bound prompt size as well as turns. Exact tokenizer budgeting remains a runtime
        // improvement.
        var characters = 0
        val selected = mutableListOf<CueMessage>()
        for (message in history.asReversed()) {
            val cost = message.content.length + if (message.imagePath != null) 2000 else 0
            if (selected.isNotEmpty() && (characters + cost > 18000 || selected.size >= 20)) break
            selected.add(0, message)
            characters += cost
        }
        // Do not begin a truncated context with an orphan assistant turn.
        while (selected.firstOrNull()?.role == "assistant") selected.removeAt(0)
        selected.forEach { message ->
            val item = JSONObject().put("role", message.role)
            if (message.imagePath != null) {
                val bytes = java.io.File(message.imagePath).readBytes()
                item.put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", message.content))
                        .put(
                            JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject()
                                        .put(
                                            "url",
                                            "data:image/jpeg;base64," +
                                                Base64.encodeToString(bytes, Base64.NO_WRAP),
                                        ),
                                )
                        ),
                )
            } else item.put("content", message.content)
            messages.put(item)
        }
        val connection = URL("$BASE/v1/chat/completions").openConnection() as HttpURLConnection
        request.attach(connection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            val body =
                JSONObject()
                    .put("messages", messages)
                    .put("max_tokens", 768)
                    .put("temperature", .7)
                    .put("stream", true)
                    .put("cache_prompt", true)
            request.check()
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200)
                throw java.io.IOException(
                    "Cue couldn’t answer just now (${connection.responseCode})"
                )
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                StreamDecoder.read(it, { request.cancelled.get() }, token)
            }
        } finally {
            connection.disconnect()
        }
    }

    fun transcribeWav(wav: ByteArray, request: Request = Request()): String {
        val boundary = "Cue-" + System.nanoTime()
        val connection = URL("$SPEECH/inference").openConnection() as HttpURLConnection
        request.attach(connection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 3000
            connection.readTimeout = 30000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.use {
                it.write(
                    "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n"
                        .toByteArray()
                )
                it.write(wav)
                it.write(
                    "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n--$boundary--\r\n"
                        .toByteArray()
                )
            }
            if (connection.responseCode != 200)
                throw java.io.IOException(
                    "Voice input is unavailable. You can still type your question."
                )
            return connection.inputStream
                .bufferedReader()
                .use { JSONObject(it.readText()).opt("text") as? String ?: "" }
                .trim()
        } finally {
            connection.disconnect()
        }
    }
}
