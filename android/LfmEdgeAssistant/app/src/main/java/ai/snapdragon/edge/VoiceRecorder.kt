package ai.snapdragon.edge

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 16kHz Mono 16-bit PCM Audio Recorder for Snapdragon On-Device Whisper STT.
 * Features real-time RMS metering for audio waveforms and silence detection.
 */
class VoiceRecorder {
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private val pcmBuffer = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun startRecording(
        onRmsChanged: ((Float) -> Unit)? = null,
        onSilenceDetected: (() -> Unit)? = null
    ): Boolean {
        if (isRecording.get()) return true

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                // Fallback to standard MIC source
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                return false
            }

            pcmBuffer.reset()
            audioRecord?.startRecording()
            isRecording.set(true)

            recordThread = Thread {
                val chunk = ShortArray(bufferSize / 2)
                var speechStarted = false
                var silenceStartTime = 0L
                val speechThresholdRms = 18.0f // dB
                val silenceThresholdRms = 14.0f // dB

                while (isRecording.get()) {
                    val read = audioRecord?.read(chunk, 0, chunk.size) ?: -1
                    if (read > 0) {
                        // Write to PCM stream (little-endian shorts)
                        val byteBuf = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                        var sumSq = 0.0
                        for (i in 0 until read) {
                            val sample = chunk[i]
                            byteBuf.putShort(sample)
                            sumSq += sample.toDouble() * sample.toDouble()
                        }
                        synchronized(pcmBuffer) {
                            pcmBuffer.write(byteBuf.array(), 0, read * 2)
                        }

                        // Calculate RMS in dB
                        val rms = sqrt(sumSq / read)
                        val db = if (rms > 1.0) (20.0 * log10(rms)).toFloat() else 0.0f
                        onRmsChanged?.invoke(db)

                        // Silence Detection
                        val now = System.currentTimeMillis()
                        if (db >= speechThresholdRms) {
                            speechStarted = true
                            silenceStartTime = 0L
                        } else if (speechStarted && db < silenceThresholdRms) {
                            if (silenceStartTime == 0L) {
                                silenceStartTime = now
                            } else if (now - silenceStartTime > 1400) { // 1.4s of silence after speech
                                onSilenceDetected?.invoke()
                                break
                            }
                        }
                    }
                }
            }.apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            return true
        } catch (_: Exception) {
            stopRecording()
            return false
        }
    }

    fun stopRecording(): ByteArray {
        isRecording.set(false)
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            recordThread?.join(500)
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        recordThread = null

        val pcmData = synchronized(pcmBuffer) { pcmBuffer.toByteArray() }
        pcmBuffer.reset()
        return createWav(pcmData, sampleRate, 1, 16)
    }

    fun cancel() {
        isRecording.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        recordThread = null
        synchronized(pcmBuffer) { pcmBuffer.reset() }
    }

    val recording: Boolean
        get() = isRecording.get()

    companion object {
        fun createWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
            val totalAudioLen = pcmData.size
            val totalDataLen = totalAudioLen + 36
            val byteRate = sampleRate * channels * bitsPerSample / 8

            val header = ByteArray(44)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
            buffer.putInt(totalDataLen)
            buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
            buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
            buffer.putInt(16) // Subchunk1Size for PCM
            buffer.putShort(1.toShort()) // AudioFormat 1 = PCM
            buffer.putShort(channels.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(byteRate)
            buffer.putShort((channels * bitsPerSample / 8).toShort()) // BlockAlign
            buffer.putShort(bitsPerSample.toShort())
            buffer.put("data".toByteArray(Charsets.US_ASCII))
            buffer.putInt(totalAudioLen)

            val wav = ByteArray(44 + totalAudioLen)
            System.arraycopy(header, 0, wav, 0, 44)
            System.arraycopy(pcmData, 0, wav, 44, totalAudioLen)
            return wav
        }
    }
}
