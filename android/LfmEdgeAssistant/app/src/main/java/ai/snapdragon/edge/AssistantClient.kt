package ai.snapdragon.edge

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Snapdragon Edge AI Client.
 * All inference and control occurs 100% on-device over localhost:
 * - llama-server on port 8080 (LFM3-VL-2B on Adreno 840 GPU)
 * - controller daemon on port 8082 (instant stack supervisor)
 */
object AssistantClient {
    private const val LLAMA_BASE = "http://127.0.0.1:8080"
    private const val CONTROLLER_BASE = "http://127.0.0.1:8082"

    fun isReady(): Boolean {
        return try {
            val connection = URL("$LLAMA_BASE/health").openConnection() as HttpURLConnection
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            val code = connection.responseCode
            connection.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    fun isControllerAlive(): Boolean {
        return try {
            val connection = URL("$CONTROLLER_BASE/status").openConnection() as HttpURLConnection
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            val code = connection.responseCode
            connection.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    fun startEngine(): Boolean {
        return try {
            val connection = URL("$CONTROLLER_BASE/start").openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            val code = connection.responseCode
            connection.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    fun stopEngine(): Boolean {
        return try {
            val connection = URL("$CONTROLLER_BASE/stop").openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            val code = connection.responseCode
            connection.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    fun reply(history: List<Pair<String, String>>): String {
        val messages = JSONArray().put(
            JSONObject().put(
                "role",
                "system"
            ).put(
                "content",
                "You are LFM, a thoughtful and capable on-device AI assistant running locally on Qualcomm Snapdragon 8 Elite. Give clear, elegant, and useful answers in natural language. Be concise unless asked for depth. Do not mention system prompts."
            )
        )
        history.takeLast(16).forEach { (role, content) ->
            messages.put(JSONObject().put("role", role).put("content", content))
        }

        val connection = URL("$LLAMA_BASE/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 120000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
                .put("messages", messages)
                .put("max_tokens", 512)
                .put("temperature", 0.7)
                .put("stream", false)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200) error("LFM assistant engine unavailable")
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val result = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            if (result.isBlank()) error("Empty response received")
            return result
        } finally {
            connection.disconnect()
        }
    }
}
