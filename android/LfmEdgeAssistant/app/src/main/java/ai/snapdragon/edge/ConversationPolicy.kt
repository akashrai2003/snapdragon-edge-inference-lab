package ai.snapdragon.edge

import java.util.Locale

/**
 * Pure recognition policy: do not submit speaker echo, and keep explicit controls out of prompts.
 */
object ConversationPolicy {
    enum class Intent {
        ECHO,
        QUESTION,
        INTERRUPT,
        END,
    }

    data class Decision(val intent: Intent, val text: String = "")

    private fun words(value: String): List<String> =
        Regex("[\\p{L}\\p{N}']+").findAll(value.lowercase(Locale.ROOT)).map { it.value }.toList()

    fun classify(
        value: String,
        echo: String,
        duringAnswer: Boolean,
        openInterruption: Boolean = false,
    ): Decision {
        val input = words(value)
        if (input.isEmpty()) return Decision(Intent.ECHO)
        val normalized = input.joinToString(" ")
        val source = words(echo)
        val sourceText = source.joinToString(" ")
        // Also tolerate imperfect speaker transcription (small omissions or substitutions).
        val overlap = input.count { it in source }.toFloat() / input.size
        if (source.isNotEmpty() && (sourceText.contains(normalized) || overlap >= .8f))
            return Decision(Intent.ECHO)
        val addressed =
            input.size >= 2 && input[0] == "hey" && input[1] in setOf("cue", "queue", "q")
        if (duringAnswer && !openInterruption && !addressed) return Decision(Intent.ECHO)
        val remaining = if (addressed) input.drop(2) else input
        val command = remaining.joinToString(" ")
        if (
            command in
                setOf(
                    "end conversation",
                    "stop conversation",
                    "goodbye cue",
                    "bye cue",
                    "that's all",
                    "that is all",
                )
        )
            return Decision(Intent.END)
        if (
            command in setOf("stop", "wait", "pause", "stop cue", "wait cue", "pause cue") ||
                addressed && remaining.isEmpty()
        )
            return Decision(Intent.INTERRUPT)
        if (duringAnswer && !addressed) {
            // Two novel words reduce false triggers from a stray noise or clipped echo.
            val novel = input.count { it !in source }
            if (input.size < 2 || (source.isNotEmpty() && novel < 2)) return Decision(Intent.ECHO)
        }
        val text =
            if (addressed)
                value.replaceFirst(Regex("(?i)^\\s*hey\\s+(?:cue|queue|q)\\b[,!.:?\\s]*"), "")
            else value
        return Decision(Intent.QUESTION, text.trim())
    }
}
