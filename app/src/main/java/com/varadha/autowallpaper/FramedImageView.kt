package com.varadha.autowallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Shows a photo exactly as it will be framed on the phone (same maths as the wallpaper). */
class FramedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var zoom = 1f
    private var cx = 0.5f
    private var cy = 0.5f
    private val m = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Used by [Thumbs] so a recycled view never shows a late-arriving wrong image. */
    var loadKey: String? = null

    fun setFraming(zoom: Float, cx: Float, cy: Float) {
        this.zoom = zoom
        this.cx = cx
        this.cy = cy
        invalidate()
    }

    fun setBitmap(b: Bitmap?) {
        bitmap = b
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        Framing.matrix(m, b.width, b.height, width.toFloat(), height.toFloat(), zoom, cx, cy)
        canvas.drawBitmap(b, m, paint)
    }
}
