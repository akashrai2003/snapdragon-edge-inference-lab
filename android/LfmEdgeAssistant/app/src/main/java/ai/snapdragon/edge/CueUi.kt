package ai.snapdragon.edge

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object CueUi {
    val paper = Color.parseColor("#F8F6F1")
    val ink = Color.parseColor("#29242E")
    val muted = Color.parseColor("#756C7D")
    val plum = Color.parseColor("#7554A4")
    val lilac = Color.parseColor("#ECE5F4")

    fun dp(context: android.content.Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()

    fun background(
        context: android.content.Context,
        color: Int,
        radius: Int = 24,
        stroke: Int? = null,
    ) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radius).toFloat()
            stroke?.let { setStroke(dp(context, 1), it) }
        }

    fun text(
        context: android.content.Context,
        value: String,
        size: Float = 15f,
        color: Int = ink,
        font: String = "sans-serif",
    ) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(font, Typeface.NORMAL)
            includeFontPadding = false
        }

    fun column(context: android.content.Context) =
        LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row(context: android.content.Context) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    fun add(
        parent: LinearLayout,
        view: View,
        width: Int = -1,
        height: Int = -2,
        top: Int = 0,
        weight: Float = 0f,
    ) {
        val c = parent.context
        parent.addView(
            view,
            LinearLayout.LayoutParams(
                    if (width < 0) width else dp(c, width),
                    if (height < 0) height else dp(c, height),
                    weight,
                )
                .apply { topMargin = dp(c, top) },
        )
    }

    fun tap(view: View, color: Int = lilac, radius: Int = 24, action: () -> Unit) {
        view.background =
            RippleDrawable(
                ColorStateList.valueOf(0x267554A4),
                background(view.context, color, radius),
                null,
            )
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener { action() }
    }

    fun button(
        context: android.content.Context,
        value: String,
        dark: Boolean = false,
        action: () -> Unit,
    ): TextView =
        text(context, value, 15f, if (dark) Color.WHITE else plum, "sans-serif-medium").apply {
            gravity = Gravity.CENTER
            minHeight = dp(context, 48)
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            tap(this, if (dark) ink else lilac, 25, action)
        }

    fun icon(context: android.content.Context, value: String, label: String, action: () -> Unit) =
        button(context, value, false, action).apply {
            contentDescription = label
            textSize = 22f
            setPadding(0, 0, 0, 0)
        }

    fun brand(context: android.content.Context): LinearLayout =
        row(context).apply {
            add(
                this,
                android.widget.ImageView(context).apply {
                    setImageResource(R.drawable.ic_spark)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                25,
                25,
            )
            add(
                this,
                text(context, "cue", 26f, ink, "sans-serif-medium").apply {
                    letterSpacing = -.04f
                    setPadding(dp(context, 8), 0, 0, 0)
                },
                -2,
            )
        }

    fun formatted(value: String): CharSequence {
        val output = SpannableStringBuilder()
        var code = false
        value.split('\n').forEachIndexed { index, line ->
            if (line.trim().startsWith("```")) {
                code = !code
                return@forEachIndexed
            }
            if (output.isNotEmpty()) output.append('\n')
            val start = output.length
            if (code) {
                output.append(line)
                output.setSpan(
                    TypefaceSpan("monospace"),
                    start,
                    output.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            } else {
                val heading = line.startsWith("#")
                val clean = line.replace(Regex("^#{1,6}\\s+"), "").replace(Regex("^[-*]\\s+"), "• ")
                val match = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
                var previous = 0
                for (m in match.findAll(clean)) {
                    output.append(clean.substring(previous, m.range.first))
                    val offset = output.length
                    output.append(m.groups[1]?.value ?: m.groups[2]!!.value)
                    output.setSpan(
                        if (m.groups[1] != null) StyleSpan(Typeface.BOLD)
                        else TypefaceSpan("monospace"),
                        offset,
                        output.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    previous = m.range.last + 1
                }
                output.append(clean.substring(previous))
                if (heading)
                    output.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        output.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
            }
        }
        return output
    }
}
