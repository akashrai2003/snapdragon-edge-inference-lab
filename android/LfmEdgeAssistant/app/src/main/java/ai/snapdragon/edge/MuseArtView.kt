package ai.snapdragon.edge

import android.content.Context
import android.graphics.*
import android.view.View

/** Resolution independent artwork, drawn with the same palette as the native interface. */
class MuseArtView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun color(value: String) = Color.parseColor(value)

    private val backgroundGradient =
        LinearGradient(
            0f,
            0f,
            340f,
            258f,
            intArrayOf(color("#EEE7F5"), color("#DED0EE"), color("#EBDCD6")),
            null,
            Shader.TileMode.CLAMP,
        )
    private val petalGradient =
        RadialGradient(
            178f,
            82f,
            76f,
            intArrayOf(color("#F9E9E7"), color("#B497D3"), color("#766095")),
            floatArrayOf(0f, .58f, 1f),
            Shader.TileMode.CLAMP,
        )
    private val centerGradient =
        RadialGradient(
            159f,
            104f,
            42f,
            intArrayOf(color("#FFEFDE"), color("#D4B3D9"), color("#9978B5")),
            null,
            Shader.TileMode.CLAMP,
        )
    private val sparkPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / 340f, height / 258f)
        paint.shader = backgroundGradient
        canvas.drawRoundRect(0f, 0f, 340f, 258f, 28f, 28f, paint)
        paint.shader = null
        canvas.save()
        canvas.rotate(-24f, 170f, 118f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f
        paint.color = color("#AA91BC")
        canvas.drawOval(31f, 74f, 309f, 164f, paint)
        canvas.drawOval(48f, 54f, 292f, 184f, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL
        // Layered translucent petals form a sculptural, luminous flower.
        for (i in 0..11) {
            canvas.save()
            canvas.rotate(i * 30f, 170f, 119f)
            paint.shader = petalGradient
            paint.alpha = 174
            canvas.drawOval(136f, 35f, 204f, 139f, paint)
            canvas.restore()
        }
        paint.alpha = 255
        paint.shader = centerGradient
        canvas.drawCircle(170f, 119f, 35f, paint)
        paint.shader = null
        fun spark(x: Float, y: Float, size: Float) {
            val path = sparkPath
            path.reset()
            path.moveTo(x, y - size)
            path.quadTo(x + size * .18f, y - size * .18f, x + size, y)
            path.quadTo(x + size * .18f, y + size * .18f, x, y + size)
            path.quadTo(x - size * .18f, y + size * .18f, x - size, y)
            path.quadTo(x - size * .18f, y - size * .18f, x, y - size)
            canvas.drawPath(path, paint)
        }
        paint.color = color("#68517E")
        spark(67f, 60f, 11f)
        spark(280f, 165f, 8f)
        paint.color = color("#FFFFFF")
        spark(265f, 54f, 6f)
        paint.color = color("#715B83")
        canvas.drawCircle(58f, 159f, 2.2f, paint)
        canvas.drawCircle(294f, 96f, 2f, paint)
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 8.5f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("A LITTLE CURIOSITY GOES A LONG WAY", 170f, 232f, paint)
        canvas.restore()
    }
}
