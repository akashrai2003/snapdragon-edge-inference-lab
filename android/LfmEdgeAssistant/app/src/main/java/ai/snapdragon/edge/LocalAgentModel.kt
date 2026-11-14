package ai.snapdragon.edge

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

fun interface AgentModel {
    fun complete(
        messages: JSONArray,
        tools: JSONArray,
        request: AssistantClient.Request,
        allowTools: Boolean,
        token: (String) -> Unit,
    ): StreamDecoder.Answer
}

/** Uses only the existing phone endpoint. No remote-model fallback or provider credentials. */
class LocalAgentModel : AgentModel {
    private var contextSize: Int? = null

    override fun complete(
        messages: JSONArray,
        tools: JSONArray,
        request: AssistantClient.Request,
        allowTools: Boolean,
        token: (String) -> Unit,
    ): StreamDecoder.Answer {
        request.check()
        if (contextSize == null) {
            val props = json("/props", null, request)
            contextSize = props.getJSONObject("default_generation_settings").getInt("n_ctx")
        }
        val body =
            JSONObject()
                .put("messages", JSONArray(messages.toString()))
                .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                .put("temperature", 0.1)
                .put("stream", true)
                .put("cache_prompt", true)
        if (tools.length() > 0)
            body.put("tools", tools).put("tool_choice", if (allowTools) "auto" else "none")
        var available: Int
        var attempts = 0
        while (true) {
            val templateRequest = JSONObject(body.toString()).put("add_generation_prompt", true)
            val prompt = json("/apply-template", templateRequest, request).getString("prompt")
            if (prompt.length > 65536)
                throw WebResearchException("Try a shorter or more focused search.")
            val count =
                json(
                        "/tokenize",
                        JSONObject().put("content", prompt).put("add_special", true),
                        request,
                    )
                    .getJSONArray("tokens")
                    .length()
            available = contextSize!! - count - 96
            if (
                available >= 640 ||
                    attempts++ >= 6 ||
                    !WebPromptBudget.shrink(body.getJSONArray("messages"))
            )
                break
        }
        if (available < 128)
            throw WebResearchException("That search grew too large. Try a more focused question.")
        body.put("max_tokens", minOf(640, available))
        val connection = open("/v1/chat/completions", body, request)
        try {
            if (connection.responseCode != 200)
                throw WebResearchException("Cue couldn’t use its web tools just now. Try again.")
            return connection.inputStream.bufferedReader().use {
                StreamDecoder.read(it, { request.cancelled.get() }, token, allowTools = allowTools)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(
        path: String,
        body: JSONObject?,
        request: AssistantClient.Request,
    ): HttpURLConnection {
        request.check()
        val connection = URL("http://127.0.0.1:8080$path").openConnection() as HttpURLConnection
        request.attach(connection)
        connection.connectTimeout = 3000
        connection.readTimeout = 45000
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            try {
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        return connection
    }

    private fun json(
        path: String,
        body: JSONObject?,
        request: AssistantClient.Request,
    ): JSONObject {
        val connection = open(path, body, request)
        try {
            if (connection.responseCode != 200)
                throw WebResearchException("Cue’s web tools aren’t available with this engine yet.")
            val text =
                connection.inputStream.bufferedReader().use { reader ->
                    val out = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        request.check()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        out.append(buffer, 0, count)
                        if (out.length > 524288)
                            throw WebResearchException("Cue couldn’t prepare that search.")
                    }
                    out.toString()
                }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
