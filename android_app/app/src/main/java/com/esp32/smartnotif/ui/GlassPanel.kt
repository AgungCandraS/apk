package com.esp32.smartnotif.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import com.esp32.smartnotif.R

/** A layout with the same measured content and a shared frosted backdrop. */
class GlassPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    init {
        val values = context.obtainStyledAttributes(attrs, R.styleable.GlassPanel)
        val radius = values.getDimension(R.styleable.GlassPanel_glassCornerRadius, 24 * resources.displayMetrics.density)
        val tone = values.getInt(R.styleable.GlassPanel_glassTone, 0)
        values.recycle()
        GlassSurfaceDrawable.install(this, radius, tone)
    }
}
