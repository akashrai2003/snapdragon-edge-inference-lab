package ai.snapdragon.edge

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WebEvidenceTest {
    @Test
    fun preservesEncodedParametersAndRemovesFragment() {
        assertEquals(
            "https://example.com/a%2Fb?q=a%26b%3Dc",
            PublicWebUrl.canonical("https://EXAMPLE.com/a%2Fb?q=a%26b%3Dc#section"),
        )
        assertEquals("https://example.com/", PublicWebUrl.canonical("https://example.com:443"))
    }

    @Test
    fun blocksPrivateAndNonWebDestinations() {
        listOf(
                "http://example.com",
                "file:///etc/hosts",
                "https://user:secret@example.com/",
                "https://localhost./",
                "https://127.0.0.1/",
                "https://127.1/",
                "https://169.254.169.254/",
                "https://10.1.2.3/",
                "https://100.64.1.2/",
                "https://[::1]/",
                "https://[fc00::1]/",
                "https://example.com:8080/",
                "https://phone.local/",
            )
            .forEach { assertNull(it, PublicWebUrl.canonical(it)) }
        assertTrue(PublicWebUrl.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(PublicWebUrl.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
        assertFalse(PublicWebUrl.isPublicAddress(InetAddress.getByName("192.0.2.1")))
        assertFalse(PublicWebUrl.isPublicAddress(InetAddress.getByName("2001:db8::1")))
    }

    @Test
    fun webScopeIsExplicitAndDoesNotMatchPrivateSearches() {
        assertTrue(WebRequest.explicit("Please search the web for astronomy news."))
        assertTrue(WebRequest.explicit("Can you look it up online?"))
        assertFalse(WebRequest.explicit("Search my photos for the beach."))
        assertFalse(WebRequest.explicit("Explain how search engines work."))
        assertFalse(WebRequest.explicit("Summarize my private email."))
    }

    @Test
    fun parsesRealisticSearchRedirectsAndDropsDuplicatesAndLocalLinks() {
        val html =
            """<div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fguide%3Fx%3Da%2526b">A useful guide</a><div class="result__snippet">Actual &amp; useful text.</div></div>
            <div class="result"><a class="result__a" href="https://example.com/guide?x=a%26b">Duplicate</a></div>
            <a class="result__a" href="https://127.0.0.1/private">Private</a>
            <a class="result__a" href="https://duckduckgo.com/y.js?ad=1">Advertisement</a>"""
        val results = PhoneWeb.parseSearch(html)
        assertEquals(1, results.size)
        assertEquals("https://example.com/guide?x=a%26b", results.single().url)
        assertEquals("Actual & useful text.", results.single().snippet)
    }

    @Test
    fun neverTreatsChallengePageAsResults() {
        assertTrue(PhoneWeb.isChallenge("<form id='challenge-form'>Verify</form>"))
        assertEquals(
            emptyList<WebHit>(),
            PhoneWeb.parseSearch(
                "<form id='challenge-form'><a class='result__a' href='https://example.com/'>Fake</a></form>"
            ),
        )
    }

    @Test
    fun pageExtractionIsBoundedAndExcludesNavigationAndScripts() {
        val text = "Reliable public evidence. ".repeat(100)
        val page =
            PhoneWeb.parsePage(
                "<title>Evidence</title><nav>Unrelated menus</nav><script>secret script</script><main><h1>Heading</h1><p>$text</p></main>",
                "https://example.com/",
            )
        assertEquals("Evidence", page.title)
        assertTrue(page.text.startsWith("Heading Reliable"))
        assertFalse(page.text.contains("Unrelated"))
        assertFalse(page.text.contains("secret"))
        assertEquals(1800, page.text.length)
        assertTrue(page.truncated)
    }

    @Test
    fun sourcePersistenceDoesNotIncludeFetchedTextOrSearchSnippet() {
        val source = WebSource(1, "Title", "https://example.com/", "temporary snippet", true)
        val json = source.toJson()
        assertFalse(json.has("snippet"))
        assertEquals(source.url, WebSource.fromJson(json)?.url)
        assertNull(WebSource.fromJson(json.put("url", "https://localhost/")))
    }

    @Test
    fun findsRelevantEvidenceBeyondTheOpeningOfLongPages() {
        val html =
            "<main><p>" +
                "Unrelated background material. ".repeat(200) +
                "</p><h2>Dial a phone number</h2><p>ACTION_DIAL opens the phone dialer with a number.</p></main>"
        val page =
            PhoneWeb.parsePage(
                html,
                "https://example.com/",
                "How does ACTION_DIAL open the dialer?",
            )
        assertTrue(page.text.contains("ACTION_DIAL opens the phone dialer"))
        assertTrue(page.text.length <= 1800)
        assertTrue(page.truncated)
    }
}
