package ai.snapdragon.edge

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebResearchAgentTest {
    private open class FakeWeb : WebAccess {
        var searches = 0
        val reads = AtomicInteger()

        override fun search(query: String, request: AssistantClient.Request): List<WebHit> {
            searches++
            return (1..4).map {
                WebHit("Guide $it", "https://example.com/guide$it", "Search snippet $it.")
            }
        }

        override fun read(url: String, request: AssistantClient.Request, focus: String): ReadPage {
            reads.incrementAndGet()
            return ReadPage("Official guide", url, "Actual page evidence for ${url.last()}.", false)
        }
    }

    private fun call(id: String, name: String, args: JSONObject) =
        StreamDecoder.Answer(
            "",
            "tool_calls",
            listOf(StreamDecoder.ToolCall(id, name, args.toString())),
        )

    @Test
    fun phoneModelReceivesAllFourPagesTogetherBeforeAnswering() {
        val web = FakeWeb()
        var turn = 0
        var schemas = ""
        val tokens = mutableListOf<String>()
        val model = AgentModel { messages, tools, _, allowed, emit ->
            if (turn++ == 0) {
                assertEquals(2, messages.length())
                assertTrue(allowed)
                schemas = tools.toString()
                call("search", "web_search", JSONObject().put("query", "public guide"))
            } else {
                assertFalse(allowed)
                assertEquals(schemas, tools.toString())
                val evidence =
                    JSONObject(messages.getJSONObject(3).getString("content"))
                        .getJSONArray("results")
                assertEquals(4, evidence.length())
                for (i in 0..3) assertTrue(
                    evidence.getJSONObject(i).getString("text").contains("evidence for ${i + 1}")
                )
                emit("A combined answer [1] [4].")
                StreamDecoder.Answer("A combined answer [1] [4].", "stop")
            }
        }
        val result =
            WebResearchAgent(model, web)
                .run(
                    "Search the web for a public guide",
                    AssistantClient.Request(),
                    token = { tokens.add(it) },
                )
        assertEquals(2, turn)
        assertEquals(1, result.calls)
        assertEquals(4, web.reads.get())
        assertTrue(result.sources.all { it.pageRead })
        assertEquals(listOf("A combined answer [1] [4]."), tokens)
    }

    @Test
    fun failedPageRetainsItsSnippetAndAnExplicitReadError() {
        val web =
            object : FakeWeb() {
                override fun read(
                    url: String,
                    request: AssistantClient.Request,
                    focus: String,
                ): ReadPage {
                    if (url.endsWith("2"))
                        throw WebResearchException("That page needs a browser check.")
                    return super.read(url, request, focus)
                }
            }
        var turn = 0
        val model = AgentModel { messages, _, _, _, _ ->
            if (turn++ == 0) call("search", "web_search", JSONObject().put("query", "guide"))
            else {
                val row =
                    JSONObject(messages.getJSONObject(3).getString("content"))
                        .getJSONArray("results")
                        .getJSONObject(1)
                assertFalse(row.getBoolean("page_read"))
                assertEquals("Search snippet 2.", row.getString("snippet"))
                assertTrue(row.has("read_error"))
                assertFalse(row.has("text"))
                StreamDecoder.Answer("Three pages were readable [1].", "stop")
            }
        }
        val result =
            WebResearchAgent(model, web)
                .run("Search the web for a guide", AssistantClient.Request(), token = {})
        assertEquals(3, result.sources.count { it.pageRead })
        assertTrue(result.sources[1].readError!!.contains("browser check"))
    }

    @Test
    fun directUserUrlsAreReadTogetherWithoutSearch() {
        val web = FakeWeb()
        var turn = 0
        val model = AgentModel { _, _, _, allowed, _ ->
            if (turn++ == 0)
                call("read", "web_read", JSONObject().put("url", "https://example.com/one"))
            else {
                assertFalse(allowed)
                StreamDecoder.Answer("The pages agree [1] [2].", "stop")
            }
        }
        val result =
            WebResearchAgent(model, web)
                .run(
                    "Compare https://example.com/one and https://example.com/two",
                    AssistantClient.Request(),
                    token = {},
                )
        assertEquals(0, web.searches)
        assertEquals(2, web.reads.get())
        assertEquals(2, result.sources.size)
    }

    @Test
    fun cancellationBetweenModelAndToolPreventsExecution() {
        val web = FakeWeb()
        val model = AgentModel { _, _, request, _, _ ->
            request.cancel()
            call("cancelled", "web_search", JSONObject().put("query", "guide"))
        }
        assertThrows(CancellationException::class.java) {
            WebResearchAgent(model, web)
                .run("Search the web for a guide", AssistantClient.Request(), token = {})
        }
        assertEquals(0, web.searches)
        assertEquals(0, web.reads.get())
    }

    @Test
    fun inventedUrlsAndUnknownToolsNeverReachTheNetwork() {
        for (tool in listOf("web_read", "run_shell")) {
            val web = FakeWeb()
            var turn = 0
            val model = AgentModel { messages, _, _, _, _ ->
                if (turn++ == 0)
                    call("bad", tool, JSONObject().put("url", "https://example.com/invented"))
                else {
                    assertTrue(
                        messages
                            .getJSONObject(messages.length() - 1)
                            .getString("content")
                            .contains("error")
                    )
                    StreamDecoder.Answer("Unavailable", "stop")
                }
            }
            assertThrows(WebResearchException::class.java) {
                WebResearchAgent(model, web)
                    .run("Search the web for a guide", AssistantClient.Request(), token = {})
            }
            assertEquals(0, web.searches)
            assertEquals(0, web.reads.get())
        }
    }

    @Test
    fun noEvidenceNeverBecomesAReportedSuccessfulWebAnswer() {
        var emitted = false
        val model = AgentModel { _, _, _, _, emit ->
            emit("Invented answer")
            StreamDecoder.Answer("Invented answer", "stop")
        }
        assertThrows(WebResearchException::class.java) {
            WebResearchAgent(model, FakeWeb())
                .run(
                    "Search the web for a guide",
                    AssistantClient.Request(),
                    token = { emitted = true },
                )
        }
        assertFalse(emitted)
    }

    @Test
    fun browserChallengeKeepsItsActionableFailureMessage() {
        val web =
            object : FakeWeb() {
                override fun search(query: String, request: AssistantClient.Request): List<WebHit> {
                    throw WebResearchException(
                        "The search site needs a browser check. Use Open search below."
                    )
                }
            }
        var turn = 0
        val model = AgentModel { _, _, _, _, _ ->
            if (turn++ == 0) call("blocked", "web_search", JSONObject().put("query", "guide"))
            else StreamDecoder.Answer("No evidence", "stop")
        }
        val problem =
            assertThrows(WebResearchException::class.java) {
                WebResearchAgent(model, web)
                    .run("Search the web for a guide", AssistantClient.Request(), token = {})
            }
        assertTrue(problem.userMessage.contains("browser check"))
    }

    @Test
    fun sourceInventoryFallbackNeverInventsClaimLevelCitations() {
        val sources =
            listOf(
                WebSource(1, "Snippet", "https://example.com/"),
                WebSource(2, "Page", "https://example.com/page", pageRead = true),
            )
        val fixed =
            WebResearchAgent.withProvenance(
                StreamDecoder.Answer("A short answer [99].", "stop"),
                sources,
            )
        assertFalse(fixed.text.contains("[99]"))
        assertTrue(fixed.text.endsWith("Pages reviewed: [2]."))
        assertFalse(fixed.text.contains("[1]"))
        val cited = StreamDecoder.Answer("A short answer [2].", "stop")
        assertEquals(cited, WebResearchAgent.withProvenance(cited, sources))
    }
}
