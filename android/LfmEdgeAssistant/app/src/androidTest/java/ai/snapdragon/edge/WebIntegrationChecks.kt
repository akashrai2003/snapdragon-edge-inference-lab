package ai.snapdragon.edge

import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Real phone HTTPS + native model + chat rendering. Only synthetic public questions are used. */
object WebIntegrationChecks {
    fun run(probe: Instrumentation): Bundle {
        val report = Bundle()
        val context = probe.targetContext
        val prefs = VoiceReplyPreferences.store(context)
        val hadReview = prefs.contains("review_voice")
        val review = prefs.getBoolean("review_voice", false)
        var activity: Activity? = null
        var original: String? = null
        var fixture: String? = null
        var originalDraft: String? = null
        val statuses = mutableSetOf<String>()
        var firstToken = 0L
        var started = 0L
        val observer = {
            CueSession.taskStatus?.let { statuses.add(it) }
            if (
                firstToken == 0L &&
                    CueSession.current.messages.lastOrNull()?.content?.isNotEmpty() == true
            )
                firstToken = SystemClock.elapsedRealtime()
        }
        try {
            check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                "Unlock the phone for the web UI check"
            }
            prefs.edit().putBoolean("review_voice", true).commit()
            context
                .getSharedPreferences("cue_metrics", android.content.Context.MODE_PRIVATE)
                .edit()
                .apply {
                    for (key in
                        listOf(
                            "web_model_1_ms",
                            "web_model_2_ms",
                            "web_model_3_ms",
                            "web_model_4_ms",
                            "web_search_ms",
                            "web_read_batch_ms",
                            "web_page_1_ms",
                            "web_page_2_ms",
                            "web_page_3_ms",
                            "web_page_4_ms",
                        )) remove(key)
                }
                .commit()
            activity =
                probe.startActivitySync(
                    Intent(context, MainActivity::class.java)
                        .putExtra("destination", "chat")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            await(probe, "engine ready", 20000) { CueSession.ready }
            onMain(probe) {
                check(!CueSession.generating) { "An existing answer is running" }
                original = CueSession.current.id
                originalDraft = CueSession.current.draft
                CueSession.fresh()
                fixture = CueSession.current.id
                val chat =
                    CueActivity::class
                        .java
                        .getDeclaredField("chat")
                        .apply { isAccessible = true }
                        .get(activity) as CueChatView
                check(
                    descendants(chat).filterIsInstance<TextView>().any {
                        it.text.toString().contains("Search web")
                    }
                ) {
                    "Search control missing"
                }
                CueSession.observe(observer)
                started = SystemClock.elapsedRealtime()
                CueSession.send(
                    "Search the web for the official Android common intents documentation. Read the official page and explain what common intents let apps do in two sentences.",
                    searchWeb = true,
                )
            }
            await(probe, "web answer completes", 100000) { !CueSession.generating }
            onMain(probe) {
                val answer = CueSession.current.messages.last()
                check(answer.state == "complete" || answer.state == "length") {
                    "Web answer failed"
                }
                check(answer.content.isNotBlank()) { "Empty web answer" }
                check(answer.sources.isNotEmpty()) { "No real sources" }
                check(answer.sources.any { java.net.URI(it.url).host == "developer.android.com" }) {
                    "Official documentation absent from results"
                }
                check(answer.sources.count { it.pageRead } >= 2) {
                    "Multiple source pages were not read"
                }
                check(Regex("\\[\\d+]").containsMatchIn(answer.content)) {
                    "Answer has no source citation"
                }
                check(statuses.any { "Searching" in it } && statuses.any { "Reading" in it }) {
                    "Tool status transitions missing"
                }
                val labels =
                    descendants(activity!!.window.decorView)
                        .filterIsInstance<TextView>()
                        .map { it.text.toString() }
                        .toList()
                check("Sources" in labels && labels.any { "Page read" in it }) {
                    "Source cards missing"
                }
                check(labels.any { "Open search" in it }) { "Browser fallback missing" }
                report.putString(
                    "web",
                    "PASS: live HTTPS search, official result, parallel multi-page read and sourced native-model answer",
                )
                report.putString("ui", "PASS: search control, progress and source cards")
                report.putString(
                    "sample",
                    "sources=${answer.sources.size}; pages_read=${answer.sources.count { it.pageRead }}; first_answer_ms=${firstToken - started}; total_ms=${SystemClock.elapsedRealtime() - started}; single synthetic sample",
                )
            }
            val metrics =
                context.getSharedPreferences("cue_metrics", android.content.Context.MODE_PRIVATE)
            report.putString(
                "timings",
                (listOf(
                        "web_model_1_ms",
                        "web_model_2_ms",
                        "web_model_3_ms",
                        "web_model_4_ms",
                        "web_search_ms",
                        "web_read_batch_ms",
                        "web_page_1_ms",
                        "web_page_2_ms",
                        "web_page_3_ms",
                        "web_page_4_ms",
                    ))
                    .filter { metrics.contains(it) }
                    .joinToString { "$it=${metrics.getLong(it, 0)}" },
            )
            // Permit an external screenshot of this synthetic fixture before it is removed.
            SystemClock.sleep(3000)
            report.putString("result", "PASS")
        } catch (problem: Throwable) {
            report.putString("result", "FAIL: ${problem.message?.take(180)}")
        } finally {
            onMain(probe) {
                CueSession.remove(observer)
                if (fixture != null) {
                    CueSession.stop()
                    CueSession.delete(fixture!!)
                    original?.let { CueSession.select(it) }
                    originalDraft?.let { CueSession.draft(it) }
                    CueSession.persist()
                }
                activity?.finish()
            }
            prefs
                .edit()
                .apply {
                    if (hadReview) putBoolean("review_voice", review) else remove("review_voice")
                }
                .commit()
        }
        return report
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup)
            for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun await(
        probe: Instrumentation,
        label: String,
        timeout: Long,
        condition: () -> Boolean,
    ) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            onMain(probe) { done = condition() }
            if (done) return
            SystemClock.sleep(100)
        }
        error("Timed out: $label")
    }

    // Propagate assertions on the probe thread so finally can restore the user's state.
    private fun onMain(probe: Instrumentation, block: () -> Unit) {
        var failure: Throwable? = null
        probe.runOnMainSync {
            try {
                block()
            } catch (problem: Throwable) {
                failure = problem
            }
        }
        failure?.let { throw it }
    }
}
