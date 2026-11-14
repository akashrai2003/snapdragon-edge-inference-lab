package ai.snapdragon.edge

import java.net.InetAddress
import java.net.URI
import org.json.JSONObject

data class WebSource(
    val id: Int,
    val title: String,
    val url: String,
    val snippet: String = "",
    val pageRead: Boolean = false,
    val retrievedAt: Long = System.currentTimeMillis(),
    val readError: String? = null,
) {
    fun toJson(includeSnippet: Boolean = false): JSONObject =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("url", url)
            .put("page_read", pageRead)
            .put("retrieved_at", retrievedAt)
            .apply {
                if (includeSnippet) put("snippet", snippet)
                readError?.let { put("read_error", it.take(180)) }
            }

    companion object {
        fun fromJson(value: JSONObject): WebSource? {
            val url = value.optString("url")
            if (PublicWebUrl.canonical(url) == null) return null
            val id = value.optInt("id")
            if (id !in 1..8) return null
            return WebSource(
                id,
                value.optString("title").take(180),
                url,
                pageRead = value.optBoolean("page_read"),
                retrievedAt = value.optLong("retrieved_at"),
                readError = (value.opt("read_error") as? String)?.take(180),
            )
        }
    }
}

object WebRequest {
    // Web access is scoped to this question. Ordinary chat and images stay out of search.
    fun explicit(value: String): Boolean =
        Regex(
                "(?i)\\b(?:search\\s+(?:the\\s+)?(?:web|internet)|search\\s+online|look\\s+(?:(?:this|that|it)\\s+)?up\\s+online|find\\b.{0,100}\\bonline)\\b"
            )
            .containsMatchIn(value)
}

object PublicWebUrl {
    fun canonical(value: String): String? =
        runCatching {
                val uri = URI(value.trim())
                require(uri.scheme.equals("https", true) && uri.rawUserInfo == null)
                val host =
                    uri.host?.lowercase()?.removeSurrounding("[", "]")?.trimEnd('.') ?: return null
                require(
                    host.isNotEmpty() &&
                        host != "localhost" &&
                        !host.endsWith(".localhost") &&
                        !host.endsWith(".local") &&
                        !host.endsWith(".internal")
                )
                require(uri.port == -1 || uri.port == 443)
                require(value.length <= 4096)
                // Numeric hosts can be rejected before DNS. Every resolved address is also checked
                // by the HTTP client's DNS hook, which is the resolver used for the actual socket.
                if (host.contains(':') || host.matches(Regex("[0-9.]+"))) {
                    require(isPublicAddress(InetAddress.getByName(host)))
                }
                val authority = if (host.contains(':')) "[$host]" else host
                // Preserve encoded query separators and path escapes exactly; decode/re-encode
                // through URI.query would change e.g. a value containing %26 into another field.
                URI(
                        "https://$authority${uri.rawPath.ifEmpty { "/" }}" +
                            (uri.rawQuery?.let { "?$it" } ?: "")
                    )
                    .normalize()
                    .toASCIIString()
            }
            .getOrNull()

    fun isPublicAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress
        )
            return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            val a = b[0]
            val c = b[1]
            return !(a == 0 ||
                a == 10 ||
                a == 127 ||
                a >= 224 ||
                (a == 100 && c in 64..127) ||
                (a == 169 && c == 254) ||
                (a == 172 && c in 16..31) ||
                (a == 192 && c == 168) ||
                (a == 192 && c == 0) ||
                (a == 198 && c in 18..19) ||
                (a == 198 && c == 51 && b[2] == 100) ||
                (a == 203 && c == 0 && b[2] == 113))
        }
        // IPv6: permit global unicast only, excluding documentation space.
        return b.size == 16 &&
            b[0] in 0x20..0x3f &&
            !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8)
    }
}

class WebResearchException(val userMessage: String) : java.io.IOException(userMessage)
