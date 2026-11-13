package ai.snapdragon.edge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock

/**
 * Physical-device smoke test using offline TTS/recognition, with an optional local-model sample.
 */
class VoiceIntegrationProbe : Instrumentation() {
    private var modelSample = false
    private var quietCheck = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        modelSample = arguments?.getString("model") == "true"
        quietCheck = arguments?.getString("quiet") == "true"
        start()
    }

    override fun onStart() {
        if (quietCheck) {
            val result = QuietWakeChecks.run(this)
            finish(
                if (result.getString("result") == "PASS") Activity.RESULT_OK
                else Activity.RESULT_CANCELED,
                result,
            )
            return
        }
        val report = Bundle()
        var activity: Activity? = null
        val prefs = VoiceReplyPreferences.store(targetContext)
        val hadReview = prefs.contains("review_voice")
        val review = prefs.getBoolean("review_voice", false)
        var draft: String? = null
        var originalThread: String? = null
        var fixtureThread: String? = null
        val hadSpoken = prefs.contains(VoiceReplyPreferences.KEY)
        val spoken = VoiceReplyPreferences.enabled(targetContext)
        var chat: CueChatView? = null
        try {
            // A test recognition must never submit ambient audio as a question.
            prefs.edit().putBoolean("review_voice", true).commit()
            activity =
                startActivitySync(
                    Intent(targetContext, FloatingAssistantActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        )
                )
            val field =
                CueActivity::class.java.getDeclaredField("chat").apply { isAccessible = true }
            chat = field.get(activity) as CueChatView
            val view = chat
            runOnMainSync {
                draft = CueSession.current.draft
                originalThread = CueSession.current.id
            }
            var first = ""
            runOnMainSync {
                first = SpeechOutput.begin(targetContext)
                SpeechOutput.append(first, "Cue voice check. ")
            }
            await("offline TTS starts", 15000) { SpeechOutput.echoText.contains("Cue voice check") }
            // Let its sentence drain while input is still open; the stream must remain active.
            SystemClock.sleep(2500)
            runOnMainSync { check(SpeechOutput.speaking) { "Open stream ended in a token gap" } }
            runOnMainSync { SpeechOutput.finish(first) }
            await("speech drains", 8000) { !SpeechOutput.speaking }
            report.putString(
                "streaming",
                "PASS: sentence played before finish; token gap preserved",
            )

            runOnMainSync {
                val cancelled = SpeechOutput.begin(targetContext)
                SpeechOutput.append(cancelled, "An unfinished phrase")
                SpeechOutput.stop()
                SpeechOutput.append(cancelled, ". This must not play. ")
                SpeechOutput.finish(cancelled)
                check(!SpeechOutput.speaking) { "Muted stream restarted" }
                val replacement = SpeechOutput.begin(targetContext)
                SpeechOutput.append(cancelled, "Stale tokens. ")
                SpeechOutput.finish(cancelled)
                check(SpeechOutput.speaking) { "Old completion stopped a replacement" }
                SpeechOutput.finish(replacement)
                check(!SpeechOutput.speaking) { "Empty stream failed to drain" }
            }
            report.putString(
                "cancellation",
                "PASS: mute rejects late tokens; old completion cannot stop replacement",
            )

            // Exercise the real UI transition with no inference request or changes to history.
            runOnMainSync {
                set(view, "conversationActive", true)
                set(view, "conversationThread", CueSession.current.id)
                set(view, "waitingForAnswer", true)
                first = SpeechOutput.begin(targetContext) // Input remains open, no audio queued.
                call(view, "reconcileConversation")
            }
            SystemClock.sleep(900)
            runOnMainSync {
                check(get(view, "voiceState") == "idle") {
                    "Follow-up started before speech finished"
                }
            }
            runOnMainSync { SpeechOutput.finish(first) }
            await("follow-up microphone", 5000) { get(view, "voiceState") == "recording" }
            report.putString(
                "followup",
                "PASS: waits for playback completion, then opens microphone",
            )
            runOnMainSync { view.cancelVoice() }
            SystemClock.sleep(900)
            runOnMainSync {
                check(get(view, "voiceState") == "idle")
                check(get(view, "conversationActive") == false)
                check(!(get(view, "voiceTurn") as VoiceTurn).active)
            }
            report.putString("end", "PASS: ends listening without a delayed restart")

            runOnMainSync { view.startVoice(followUp = false) }
            await("microphone ready for lifecycle check", 5000) {
                get(view, "voiceState") == "recording"
            }
            runOnMainSync { activity.finish() }
            await("microphone released on pause", 5000) {
                !(get(view, "voiceTurn") as VoiceTurn).active && get(view, "voiceState") == "idle"
            }
            report.putString("lifecycle", "PASS: dismiss cancels microphone")
            if (modelSample) {
                await("local model ready", 10000) { CueSession.ready }
                var started = 0L
                var firstAudio = 0L
                var stillGenerating = false
                runOnMainSync {
                    CueSession.fresh()
                    fixtureThread = CueSession.current.id
                    VoiceReplyPreferences.setEnabled(targetContext, true)
                    started = SystemClock.elapsedRealtime()
                    CueSession.send(
                        "Tell me eight short facts about the Moon in plain sentences.",
                        spokenRequest = true,
                    )
                }
                await("local model first spoken sentence", 30000) {
                    SpeechOutput.firstAudioAt >= started
                }
                runOnMainSync {
                    firstAudio = SpeechOutput.firstAudioAt - started
                    stillGenerating = CueSession.generating
                    CueSession.stop()
                }
                SystemClock.sleep(500)
                runOnMainSync {
                    check(!CueSession.generating && !SpeechOutput.speaking) {
                        "Stop did not cancel inference and speech"
                    }
                }
                check(stillGenerating) { "Speech started only after generation completed" }
                report.putString(
                    "model_sample",
                    "PASS: first audio at ${firstAudio}ms while generation still running; Stop cancels both",
                )
            }
            report.putString("result", "PASS")
        } catch (problem: Throwable) {
            report.putString("result", "FAIL: ${problem.message}")
        } finally {
            runOnMainSync {
                chat?.cancelVoice()
                SpeechOutput.stop()
                if (fixtureThread != null) {
                    CueSession.stop()
                    CueSession.delete(fixtureThread!!)
                    originalThread?.let { CueSession.select(it) }
                }
                draft?.let { CueSession.draft(it) }
                CueSession.persist()
                activity?.finish()
            }
            val restore = prefs.edit()
            if (hadReview) restore.putBoolean("review_voice", review)
            else restore.remove("review_voice")
            if (hadSpoken) restore.putBoolean(VoiceReplyPreferences.KEY, spoken)
            else restore.remove(VoiceReplyPreferences.KEY)
            restore.commit()
        }
        finish(
            if (report.getString("result") == "PASS") Activity.RESULT_OK
            else Activity.RESULT_CANCELED,
            report,
        )
    }

    private fun await(label: String, timeout: Long, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        }
        error("Timed out: $label")
    }

    private fun get(view: CueChatView, name: String): Any? =
        CueChatView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view)

    private fun set(view: CueChatView, name: String, value: Any) =
        CueChatView::class
            .java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(view, value)

    private fun call(view: CueChatView, name: String) =
        CueChatView::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(view)
}
