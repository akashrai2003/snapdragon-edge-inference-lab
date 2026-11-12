package ai.snapdragon.edge

import android.content.Context
import android.os.SystemClock

/**
 * Bounded local counters/timings only. Never store audio, recognized words, prompts, or answers.
 */
object CueDiagnostics {
    fun timing(context: Context, name: String, value: Long) {
        context.applicationContext
            .getSharedPreferences("cue_metrics", Context.MODE_PRIVATE)
            .edit()
            .putLong(name, value)
            .apply()
    }

    fun event(context: Context, name: String) {
        val prefs =
            context.applicationContext.getSharedPreferences("cue_metrics", Context.MODE_PRIVATE)
        prefs
            .edit()
            .putLong(name + "_elapsed", SystemClock.elapsedRealtime())
            .putLong(name + "_count", prefs.getLong(name + "_count", 0) + 1)
            .apply()
    }

    fun summary(context: Context): String {
        val prefs = context.getSharedPreferences("cue_metrics", Context.MODE_PRIVATE)
        val latency =
            if (prefs.contains("wake_to_draw_ms"))
                "${prefs.getLong("wake_to_draw_ms",0)} ms (last event)"
            else "Not measured"
        return "Wake to panel draw: $latency\nWake matches: ${prefs.getLong("wake_match_count",0)}\nLast recognition error: ${prefs.getLong("recognition_error",0)}\n\nThese are local diagnostic samples, not a latency or battery benchmark. No speech or conversation text is recorded here."
    }
}
