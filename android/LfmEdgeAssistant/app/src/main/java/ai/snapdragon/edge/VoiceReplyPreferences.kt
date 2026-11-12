package ai.snapdragon.edge

import android.content.Context
import android.content.SharedPreferences

/** One source of truth for automatic spoken replies. Explicit per-answer Read aloud still works. */
object VoiceReplyPreferences {
    const val KEY = "speak_replies"

    fun store(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("mira", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = store(context).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY, enabled).apply()
        if (!enabled) SpeechOutput.stop()
    }
}
