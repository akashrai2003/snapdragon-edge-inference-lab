package ai.snapdragon.edge

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast

/** Accessible, synchronized quick switch for spoken voice replies versus read-only mode. */
class VoiceReplyToggle(context: Context) : FrameLayout(context) {
    private val preferences = VoiceReplyPreferences.store(context)
    private val icon = ImageView(context)
    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == VoiceReplyPreferences.KEY) render()
        }

    init {
        minimumWidth = CueUi.dp(context, 48)
        minimumHeight = CueUi.dp(context, 48)
        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon, LayoutParams(CueUi.dp(context, 23), CueUi.dp(context, 23), Gravity.CENTER))
        CueUi.tap(this, CueUi.lilac) {
            val enabled = !VoiceReplyPreferences.enabled(context)
            VoiceReplyPreferences.setEnabled(context, enabled)
            render()
            Toast.makeText(
                    context,
                    if (enabled) "Voice replies will be spoken" else "Read-only replies",
                    Toast.LENGTH_SHORT,
                )
                .show()
        }
        render()
    }

    private fun render() {
        val enabled = VoiceReplyPreferences.enabled(context)
        icon.setImageResource(if (enabled) R.drawable.ic_speaker_on else R.drawable.ic_speaker_off)
        icon.setColorFilter(if (enabled) CueUi.plum else CueUi.muted)
        contentDescription =
            if (enabled) "Spoken replies on. Switch to read-only"
            else "Read-only replies. Turn spoken replies on"
        tooltipText = if (enabled) "Spoken replies" else "Read-only replies"
        isSelected = enabled
        if (Build.VERSION.SDK_INT >= 30)
            stateDescription = if (enabled) "Spoken replies on" else "Spoken replies off"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        preferences.registerOnSharedPreferenceChangeListener(listener)
        render()
    }

    override fun onDetachedFromWindow() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        super.onDetachedFromWindow()
    }
}
