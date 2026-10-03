package com.esp32.smartnotif.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.esp32.smartnotif.R
import kotlin.math.ceil
import kotlin.math.max

/** A shared, static backdrop. Blur is computed once per size, never during scrolling. */
class GlassBackdropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    var frostedBitmap: Bitmap? = null
        private set
    private var wallpaper: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val bw = max(1, ceil(w / 4.0).toInt())
        val bh = max(1, ceil(h / 4.0).toInt())
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val width = bw.toFloat()
        val height = bh.toFloat()
        fun color(id: Int) = ContextCompat.getColor(context, id)
        paint.shader = LinearGradient(0f, 0f, width, height,
            color(R.color.canvas_top), color(R.color.canvas_bottom), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, paint)

        val ribbon = Path().apply {
            moveTo(-width * .2f, height * .58f)
            cubicTo(width * .28f, height * .57f, width * .65f, height * .08f, width * 1.12f, -height * .03f)
            lineTo(width * 1.12f, height * .23f)
            cubicTo(width * .62f, height * .26f, width * .35f, height * .79f, -width * .2f, height * .76f)
            close()
        }
        paint.shader = LinearGradient(0f, height * .6f, width, height * .1f,
            color(R.color.canvas_ribbon_start), color(R.color.canvas_ribbon_end), Shader.TileMode.CLAMP)
        canvas.drawPath(ribbon, paint)

        val lowerFold = Path().apply {
            moveTo(-width * .2f, height * .85f)
            cubicTo(width * .28f, height * .65f, width * .65f, height * .87f, width * 1.2f, height * .57f)
            lineTo(width * 1.2f, height * 1.2f)
            lineTo(-width * .2f, height * 1.2f)
            close()
        }
        paint.shader = LinearGradient(0f, height * .68f, width, height,
            color(R.color.canvas_fold_start), color(R.color.canvas_fold_end), Shader.TileMode.CLAMP)
        canvas.drawPath(lowerFold, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(.5f, resources.displayMetrics.density / 4f)
        paint.color = color(R.color.canvas_contour)
        val contour = Path().apply {
            moveTo(-width * .1f, height * .60f)
            cubicTo(width * .32f, height * .58f, width * .69f, height * .12f, width * 1.12f, height * .01f)
        }
        canvas.drawPath(contour, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        wallpaper = bitmap
        frostedBitmap = blur(bitmap, max(1, ceil(18 * resources.displayMetrics.density / 4).toInt()))
        destination.set(0, 0, w, h)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        wallpaper?.let { canvas.drawBitmap(it, null, destination, paint) }
    }

    private fun blur(source: Bitmap, radius: Int): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        val buffer = IntArray(pixels.size)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val kernel = 2 * radius + 1
        // Three separable box passes approximate a Gaussian and keep allocations bounded.
        repeat(3) {
            for (y in 0 until h) {
                var r = 0; var g = 0; var b = 0
                for (offset in -radius..radius) {
                    val c = pixels[y * w + offset.coerceIn(0, w - 1)]
                    r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
                }
                for (x in 0 until w) {
                    buffer[y * w + x] = (255 shl 24) or ((r / kernel) shl 16) or ((g / kernel) shl 8) or (b / kernel)
                    val remove = pixels[y * w + (x - radius).coerceIn(0, w - 1)]
                    val add = pixels[y * w + (x + radius + 1).coerceIn(0, w - 1)]
                    r += ((add shr 16) and 255) - ((remove shr 16) and 255)
                    g += ((add shr 8) and 255) - ((remove shr 8) and 255)
                    b += (add and 255) - (remove and 255)
                }
            }
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0
                for (offset in -radius..radius) {
                    val c = buffer[offset.coerceIn(0, h - 1) * w + x]
                    r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
                }
                for (y in 0 until h) {
                    pixels[y * w + x] = (255 shl 24) or ((r / kernel) shl 16) or ((g / kernel) shl 8) or (b / kernel)
                    val remove = buffer[(y - radius).coerceIn(0, h - 1) * w + x]
                    val add = buffer[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                    r += ((add shr 16) and 255) - ((remove shr 16) and 255)
                    g += ((add shr 8) and 255) - ((remove shr 8) and 255)
                    b += (add and 255) - (remove and 255)
                }
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
