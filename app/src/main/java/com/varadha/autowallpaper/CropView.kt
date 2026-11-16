package com.varadha.autowallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * The editor canvas. Two modes:
 *  POSITION: the photo full-screen as it will look. Pinch = zoom (down to "fit whole photo"),
 *            drag = move, double-tap = switch between Fit and Fill.
 *  CROP:     the whole photo with a crop box. Drag corners/edges to resize, drag inside to move.
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { POSITION, CROP }

    var mode = Mode.POSITION
        set(value) {
            field = value
            clamp()
            invalidate()
        }

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            clamp()
            invalidate()
        }

    var photo = Photo("")
        private set

    /** Called whenever framing changes, so the screen can update its buttons. */
    var onChange: (() -> Unit)? = null

    /** Crop shape as width/height in pixels, or null for free. */
    var aspect: Float? = null
        private set

    private val renderer = Renderer()
    private val region = RectF()
    private val imageRect = RectF()
    private val cropRect = RectF()
    private var insetTop = 0f
    private var insetBottom = 0f
    private val density = resources.displayMetrics.density

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        strokeWidth = 1f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }

    fun setPhoto(p: Photo) {
        photo = p
        clamp()
        invalidate()
    }

    fun setCropInsets(top: Float, bottom: Float) {
        insetTop = top
        insetBottom = bottom
        invalidate()
    }

    // ---------------- position ----------------

    /** "Fit whole photo" zoom for a single photo on this screen. */
    fun minZoom(): Float {
        val b = bitmap ?: return 1f
        if (width == 0 || height == 0) return 1f
        Framing.region(b.width, b.height, photo, region)
        return Framing.minZoom(region.width(), region.height(), width.toFloat(), height.toFloat())
    }

    /** Area one copy is drawn into: the whole screen, or one cell of a 2x/3x layout. */
    private fun viewport(): Pair<Float, Float> =
        Framing.cellSize(width.toFloat(), height.toFloat(), photo.rows)

    /** Same photo stacked [n] times (1 = single). */
    fun setRows(n: Int) {
        photo = photo.copy(rows = n, zoom = 1f, cx = 0.5f, cy = 0.5f)
        changed()
    }

    /** True when the whole photo is visible (single-photo mode). */
    val isFit: Boolean get() = photo.rows == 1 && abs(photo.zoom - minZoom()) < 0.01f

    /** True when the photo already has the same shape as the screen (Fit = Fill). */
    val fitEqualsFill: Boolean get() = minZoom() > 0.99f

    fun fit() {
        photo = photo.copy(rows = 1)
        photo = photo.copy(zoom = minZoom(), cx = 0.5f, cy = 0.5f)
        changed()
    }

    fun fill() {
        photo = photo.copy(rows = 1, zoom = 1f)
        changed()
    }

    fun setBg(bg: Int) {
        photo = photo.copy(bg = bg)
        changed()
    }

    fun resetPosition() {
        photo = photo.copy(zoom = 1f, cx = 0.5f, cy = 0.5f)
        changed()
    }

    // ---------------- crop ----------------

    fun resetCrop() {
        aspect = null
        photo = photo.copy(cl = 0f, ct = 0f, cr = 1f, cb = 1f, zoom = 1f, cx = 0.5f, cy = 0.5f)
        changed()
    }

    /** Largest box of this shape that fits, centred on the current crop. */
    fun setAspect(a: Float?) {
        aspect = a
        val b = bitmap
        if (a == null || b == null) {
            invalidate()
            return
        }
        val bw = b.width.toFloat()
        val bh = b.height.toFloat()
        val w = min(bw, bh * a)
        val h = w / a
        val centreX = (photo.cl + photo.cr) / 2f * bw
        val centreY = (photo.ct + photo.cb) / 2f * bh
        val l = (centreX - w / 2f).coerceIn(0f, bw - w)
        val t = (centreY - h / 2f).coerceIn(0f, bh - h)
        photo = photo.copy(
            cl = l / bw, ct = t / bh, cr = (l + w) / bw, cb = (t + h) / bh,
            zoom = 1f, cx = 0.5f, cy = 0.5f
        )
        changed()
    }

    private fun changed() {
        clamp()
        invalidate()
        onChange?.invoke()
    }

    /** Keeps zoom between "fit" and 5x, and no empty edges on a side the photo can cover. */
    private fun clamp() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        Framing.region(b.width, b.height, photo, region)
        val (vw, vh) = viewport()
        // In a 2x/3x layout each copy always fills its cell; single photos can go down to "fit".
        val min = if (photo.rows > 1) 1f else Framing.minZoom(region.width(), region.height(), vw, vh)
        val zoom = photo.zoom.coerceIn(min, MAX_ZOOM)
        val s = Framing.cover(region.width(), region.height(), vw, vh) * zoom
        photo = photo.copy(
            zoom = zoom,
            cx = Framing.clampFraction(photo.cx, region.width(), vw, s),
            cy = Framing.clampFraction(photo.cy, region.height(), vh, s)
        )
    }

    // ---------------- gestures ----------------

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                photo = photo.copy(zoom = photo.zoom * detector.scaleFactor)
                changed()
                return true
            }
        })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val b = bitmap ?: return false
            Framing.region(b.width, b.height, photo, region)
            val (vw, vh) = viewport()
            val s = Framing.cover(region.width(), region.height(), vw, vh) * photo.zoom
            photo = photo.copy(
                cx = photo.cx + distanceX / (s * region.width()),
                cy = photo.cy + distanceY / (s * region.height())
            )
            changed()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (photo.rows > 1) {
                photo = photo.copy(zoom = if (photo.zoom > 1.05f) 1f else 2f)
                changed()
            } else if (isFit) fill() else fit()
            return true
        }
    })

    private var dragFlags = 0
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return true
        if (mode == Mode.POSITION) {
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                layoutCrop()
                dragFlags = hitTest(event.x, event.y)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> if (dragFlags != 0) {
                drag((event.x - lastX) / imageRect.width(), (event.y - lastY) / imageRect.height())
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragFlags != 0) {
                dragFlags = 0
                // New crop: start from "fill" at the centre.
                photo = photo.copy(zoom = 1f, cx = 0.5f, cy = 0.5f)
                changed()
            }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): Int {
        val slop = 28f * density
        val inY = y > cropRect.top - slop && y < cropRect.bottom + slop
        val inX = x > cropRect.left - slop && x < cropRect.right + slop
        var f = 0
        if (inY && abs(x - cropRect.left) < slop) f = f or L
        else if (inY && abs(x - cropRect.right) < slop) f = f or R
        if (inX && abs(y - cropRect.top) < slop) f = f or T
        else if (inX && abs(y - cropRect.bottom) < slop) f = f or B
        if (f == 0 && cropRect.contains(x, y)) f = MOVE
        return f
    }

    private fun drag(dx: Float, dy: Float) {
        val b = bitmap ?: return
        var l = photo.cl
        var t = photo.ct
        var r = photo.cr
        var bt = photo.cb
        if (dragFlags == MOVE) {
            val w = r - l
            val h = bt - t
            l = (l + dx).coerceIn(0f, 1f - w)
            t = (t + dy).coerceIn(0f, 1f - h)
            r = l + w
            bt = t + h
        } else {
            if (dragFlags and L != 0) l = (l + dx).coerceIn(0f, r - MIN_CROP)
            if (dragFlags and R != 0) r = (r + dx).coerceIn(l + MIN_CROP, 1f)
            if (dragFlags and T != 0) t = (t + dy).coerceIn(0f, bt - MIN_CROP)
            if (dragFlags and B != 0) bt = (bt + dy).coerceIn(t + MIN_CROP, 1f)
            val a = aspect
            if (a != null) {
                val bw = b.width.toFloat()
                val bh = b.height.toFloat()
                if (dragFlags and (L or R) == 0) {
                    // Only top/bottom edge moved: width follows height, centred.
                    val wf = (bt - t) * bh * a / bw
                    val c = (l + r) / 2f
                    l = c - wf / 2f
                    r = c + wf / 2f
                } else {
                    val hf = (r - l) * bw / a / bh
                    when {
                        dragFlags and T != 0 -> t = bt - hf
                        dragFlags and B != 0 -> bt = t + hf
                        else -> {
                            val c = (t + bt) / 2f
                            t = c - hf / 2f
                            bt = c + hf / 2f
                        }
                    }
                }
                // Shape can't fit there: ignore this step.
                if (l < 0f || t < 0f || r > 1f || bt > 1f || r - l < MIN_CROP || bt - t < MIN_CROP) return
            }
        }
        photo = photo.copy(cl = l, ct = t, cr = r, cb = bt)
        invalidate()
    }

    // ---------------- drawing ----------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clamp()
    }

    /** Where the whole photo and the crop box sit on screen in crop mode. */
    private fun layoutCrop() {
        val b = bitmap ?: return
        val pad = 24f * density
        val area = RectF(pad, insetTop, width - pad, height - insetBottom)
        if (area.height() < 100f) area.set(pad, pad, width - pad, height - pad)
        val k = min(area.width() / b.width, area.height() / b.height)
        val iw = b.width * k
        val ih = b.height * k
        imageRect.set(area.centerX() - iw / 2f, area.centerY() - ih / 2f, area.centerX() + iw / 2f, area.centerY() + ih / 2f)
        cropRect.set(
            imageRect.left + photo.cl * iw,
            imageRect.top + photo.ct * ih,
            imageRect.left + photo.cr * iw,
            imageRect.top + photo.cb * ih
        )
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (mode == Mode.POSITION) {
            renderer.draw(canvas, b, photo, width.toFloat(), height.toFloat())
            return
        }
        layoutCrop()
        canvas.drawBitmap(b, null, imageRect, paint)

        val save = canvas.save()
        canvas.clipOutRect(cropRect)
        canvas.drawRect(imageRect, dimPaint)
        canvas.restoreToCount(save)

        // Rule-of-thirds grid
        for (i in 1..2) {
            val x = cropRect.left + cropRect.width() * i / 3f
            val y = cropRect.top + cropRect.height() * i / 3f
            canvas.drawLine(x, cropRect.top, x, cropRect.bottom, gridPaint)
            canvas.drawLine(cropRect.left, y, cropRect.right, y, gridPaint)
        }
        canvas.drawRect(cropRect, borderPaint)

        // Corner handles
        val len = min(22f * density, min(cropRect.width(), cropRect.height()) / 3f)
        with(cropRect) {
            canvas.drawLine(left, top, left + len, top, handlePaint)
            canvas.drawLine(left, top, left, top + len, handlePaint)
            canvas.drawLine(right, top, right - len, top, handlePaint)
            canvas.drawLine(right, top, right, top + len, handlePaint)
            canvas.drawLine(left, bottom, left + len, bottom, handlePaint)
            canvas.drawLine(left, bottom, left, bottom - len, handlePaint)
            canvas.drawLine(right, bottom, right - len, bottom, handlePaint)
            canvas.drawLine(right, bottom, right, bottom - len, handlePaint)
        }
    }

    companion object {
        const val MAX_ZOOM = 5f
        private const val MIN_CROP = 0.08f
        private const val L = 1
        private const val T = 2
        private const val R = 4
        private const val B = 8
        private const val MOVE = 16
    }
}
