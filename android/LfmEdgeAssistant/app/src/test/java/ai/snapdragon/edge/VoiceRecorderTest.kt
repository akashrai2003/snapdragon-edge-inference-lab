package ai.snapdragon.edge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class VoiceRecorderTest {
    @Test
    fun wavHasCorrectMonoPcmHeaderAndPayload() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = VoiceRecorder.createWav(pcm, 16000, 1, 16)
        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(40, header.getInt(4))
        assertEquals(16000, header.getInt(24))
        assertEquals(32000, header.getInt(28))
        assertEquals(4, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, 48))
    }
}
