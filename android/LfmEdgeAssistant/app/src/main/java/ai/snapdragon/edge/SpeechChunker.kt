package ai.snapdragon.edge

/** Incremental, bounded spoken phrases. Keeps code fences and token boundaries out of speech. */
class SpeechChunker {
    private val raw = StringBuilder()
    private val prose = StringBuilder()
    private var fenced = false
    private var ended = false

    fun append(delta: String): List<String> {
        if (ended) return emptyList()
        raw.append(delta)
        return drain(false)
    }

    fun finish(): List<String> {
        if (ended) return emptyList()
        ended = true
        return drain(true)
    }

    private fun drain(final: Boolean): List<String> {
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '`') {
                if (!final && raw.length - i < 3 && raw.substring(i).all { it == '`' }) break
                if (raw.startsWith("```", i)) {
                    fenced = !fenced
                    prose.append(' ')
                    i += 3
                } else {
                    i++ // Inline-code delimiters aren't pronounced.
                }
            } else {
                if (!fenced) prose.append(raw[i])
                i++
            }
        }
        raw.delete(0, i)
        val output = mutableListOf<String>()
        while (prose.isNotEmpty()) {
            val boundary = boundary(final)
            if (boundary == 0) break
            val phrase = clean(prose.substring(0, boundary))
            prose.delete(0, boundary)
            if (phrase.any { it.isLetterOrDigit() }) output.add(phrase)
        }
        return output
    }

    private fun boundary(final: Boolean): Int {
        for (i in prose.indices) {
            val c = prose[i]
            if (c == '\n' && i > 0) return i + 1
            if (c !in ".!?。！？") continue
            var end = i + 1
            while (end < prose.length && prose[end] in "\"'”’)") end++
            if (end == prose.length && !final) continue
            if (end < prose.length && !prose[end].isWhitespace()) continue
            val word = prose.substring(0, i).takeLastWhile { !it.isWhitespace() }.lowercase()
            if (
                c == '.' &&
                    (word in abbreviations ||
                        word.matches(Regex("[a-z]")) ||
                        word.matches(Regex("\\d+")) && prose.substring(0, i).trim() == word)
            )
                continue
            return end
        }
        // Begin a long first sentence at a natural clause, then bound subsequent phrases.
        if (prose.length >= 100) {
            val clause = (60 until minOf(prose.length, 180)).firstOrNull { prose[it] in ",;:" }
            if (clause != null) return clause + 1
        }
        if (prose.length > 200) {
            val space = prose.substring(0, 200).lastIndexOf(' ')
            return if (space >= 80) space + 1 else 200
        }
        return if (final) prose.length else 0
    }

    companion object {
        private val abbreviations =
            setOf("mr", "mrs", "ms", "dr", "prof", "st", "vs", "etc", "e.g", "i.e", "u.s", "u.k")

        fun clean(value: String): String =
            value
                .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
                .replace(Regex("https?://\\S+"), "link")
                .replace(Regex("(?m)^\\s*(?:#{1,6}\\s*|[-+*•·‣⁃▪▫●○◆◇►▸>]\\s+|\\d+[.)]\\s+)"), "")
                .replace(Regex("\\\\([*_`~#>\\[\\]])"), "$1")
                .replace(Regex("[*_`~∗⁎⋆✱✳]"), "")
                .replace('|', ' ')
                .replace(Regex("\\s+[–—]\\s+"), ", ")
                .replace(" & ", " and ")
                .replace(Regex("[\\p{So}\\p{Sk}]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
