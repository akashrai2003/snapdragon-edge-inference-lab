package ai.snapdragon.edge

import java.util.Locale

/**
 * A partial keyword must survive another acoustic update; ordinary confusable phrases do not wake.
 */
class WakeCandidate {
    private var since = -1L
    private var fired = false

    fun accept(text: String, now: Long, final: Boolean = false): Boolean {
        if (fired) return false
        if (!matches(text)) {
            since = -1
            return false
        }
        if (since < 0) since = now
        if (!final && now - since < 180) return false
        fired = true
        return true
    }

    companion object {
        // Include confusable alternatives so the decoder can reject them, instead of forcing a
        // wake.
        const val GRAMMAR =
            "[\"hey queue\",\"hey you\",\"hey google\",\"hey siri\",\"thank you\",\"queue the music\",\"hello\",\"okay\",\"[unk]\"]"

        fun matches(value: String): Boolean =
            Regex("\\bhey\\s+(cue|queue|q)\\b")
                .containsMatchIn(value.lowercase(Locale.ROOT).replace(Regex("[^a-z ]"), " "))
    }
}
