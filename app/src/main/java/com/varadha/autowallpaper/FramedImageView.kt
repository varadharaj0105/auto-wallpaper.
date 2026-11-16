package com.varadha.autowallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/** Shows a photo exactly as it will look on the phone (same renderer as the wallpaper). */
class FramedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var photo: Photo? = null
    private val renderer = Renderer()

    /** Used by [Thumbs] so a recycled view never shows a late-arriving wrong image. */
    var loadKey: String? = null

    fun setPhoto(p: Photo) {
        photo = p
        invalidate()
    }

    fun setBitmap(b: Bitmap?) {
        bitmap = b
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val p = photo ?: return
        if (width == 0 || height == 0) return
        renderer.draw(canvas, b, p, width.toFloat(), height.toFloat())
    }
}
