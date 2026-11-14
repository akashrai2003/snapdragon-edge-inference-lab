package ai.snapdragon.edge

import java.io.BufferedReader
import java.io.IOException
import org.json.JSONObject

/** SSE framing is independent of Android so protocol regressions can be tested on the JVM. */
object StreamDecoder {
    data class ToolCall(val id: String, val name: String, val arguments: String)

    data class Answer(
        val text: String,
        val finishReason: String,
        val toolCalls: List<ToolCall> = emptyList(),
    )

    private class PendingCall {
        val id = StringBuilder()
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    fun read(
        reader: BufferedReader,
        cancelled: () -> Boolean = { false },
        token: (String) -> Unit,
        allowTools: Boolean = false,
    ): Answer {
        val text = StringBuilder()
        val event = StringBuilder()
        var terminal = false
        var reason = ""
        val calls = sortedMapOf<Int, PendingCall>()
        fun dispatch() {
            val value = event.toString().trim()
            event.setLength(0)
            if (value.isEmpty()) return
            if (value == "[DONE]") {
                terminal = true
                return
            }
            val json =
                try {
                    JSONObject(value)
                } catch (e: Exception) {
                    throw IOException("Invalid response event", e)
                }
            if (json.has("error")) throw IOException("The assistant could not complete this answer")
            val choices = json.optJSONArray("choices") ?: return
            if (choices.length() == 0) return
            val choice = choices.getJSONObject(0)
            val delta = choice.optJSONObject("delta")
            val content = delta?.opt("content")
            delta?.optJSONArray("tool_calls")?.let { chunks ->
                if (!allowTools) throw IOException("Unexpected tool call")
                for (i in 0 until chunks.length()) {
                    val chunk = chunks.getJSONObject(i)
                    val index = chunk.optInt("index", -1)
                    if (index !in 0..3) throw IOException("Too many tool calls")
                    val call = calls.getOrPut(index) { PendingCall() }
                    fun appendField(
                        target: StringBuilder,
                        value: Any?,
                        limit: Int,
                        repeated: Boolean = false,
                    ) {
                        if (value is String && (!repeated || target.toString() != value))
                            target.append(value)
                        if (target.length > limit) throw IOException("Tool call exceeds its limit")
                    }
                    appendField(call.id, chunk.opt("id"), 128, true)
                    chunk.optJSONObject("function")?.let { function ->
                        appendField(call.name, function.opt("name"), 64, true)
                        appendField(call.arguments, function.opt("arguments"), 4096)
                    }
                }
            }
            // Stream answer content immediately. Internal reasoning is a separate field,
            // never a fallback answer to display or feed into spoken playback.
            if (content is String && content.isNotEmpty()) {
                if (calls.isNotEmpty()) throw IOException("Mixed answer and tool call")
                text.append(content)
                if (text.length > 32000) throw IOException("Answer exceeds its limit")
                token(content)
            }
            if (calls.isNotEmpty() && text.isNotBlank())
                throw IOException("Mixed answer and tool call")
            val finish = choice.opt("finish_reason")
            if (finish is String && finish.isNotBlank()) {
                reason = finish
                terminal = true
            }
        }
        while (!cancelled()) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                dispatch()
                if (terminal) break
            } else if (line.startsWith("data:")) {
                if (event.isNotEmpty()) event.append('\n')
                event.append(line.substring(5).trimStart())
                if (event.length > 65536) throw IOException("Response event exceeds its limit")
            }
        }
        if (cancelled()) throw java.util.concurrent.CancellationException()
        if (event.isNotEmpty()) dispatch()
        if (!terminal) throw IOException("The connection ended before the answer finished")
        if (calls.isNotEmpty()) {
            if (reason != "tool_calls") throw IOException("Incomplete tool call")
            val finished =
                calls.values.map {
                    if (
                        !Regex("[A-Za-z0-9_-]{1,128}").matches(it.id) ||
                            !Regex("[A-Za-z0-9_]{1,64}").matches(it.name)
                    )
                        throw IOException("Invalid tool identity")
                    try {
                        JSONObject(it.arguments.toString())
                    } catch (e: Exception) {
                        throw IOException("Invalid tool arguments", e)
                    }
                    ToolCall(it.id.toString(), it.name.toString(), it.arguments.toString())
                }
            if (finished.map { it.id }.distinct().size != finished.size)
                throw IOException("Duplicate tool call ID")
            return Answer("", reason, finished)
        }
        if (reason == "tool_calls" || text.isBlank())
            throw IOException("The assistant returned an empty answer")
        return Answer(text.toString().trim(), reason.ifBlank { "stop" })
    }
}
