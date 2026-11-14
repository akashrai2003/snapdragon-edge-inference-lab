package ai.snapdragon.edge

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

data class WebHit(val title: String, val url: String, val snippet: String)

data class ReadPage(val title: String, val url: String, val text: String, val truncated: Boolean)

data class WebDocument(val url: String, val body: String, val truncated: Boolean)

interface WebAccess {
    fun search(query: String, request: AssistantClient.Request): List<WebHit>

    fun read(url: String, request: AssistantClient.Request, focus: String = ""): ReadPage
}

/** Phone-owned HTTPS, no API keys, account cookies, proxy service or hosted language model. */
class PhoneWeb : WebAccess {
    override fun search(query: String, request: AssistantClient.Request): List<WebHit> {
        require(query.trim().length in 2..256 && query.none { it.isISOControl() })
        request.check()
        val url =
            "https://html.duckduckgo.com/html/"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", query.trim())
                .build()
                .toString()
        val document = fetch(url, request)
        if (isChallenge(document.body))
            throw WebResearchException(
                "The search site needs a browser check. Use Open search below, or try again later."
            )
        val results = parseSearch(document.body)
        if (results.isEmpty())
            throw WebResearchException(
                "I couldn’t find usable web results. Try a more specific search or open it in your browser."
            )
        return results.take(4)
    }

    override fun read(url: String, request: AssistantClient.Request, focus: String): ReadPage {
        val document = fetch(url, request)
        if (isChallenge(document.body))
            throw WebResearchException("That page needs a browser check.")
        val page = parsePage(document.body, document.url, focus)
        if (page.text.length < 100)
            throw WebResearchException("That page didn’t provide readable text.")
        return page.copy(truncated = page.truncated || document.truncated)
    }

    private fun fetch(value: String, request: AssistantClient.Request): WebDocument {
        var target =
            PublicWebUrl.canonical(value)
                ?: throw WebResearchException("That link isn’t a public HTTPS page.")
        repeat(5) { hop ->
            request.check()
            val call =
                client.newCall(
                    Request.Builder()
                        .url(target)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36 Cue/3.4",
                        )
                        .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9")
                        .build()
                )
            request.attachCancellation { call.cancel() }
            call.execute().use { response ->
                request.check()
                if (response.code in 300..399) {
                    if (hop == 4) throw WebResearchException("That page redirected too many times.")
                    val next =
                        response.header("Location")?.let { response.request.url.resolve(it) }
                            ?: throw WebResearchException(
                                "That page returned an incomplete redirect."
                            )
                    target =
                        PublicWebUrl.canonical(next.toString())
                            ?: throw WebResearchException(
                                "That redirect isn’t a public HTTPS page."
                            )
                } else {
                    if (response.code == 429 || response.code == 403)
                        throw WebResearchException(
                            "The site is limiting access. Try later or open it in your browser."
                        )
                    if (!response.isSuccessful)
                        throw WebResearchException("That website couldn’t be reached just now.")
                    val body = response.body ?: throw WebResearchException("That page was empty.")
                    val type = body.contentType()
                    if (type != null && type.subtype !in setOf("html", "xhtml+xml", "plain"))
                        throw WebResearchException("That link isn’t a readable web page.")
                    val bytes = ByteArrayOutputStream()
                    var truncated = false
                    body.byteStream().use { stream ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            request.check()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            val remaining = 768000 - bytes.size()
                            bytes.write(buffer, 0, minOf(count, remaining))
                            if (count > remaining) {
                                truncated = true
                                break
                            }
                        }
                    }
                    request.check()
                    return WebDocument(
                        target,
                        bytes.toString(type?.charset(Charsets.UTF_8)?.name() ?: "UTF-8"),
                        truncated,
                    )
                }
            }
        }
        throw WebResearchException("That page couldn’t be opened.")
    }

    companion object {
        private val client by lazy {
            OkHttpClient.Builder()
                .dns(
                    object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            val addresses = Dns.SYSTEM.lookup(hostname)
                            if (
                                addresses.isEmpty() ||
                                    addresses.any { !PublicWebUrl.isPublicAddress(it) }
                            )
                                throw UnknownHostException("Public web destinations only")
                            return addresses
                        }
                    }
                )
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(18, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }

        fun isChallenge(html: String): Boolean {
            val lower = html.lowercase()
            return "anomaly.js" in lower ||
                "challenge-form" in lower ||
                "unfortunately, bots use duckduckgo" in lower ||
                (html.length < 30000 &&
                    ("verify you are human" in lower || "checking your browser" in lower))
        }

        fun parseSearch(html: String): List<WebHit> {
            if (isChallenge(html)) return emptyList()
            val doc = Jsoup.parse(html, "https://html.duckduckgo.com/")
            val results = linkedMapOf<String, WebHit>()
            for (anchor in doc.select("a.result__a, a.result-link")) {
                val href = anchor.absUrl("href")
                val unwrapped =
                    runCatching {
                            val url = href.toHttpUrl()
                            if (
                                url.host == "duckduckgo.com" || url.host.endsWith(".duckduckgo.com")
                            )
                                url.queryParameter("uddg") ?: ""
                            else href
                        }
                        .getOrDefault("")
                val url = PublicWebUrl.canonical(unwrapped) ?: continue
                val title = anchor.text().trim().take(180)
                if (title.isBlank()) continue
                val box = anchor.parents().firstOrNull { it.hasClass("result") }
                val snippet = box?.selectFirst(".result__snippet")?.text().orEmpty().take(240)
                results.putIfAbsent(url, WebHit(title, url, snippet))
                if (results.size >= 4) break
            }
            return results.values.toList()
        }

        fun parsePage(html: String, url: String, focus: String = ""): ReadPage {
            val doc = Jsoup.parse(html, url)
            doc.select("script,style,noscript,nav,header,footer,aside,form,svg,canvas,template")
                .remove()
            val main =
                doc.select("article,main,[role=main]").maxByOrNull { it.text().length }
                    ?: doc.body()
            val text = main.text().replace(Regex("\\s+"), " ").trim()
            return ReadPage(doc.title().take(180), url, passages(text, focus), text.length > 1800)
        }

        /**
         * Select evidence near question terms, rather than losing relevant sections at page end.
         */
        private fun passages(text: String, focus: String): String {
            if (text.length <= 1800) return text
            val ignored =
                setOf(
                    "search",
                    "please",
                    "about",
                    "explain",
                    "official",
                    "https",
                    "www",
                    "what",
                    "does",
                    "with",
                    "from",
                    "that",
                    "this",
                    "have",
                    "their",
                    "which",
                )
            val terms =
                Regex("[\\p{L}\\p{N}_]{4,}")
                    .findAll(focus.lowercase())
                    .map { it.value }
                    .filter { it !in ignored }
                    .toSet()
            if (terms.isEmpty()) return text.take(1800)
            val windows =
                (0 until text.length step 650).map { start ->
                    val end = minOf(text.length, start + 895)
                    val fragment = text.substring(start, end)
                    val lower = fragment.lowercase()
                    Triple(start, fragment, terms.count { it in lower })
                }
            val ranked = windows.filter { it.third > 0 }.sortedByDescending { it.third }
            if (ranked.isEmpty()) return text.take(1800)
            val selected = mutableListOf(ranked.first())
            ranked
                .drop(1)
                .firstOrNull { kotlin.math.abs(it.first - selected[0].first) >= 895 }
                ?.let { selected.add(it) }
            return selected.sortedBy { it.first }.joinToString(" … ") { it.second }.take(1800)
        }
    }
}
