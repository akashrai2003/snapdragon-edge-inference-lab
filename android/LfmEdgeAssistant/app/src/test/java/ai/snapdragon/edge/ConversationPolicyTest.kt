package ai.snapdragon.edge

import org.junit.Assert.*
import org.junit.Test

class ConversationPolicyTest {
    private val answer = "You can make a quick pasta with tomatoes, basil, and olive oil."

    @Test
    fun `speaker echo and imperfect echo cannot submit another question`() {
        listOf(
                "make a quick pasta",
                "pasta with tomato basil and olive oil",
                "You can make a quick pasta with tomatoes",
            )
            .forEach {
                assertEquals(
                    ConversationPolicy.Intent.ECHO,
                    ConversationPolicy.classify(it, answer, true, openInterruption = true).intent,
                )
            }
    }

    @Test
    fun `new question can interrupt and keeps its leading words`() {
        val decision =
            ConversationPolicy.classify(
                "Actually make it vegetarian please",
                answer,
                true,
                openInterruption = true,
            )
        assertEquals(ConversationPolicy.Intent.QUESTION, decision.intent)
        assertEquals("Actually make it vegetarian please", decision.text)
    }

    @Test
    fun `wake prefix interrupts promptly and is removed from next prompt`() {
        assertEquals(
            ConversationPolicy.Intent.INTERRUPT,
            ConversationPolicy.classify("Hey Cue", answer, true).intent,
        )
        assertEquals(
            "what about rice",
            ConversationPolicy.classify("Hey queue, what about rice", answer, true).text,
        )
        assertEquals(
            ConversationPolicy.Intent.INTERRUPT,
            ConversationPolicy.classify("Hey Cue stop", answer, true).intent,
        )
        assertEquals(
            ConversationPolicy.Intent.END,
            ConversationPolicy.classify("Hey Cue end conversation", answer, true).intent,
        )
    }

    @Test
    fun `one word followups work after playback but a stray word cannot interrupt`() {
        assertEquals(
            ConversationPolicy.Intent.ECHO,
            ConversationPolicy.classify("noise", answer, true).intent,
        )
        assertEquals(
            ConversationPolicy.Intent.QUESTION,
            ConversationPolicy.classify("why", "", false).intent,
        )
    }

    @Test
    fun `spoken instructions containing control words do not end the turn`() {
        val echo = "Say end conversation when you want to finish."
        assertEquals(
            ConversationPolicy.Intent.ECHO,
            ConversationPolicy.classify("end conversation", echo, true, openInterruption = true)
                .intent,
        )
        assertEquals(
            ConversationPolicy.Intent.QUESTION,
            ConversationPolicy.classify("Please explain how to stop a timer", "", false).intent,
        )
    }

    @Test
    fun `nearby conversation cannot interrupt without an explicit address by default`() {
        listOf("Can you pass the salt", "The bus is coming", "stop", "wait").forEach {
            assertEquals(
                ConversationPolicy.Intent.ECHO,
                ConversationPolicy.classify(it, answer, true).intent,
            )
        }
        assertEquals(
            ConversationPolicy.Intent.QUESTION,
            ConversationPolicy.classify("Hey Cue make it shorter", answer, true).intent,
        )
        assertEquals(
            ConversationPolicy.Intent.QUESTION,
            ConversationPolicy.classify("make it shorter", "", false).intent,
        )
    }

    @Test
    fun `speaker echo recognizes the Cue queue homophone`() {
        val spoken = "Say Hey Cue when you want to talk to me."
        assertEquals(
            ConversationPolicy.Intent.ECHO,
            ConversationPolicy.classify("hey queue", spoken, true).intent,
        )
        assertEquals(
            ConversationPolicy.Intent.ECHO,
            ConversationPolicy.classify("hey q when you want to talk", spoken, true).intent,
        )
        assertEquals(
            ConversationPolicy.Intent.QUESTION,
            ConversationPolicy.classify("Hey Queue tell me about Jupiter", spoken, true).intent,
        )
    }
}
