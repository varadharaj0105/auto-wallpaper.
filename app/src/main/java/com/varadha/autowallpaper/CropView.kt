package com.varadha.autowallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** Full-screen photo you can pinch to zoom, drag to move and double-tap to zoom in/out. */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            clamp()
            invalidate()
        }

    var zoom = 1f
        private set
    var cx = 0.5f
        private set
    var cy = 0.5f
        private set

    private val m = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun setFraming(zoom: Float, cx: Float, cy: Float) {
        this.zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        this.cx = cx
        this.cy = cy
        clamp()
        invalidate()
    }

    private fun currentScale(b: Bitmap) =
        Framing.scale(b.width, b.height, width.toFloat(), height.toFloat(), zoom)

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                clamp()
                invalidate()
                return true
            }
        })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val b = bitmap ?: return false
            val s = currentScale(b)
            cx += distanceX / (s * b.width)
            cy += distanceY / (s * b.height)
            clamp()
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoom = if (zoom > 1.05f) 1f else 2f
            clamp()
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clamp()
    }

    private fun clamp() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val s = currentScale(b)
        cx = Framing.clampFraction(cx, b.width, width.toFloat(), s)
        cy = Framing.clampFraction(cy, b.height, height.toFloat(), s)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        Framing.matrix(m, b.width, b.height, width.toFloat(), height.toFloat(), zoom, cx, cy)
        canvas.drawBitmap(b, m, paint)
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
    }
}
