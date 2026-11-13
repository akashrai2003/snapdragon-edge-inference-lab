package ai.snapdragon.edge

import java.io.BufferedReader
import java.io.IOException
import org.json.JSONObject

/** SSE framing is independent of Android so protocol regressions can be tested on the JVM. */
object StreamDecoder {
    data class Answer(val text: String, val finishReason: String)

    fun read(
        reader: BufferedReader,
        cancelled: () -> Boolean = { false },
        token: (String) -> Unit,
    ): Answer {
        val text = StringBuilder()
        val event = StringBuilder()
        var terminal = false
        var reason = ""
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
            // Stream answer content immediately. Internal reasoning is a separate field,
            // never a fallback answer to display or feed into spoken playback.
            if (content is String && content.isNotEmpty()) {
                text.append(content)
                token(content)
            }
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
            }
        }
        if (cancelled()) throw java.util.concurrent.CancellationException()
        if (event.isNotEmpty()) dispatch()
        if (!terminal) throw IOException("The connection ended before the answer finished")
        if (text.isBlank()) throw IOException("The assistant returned an empty answer")
        return Answer(text.toString().trim(), reason.ifBlank { "stop" })
    }
}
