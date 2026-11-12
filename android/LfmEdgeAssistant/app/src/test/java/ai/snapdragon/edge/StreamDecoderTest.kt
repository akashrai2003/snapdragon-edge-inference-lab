package ai.snapdragon.edge

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StreamDecoderTest {
    private fun parse(vararg events: String): Pair<StreamDecoder.Answer, List<String>> {
        val tokens = mutableListOf<String>()
        val answer =
            StreamDecoder.read(
                events.joinToString("\n\n", postfix = "\n\n").reader().buffered(),
                token = { tokens.add(it) },
            )
        return answer to tokens
    }

    @Test
    fun ignoresNullAndControlFrames() {
        val (answer, tokens) =
            parse(
                "data: {\"choices\":[{\"delta\":{\"content\":null,\"role\":\"assistant\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
                "data: [DONE]",
            )
        assertEquals("Hello", answer.text)
        assertEquals(listOf("Hello"), tokens)
    }

    @Test
    fun reasoningIsNeverShown() {
        val (answer, tokens) =
            parse(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hidden\",\"content\":null}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"Answer\"},\"finish_reason\":\"stop\"}]}",
            )
        assertEquals("Answer", answer.text)
        assertEquals(listOf("Answer"), tokens)
    }

    @Test
    fun preservesUnicodeAndTokenBoundaries() {
        val (answer, tokens) =
            parse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hi \"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"世界 ✨\"}}]}",
                "data: [DONE]",
            )
        assertEquals("Hi 世界 ✨", answer.text)
        assertEquals(2, tokens.size)
    }

    @Test
    fun reportsLengthLimit() {
        val (a, _) =
            parse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Partial\"},\"finish_reason\":\"length\"}]}"
            )
        assertEquals("length", a.finishReason)
    }

    @Test
    fun supportsMultilineEventsAndComments() {
        val (a, _) =
            parse(
                ": heartbeat\ndata: {\"choices\": [\ndata: {\"delta\": {\"content\": \"OK\"}, \"finish_reason\": \"stop\"}]}"
            )
        assertEquals("OK", a.text)
    }

    @Test(expected = IOException::class)
    fun rejectsTruncatedStream() {
        parse("data: {\"choices\":[{\"delta\":{\"content\":\"Partial\"}}]}")
    }

    @Test(expected = IOException::class)
    fun rejectsMalformedEvent() {
        parse("data: invalid", "data: [DONE]")
    }

    @Test(expected = IOException::class)
    fun rejectsErrorEvent() {
        parse("data: {\"error\":{\"message\":\"busy\"}}")
    }

    @Test(expected = IOException::class)
    fun rejectsReasoningOnlyAnswer() {
        parse(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hidden\"}}]}",
            "data: [DONE]",
        )
    }

    @Test(expected = CancellationException::class)
    fun cancellationWinsOverEmptyAnswer() {
        StreamDecoder.read("".reader().buffered(), { true }, {})
    }

    @Test(expected = IllegalStateException::class)
    fun doesNotSwallowCallbackFailures() {
        StreamDecoder.read(
            "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n".reader().buffered(),
            token = { error("callback failed") },
        )
    }
}
