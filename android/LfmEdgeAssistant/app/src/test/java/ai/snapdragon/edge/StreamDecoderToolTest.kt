package ai.snapdragon.edge

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StreamDecoderToolTest {
    private fun frame(arguments: String, first: Boolean = false, index: Int = 0): String {
        val chunk =
            JSONObject()
                .put("index", index)
                .put(
                    "function",
                    JSONObject().put("arguments", arguments).apply {
                        if (first) put("name", "web_search")
                    },
                )
        if (first) chunk.put("id", "call_$index").put("type", "function")
        return "data: " +
            JSONObject()
                .put(
                    "choices",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put(
                                    "delta",
                                    JSONObject().put("tool_calls", JSONArray().put(chunk)),
                                )
                        ),
                )
    }

    private fun parse(vararg frames: String, tools: Boolean = true): StreamDecoder.Answer =
        StreamDecoder.read(
            frames.joinToString("\n\n", postfix = "\n\n").reader().buffered(),
            token = { error("Tool syntax was emitted as speech") },
            allowTools = tools,
        )

    private val finish = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"

    @Test
    fun assemblesNativeFragmentedArgumentsWithoutEmittingTokens() {
        val result = parse(frame("{\"query\":\"caf", true), frame("é\"}"), finish)
        assertEquals("", result.text)
        assertEquals("tool_calls", result.finishReason)
        assertEquals(
            StreamDecoder.ToolCall("call_0", "web_search", "{\"query\":\"café\"}"),
            result.toolCalls.single(),
        )
    }

    @Test
    fun assemblesInterleavedCallsByIndex() {
        val result =
            parse(
                frame("{\"query\":\"", true, 0),
                frame("{\"query\":\"two\"}", true, 1),
                frame("one\"}", index = 0),
                finish,
            )
        assertEquals(listOf("call_0", "call_1"), result.toolCalls.map { it.id })
    }

    @Test(expected = IOException::class)
    fun plainChatRejectsToolFrames() {
        parse(frame("{}", true), finish, tools = false)
    }

    @Test(expected = IOException::class)
    fun refusesTruncatedToolArguments() {
        parse(frame("{\"query\":", true), finish)
    }

    @Test(expected = IOException::class)
    fun requiresToolFinishReason() {
        parse(frame("{}", true), "data: [DONE]")
    }

    @Test(expected = IOException::class)
    fun refusesOversizedArguments() {
        parse(frame("x".repeat(4097), true), finish)
    }

    @Test(expected = IOException::class)
    fun refusesUnboundedToolIndex() {
        parse(frame("{}", true, 9), finish)
    }
}
