package ai.snapdragon.edge

import org.junit.Assert.*
import org.junit.Test

class WakeCandidateTest {
    @Test
    fun `partial corrections must stabilize before one wake`() {
        val candidate = WakeCandidate()
        assertFalse(candidate.accept("hey queue", 0))
        assertFalse(candidate.accept("hey you", 150))
        assertFalse(candidate.accept("hey queue", 300))
        assertFalse(candidate.accept("hey queue", 450))
        assertTrue(candidate.accept("hey queue", 500))
        assertFalse(candidate.accept("hey queue", 900))
        assertFalse(candidate.accept("hey queue", 1200, final = true))
    }

    @Test
    fun `a final result can wake without waiting for another buffer`() {
        assertTrue(WakeCandidate().accept("hey queue", 50, final = true))
    }

    @Test
    fun `silence and similar words never become a wake even after long idle`() {
        val candidate = WakeCandidate()
        for (now in 0L..60000L step 100L) {
            listOf(
                    "",
                    "hey you",
                    "hey cute",
                    "thank you",
                    "queue the music",
                    "hey google",
                    "hey [unk]",
                )
                .forEach { assertFalse(it, candidate.accept(it, now)) }
        }
    }
}
