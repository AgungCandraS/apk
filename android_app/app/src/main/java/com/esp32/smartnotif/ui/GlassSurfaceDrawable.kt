package com.esp32.smartnotif.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.content.ContextCompat
import com.esp32.smartnotif.R

/** Samples only the decorative canvas: content is never captured or blurred. */
class GlassSurfaceDrawable private constructor(
    private val host: View, private val radius: Float, private val tone: Int
) : Drawable() {
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = host.resources.displayMetrics.density
    }
    private val rectangle = RectF()
    private val edgeRectangle = RectF()
    private val matrix = Matrix()
    private val position = IntArray(2)
    private val canvasPosition = IntArray(2)
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var bitmap: Bitmap? = null
    private var bitmapShader: BitmapShader? = null
    private var opacity = 255

    fun updatePosition() {
        host.getLocationInWindow(position)
        if (position[0] != lastX || position[1] != lastY) {
            lastX = position[0]; lastY = position[1]
            invalidateSelf()
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        rectangle.set(bounds)
        edgeRectangle.set(rectangle)
        edgeRectangle.inset(edgePaint.strokeWidth / 2, edgePaint.strokeWidth / 2)
        fun color(id: Int) = ContextCompat.getColor(host.context, id)
        val top = when (tone) {
            1 -> R.color.glass_inset_top
            2 -> R.color.glass_hero_top
            3 -> R.color.glass_nav_top
            else -> R.color.glass_top
        }
        val bottom = when (tone) {
            1 -> R.color.glass_inset_bottom
            2 -> R.color.glass_hero_bottom
            3 -> R.color.glass_nav_bottom
            else -> R.color.glass_bottom
        }
        tintPaint.shader = LinearGradient(0f, rectangle.top, 0f, rectangle.bottom,
            color(top), color(bottom), Shader.TileMode.CLAMP)
        edgePaint.shader = LinearGradient(0f, rectangle.top, rectangle.right, rectangle.bottom,
            color(R.color.glass_edge), color(R.color.glass_edge_bottom), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val backdrop = host.rootView.findViewById<GlassBackdropView>(R.id.glassBackdrop)
        val source = backdrop?.frostedBitmap
        if (source != null && backdrop.width > 0 && backdrop.height > 0) {
            if (bitmap !== source) {
                bitmap = source
                bitmapShader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                backdropPaint.shader = bitmapShader
            }
            host.getLocationInWindow(position)
            backdrop.getLocationInWindow(canvasPosition)
            matrix.setScale(backdrop.width.toFloat() / source.width, backdrop.height.toFloat() / source.height)
            matrix.postTranslate((canvasPosition[0] - position[0]).toFloat(), (canvasPosition[1] - position[1]).toFloat())
            bitmapShader?.setLocalMatrix(matrix)
            canvas.drawRoundRect(rectangle, radius, radius, backdropPaint)
        }
        canvas.drawRoundRect(rectangle, radius, radius, tintPaint)
        canvas.drawRoundRect(edgeRectangle, radius, radius, edgePaint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
        outline.alpha = .20f
    }

    override fun setAlpha(alpha: Int) {
        opacity = alpha
        backdropPaint.alpha = alpha; tintPaint.alpha = alpha; edgePaint.alpha = alpha
        invalidateSelf()
    }
    override fun getAlpha(): Int = opacity
    override fun setColorFilter(colorFilter: ColorFilter?) {
        tintPaint.colorFilter = colorFilter
        invalidateSelf()
    }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        fun install(host: View, radius: Float, tone: Int = 0) {
            val surface = GlassSurfaceDrawable(host, radius, tone)
            // Drawable replacement must not clear the XML layout's content padding.
            val left = host.paddingLeft; val top = host.paddingTop
            val right = host.paddingRight; val bottom = host.paddingBottom
            host.background = surface
            host.setPadding(left, top, right, bottom)
            host.clipToOutline = true
            val preDraw = ViewTreeObserver.OnPreDrawListener { surface.updatePosition(); true }
            var observer: ViewTreeObserver? = null
            fun attach() {
                observer = host.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
                surface.updatePosition()
            }
            host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = attach()
                override fun onViewDetachedFromWindow(v: View) {
                    observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
                    observer = null
                }
            })
            if (host.isAttachedToWindow) attach()
        }
    }
}
