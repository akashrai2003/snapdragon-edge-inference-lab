package ai.snapdragon.edge

import ai.snapdragon.edge.service.VoiceWakeService
import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Recognizer

/**
 * Actual native decoder, continuous-recording soak, and background UI handoff. No sound is played.
 */
object QuietWakeChecks {
    fun run(probe: Instrumentation): Bundle {
        val report = Bundle()
        val context = probe.targetContext
        val preferences = VoiceReplyPreferences.store(context)
        val hadReview = preferences.contains("review_voice")
        val review = preferences.getBoolean("review_voice", false)
        var activity: Activity? = null
        var panel: Activity? = null
        var draft: String? = null
        try {
            check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                "Unlock the phone for the background-opening check"
            }
            preferences.edit().putBoolean("review_voice", true).commit()
            val model = QuietSpeechModel.get(context)
            var positives = 0
            var negatives = 0
            val names = probe.context.assets.list("speech-fixtures").orEmpty().sorted()
            check(names.size == 6)
            for (name in names) {
                val wav = probe.context.assets.open("speech-fixtures/$name").use { it.readBytes() }
                val pcm = wavData(wav)
                val candidate = WakeCandidate()
                var matched = false
                Recognizer(model, 16000f, WakeCandidate.GRAMMAR).use { decoder ->
                    var offset = 0
                    var now = 0L
                    while (offset < pcm.size) {
                        val length = minOf(3200, pcm.size - offset)
                        val endpoint =
                            decoder.acceptWaveForm(pcm.copyOfRange(offset, offset + length), length)
                        val json =
                            JSONObject(if (endpoint) decoder.result else decoder.partialResult)
                        if (
                            candidate.accept(
                                json.optString(if (endpoint) "text" else "partial"),
                                now,
                                endpoint,
                            )
                        )
                            matched = true
                        offset += length
                        now += 100
                    }
                    if (
                        candidate.accept(
                            JSONObject(decoder.finalResult).optString("text"),
                            now,
                            true,
                        )
                    )
                        matched = true
                }
                check(matched == name.startsWith("wake-")) { "Acoustic fixture mismatch: $name" }
                if (matched) positives++ else negatives++
            }
            report.putString(
                "native_decoder",
                "PASS: $positives synthetic wake voices; $negatives confusable phrases rejected",
            )
            activity =
                probe.startActivitySync(
                    Intent(context, MainActivity::class.java)
                        .putExtra("destination", "home")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            probe.runOnMainSync {
                draft = CueSession.current.draft
                VoiceWakeService.start(context)
            }
            SystemClock.sleep(400)
            ParcelFileDescriptor.AutoCloseInputStream(
                    probe.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
                )
                .use { it.readBytes() }
            await(probe, "wake capture ready", 10000) {
                VoiceWakeService.isCapturing && QuietSpeechSession.captureSessionId >= 0
            }
            var initial = -1
            var starts = 0L
            probe.runOnMainSync {
                initial = QuietSpeechSession.captureSessionId
                starts =
                    context
                        .getSharedPreferences("cue_metrics", Context.MODE_PRIVATE)
                        .getLong("wake_capture_started_count", 0)
                val channel =
                    context
                        .getSystemService(NotificationManager::class.java)
                        .getNotificationChannel(VoiceWakeService.CHANNEL_ID)
                check(channel.sound == null && !channel.shouldVibrate()) {
                    "Wake notification is not silent"
                }
            }
            repeat(9) {
                SystemClock.sleep(5000)
                probe.runOnMainSync {
                    check(
                        VoiceWakeService.isCapturing &&
                            QuietSpeechSession.captureSessionId == initial
                    ) {
                        "Wake microphone restarted during idle"
                    }
                    check(
                        context
                            .getSharedPreferences("cue_metrics", Context.MODE_PRIVATE)
                            .getLong("wake_capture_started_count", 0) == starts
                    ) {
                        "Wake restart counter changed"
                    }
                }
            }
            report.putString(
                "steady_capture",
                "PASS: same AudioRecord session held for 45 seconds; no timed restart",
            )
            report.putString(
                "notification",
                "PASS: sound null and vibration disabled; global volume untouched",
            )
            val monitor = probe.addMonitor(FloatingAssistantActivity::class.java.name, null, false)
            probe.runOnMainSync {
                context.startService(
                    Intent(context, VoiceWakeService::class.java)
                        .setAction("ai.snapdragon.edge.VERIFY_OPENING")
                )
            }
            panel = probe.waitForMonitorWithTimeout(monitor, 7000)
            check(panel != null) { "Background overlay did not open the conversation" }
            probe.removeMonitor(monitor)
            report.putString(
                "background_open",
                "PASS: background service opened real overlay and floating activity",
            )
            probe.runOnMainSync {
                panel?.finish()
                VoiceWakeService.stop(context)
            }
            await(probe, "microphone released", 5000) {
                !VoiceWakeService.isCapturing && QuietSpeechSession.captureSessionId < 0
            }
            report.putString("stop", "PASS: microphone released after Turn off")
            report.putString("result", "PASS")
        } catch (error: Throwable) {
            report.putString("result", "FAIL: ${error.message}")
        } finally {
            probe.runOnMainSync {
                panel?.finish()
                VoiceWakeService.stop(context)
                draft?.let {
                    CueSession.draft(it)
                    CueSession.persist()
                }
                activity?.finish()
            }
            val restore = preferences.edit()
            if (hadReview) restore.putBoolean("review_voice", review)
            else restore.remove("review_voice")
            restore.commit()
        }
        return report
    }

    private fun await(
        probe: Instrumentation,
        label: String,
        timeout: Long,
        condition: () -> Boolean,
    ) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            var success = false
            probe.runOnMainSync { success = condition() }
            if (success) return
            SystemClock.sleep(50)
        }
        error("Timed out: $label")
    }

    private fun wavData(wav: ByteArray): ByteArray {
        fun size(offset: Int) =
            (0..3).fold(0) { value, index ->
                value or ((wav[offset + index].toInt() and 255) shl (index * 8))
            }
        var offset = 12
        while (offset + 8 <= wav.size) {
            val length = size(offset + 4)
            check(length >= 0 && offset + 8L + length <= wav.size)
            if (String(wav, offset, 4, Charsets.US_ASCII) == "data")
                return wav.copyOfRange(offset + 8, offset + 8 + length)
            offset += 8 + length + length % 2
        }
        error("Missing WAV data")
    }
}
