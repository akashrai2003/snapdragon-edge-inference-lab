package ai.snapdragon.edge

import org.junit.Assert.*
import org.junit.Test

class SpeechChunkerTest {
    @Test
    fun `speaks a finished sentence before the response ends`() {
        val chunks = SpeechChunker()
        assertTrue(chunks.append("Hello").isEmpty())
        assertTrue(chunks.append(" there.").isEmpty())
        assertEquals(listOf("Hello there."), chunks.append(" The next"))
        assertEquals(listOf("The next sentence."), chunks.append(" sentence. "))
        assertTrue(chunks.finish().isEmpty())
        assertTrue(chunks.finish().isEmpty())
        assertTrue(chunks.append("Must never replay.").isEmpty())
    }

    @Test
    fun `token partitioning does not change spoken content`() {
        val text =
            "Dr. Rao has 3.14 apples. Really? Yes!\n1. Try **this** idea.\nRead [the guide](https://example.com)."
        fun speak(tokens: List<String>): List<String> {
            val chunks = SpeechChunker()
            return tokens.flatMap { chunks.append(it) } + chunks.finish()
        }
        val expected =
            listOf(
                "Dr. Rao has 3.14 apples.",
                "Really?",
                "Yes!",
                "Try this idea.",
                "Read the guide.",
            )
        assertEquals(expected, speak(listOf(text)))
        assertEquals(expected, speak(text.map { it.toString() }))
        assertEquals(expected, speak(text.chunked(7)))
    }

    @Test
    fun `never reads fenced code even when delimiters span tokens`() {
        val chunks = SpeechChunker()
        val text = "Try this.\n```kotlin\nprintln(\"secret.\")\n```\nThen continue."
        val result = text.map { it.toString() }.flatMap { chunks.append(it) } + chunks.finish()
        assertEquals(listOf("Try this.", "Then continue."), result)
    }

    @Test
    fun `long answers start at a clause and never exceed speech limits`() {
        val chunks = SpeechChunker()
        val clause = "This is a longer opening that gives you the main point in a clear way,"
        assertTrue(chunks.append(clause).isEmpty())
        assertEquals(
            listOf(clause),
            chunks.append(" and then explains the next part of the answer"),
        )
        val rest = chunks.append(" word".repeat(3000)) + chunks.finish()
        assertTrue(rest.all { it.length <= 200 })
        assertTrue(rest.isNotEmpty())
    }

    @Test
    fun `mute and restart use independent chunkers`() {
        val first = SpeechChunker()
        first.append("A phrase that is cancelled")
        val second = SpeechChunker()
        assertEquals(listOf("Only this answer."), second.append("Only this answer. "))
        assertTrue(second.finish().isEmpty())
    }

    @Test
    fun `book recommendations never pronounce list or bold symbols`() {
        val value =
            "- **Atomic Habits** – A useful book.\n* **The Power of Habit** — Another good choice.\n▪ \u2731Mindset\u2731 & practice."
        val chunks = SpeechChunker()
        val result = value.map { it.toString() }.flatMap { chunks.append(it) } + chunks.finish()
        assertEquals(
            listOf(
                "Atomic Habits, A useful book.",
                "The Power of Habit, Another good choice.",
                "Mindset and practice.",
            ),
            result,
        )
        assertTrue(result.none { it.contains('*') || it.contains('▪') || it.contains('✱') })
    }
}
