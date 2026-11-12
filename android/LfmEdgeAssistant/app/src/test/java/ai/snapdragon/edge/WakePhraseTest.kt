package ai.snapdragon.edge

import ai.snapdragon.edge.service.VoiceWakeService
import org.junit.Assert.*
import org.junit.Test

class WakePhraseTest {
    @Test
    fun acceptsChosenNameAndHomophones() {
        listOf("Hey Cue", "HEY Q", "hey queue please help", "hey, cue").forEach {
            assertTrue(it, VoiceWakeService.matches(it))
        }
    }

    @Test
    fun rejectsOldAndBroadTriggers() {
        listOf(
                "hey Mira",
                "hey LFM",
                "hey assistant",
                "hey Liam",
                "cue",
                "thank you",
                "hey cute",
                "the queue is long",
            )
            .forEach { assertFalse(it, VoiceWakeService.matches(it)) }
    }
}
