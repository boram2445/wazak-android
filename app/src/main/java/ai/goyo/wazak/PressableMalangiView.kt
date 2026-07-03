package ai.goyo.wazak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import java.io.File
import java.net.URL
import kotlin.math.min

class PressableMalangiView(context: Context) : View(context) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var bitmap: Bitmap? = null
    private var pressX = 0f
    private var pressY = 0f
    private var pressAmount = 0f

    init {
        isClickable = true
    }

    fun setImagePath(path: String?) {
        bitmap?.recycle()
        bitmap = null
        if (path?.startsWith("http://") == true || path?.startsWith("https://") == true) {
            Thread {
                val loaded = runCatching { URL(path).openStream().use(BitmapFactory::decodeStream) }.getOrNull()
                post {
                    bitmap?.recycle()
                    bitmap = loaded
                    invalidate()
                }
            }.start()
        } else {
            bitmap = path?.let { BitmapFactory.decodeFile(it) }
        }
        invalidate()
    }

    fun setPressedPoint(x: Float, y: Float, amount: Float) {
        pressX = x
        pressY = y
        pressAmount = amount.coerceIn(0f, 1f)
        animate().cancel()
        invalidate()
    }

    fun releasePress() {
        ValueAnimator.ofFloat(pressAmount, 0f).apply {
            duration = 180
            addUpdateListener {
                pressAmount = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val inset = size * 0.06f
        rect.set(inset, inset, width - inset, height - inset)

        canvas.save()
        val scaleX = 1f + pressAmount * 0.035f
        val scaleY = 1f - pressAmount * 0.07f
        canvas.scale(scaleX, scaleY, pressX.coerceIn(0f, width.toFloat()), pressY.coerceIn(0f, height.toFloat()))

        val currentBitmap = bitmap
        if (currentBitmap == null) {
            drawFallbackMalangi(canvas)
        } else {
            canvas.drawBitmap(currentBitmap, null, rect, bitmapPaint)
        }

        if (pressAmount > 0f) drawPressShading(canvas, size)
        canvas.restore()
    }

    private fun drawFallbackMalangi(canvas: Canvas) {
        fillPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(Color.rgb(255, 177, 146), Color.rgb(240, 111, 90)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(rect, fillPaint)
        fillPaint.shader = null

        facePaint.color = Color.rgb(35, 31, 32)
        canvas.drawCircle(width * 0.38f, height * 0.44f, width * 0.035f, facePaint)
        canvas.drawCircle(width * 0.62f, height * 0.44f, width * 0.035f, facePaint)
        facePaint.style = Paint.Style.STROKE
        facePaint.strokeWidth = width * 0.025f
        facePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(width * 0.40f, height * 0.47f, width * 0.60f, height * 0.68f, 25f, 130f, false, facePaint)
        facePaint.style = Paint.Style.FILL
    }

    private fun drawPressShading(canvas: Canvas, size: Float) {
        val radius = size * 0.34f
        fillPaint.shader = RadialGradient(
            pressX,
            pressY,
            radius,
            intArrayOf(
                Color.argb((90 * pressAmount).toInt(), 40, 28, 28),
                Color.argb((28 * pressAmount).toInt(), 40, 28, 28),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(pressX, pressY, radius, fillPaint)

        fillPaint.shader = RadialGradient(
            pressX - radius * 0.34f,
            pressY - radius * 0.38f,
            radius * 0.72f,
            Color.argb((80 * pressAmount).toInt(), 255, 255, 255),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(pressX - radius * 0.28f, pressY - radius * 0.30f, radius * 0.72f, fillPaint)
        fillPaint.shader = null
    }
}
