package com.varadha.autowallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws one photo into a view/screen: crop, zoom, position, and the background
 * (blurred photo, dark, or a colour from the photo) when the photo doesn't fill the screen.
 * The wallpaper, the previews and the editor all draw through this, so they always match.
 */
class Renderer {
    private val m = Matrix()
    private val bgM = Matrix()
    private val region = RectF()
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private var bgKey: String? = null
    private var bgSmall: Bitmap? = null
    private var bgColor = Color.BLACK

    fun draw(c: Canvas, b: Bitmap, p: Photo, vw: Float, vh: Float, extra: Float = 1f) {
        Framing.region(b.width, b.height, p, region)
        Framing.matrix(m, region, vw, vh, p.zoom, p.cx, p.cy, extra)
        dst.set(region)
        m.mapRect(dst)
        val covers = dst.left <= 0.5f && dst.top <= 0.5f && dst.right >= vw - 0.5f && dst.bottom >= vh - 0.5f
        if (!covers) background(c, b, p, vw, vh)
        val save = c.save()
        c.clipRect(dst)
        c.drawBitmap(b, m, paint)
        c.restoreToCount(save)
    }

    private fun background(c: Canvas, b: Bitmap, p: Photo, vw: Float, vh: Float) {
        when (p.bg) {
            Photo.BG_DARK -> c.drawColor(Color.BLACK)
            Photo.BG_COLOR -> {
                ensureBg(b, p)
                c.drawColor(bgColor)
            }
            else -> {
                ensureBg(b, p)
                val sm = bgSmall
                if (sm == null) {
                    c.drawColor(Color.BLACK)
                    return
                }
                // A tiny copy stretched over the screen with smoothing = a soft blur, almost free.
                val s = max(vw / sm.width, vh / sm.height) * 1.2f
                bgM.setScale(s, s)
                bgM.postTranslate((vw - sm.width * s) / 2f, (vh - sm.height * s) / 2f)
                c.drawBitmap(sm, bgM, paint)
                c.drawColor(0x40000000) // slightly darker so the photo stands out
            }
        }
    }

    private fun ensureBg(b: Bitmap, p: Photo) {
        val key = "${System.identityHashCode(b)}|${p.cl}|${p.ct}|${p.cr}|${p.cb}"
        if (key == bgKey) return
        bgKey = key
        runCatching {
            val src = Rect(
                region.left.toInt().coerceIn(0, b.width - 1),
                region.top.toInt().coerceIn(0, b.height - 1),
                region.right.toInt().coerceIn(1, b.width),
                region.bottom.toInt().coerceIn(1, b.height)
            )
            val midH = (32f * src.height() / max(1, src.width())).roundToInt().coerceIn(1, 96)
            val mid = Bitmap.createBitmap(32, midH, Bitmap.Config.ARGB_8888)
            Canvas(mid).drawBitmap(b, src, Rect(0, 0, mid.width, mid.height), paint)
            val tinyH = (10f * mid.height / mid.width).roundToInt().coerceAtLeast(1)
            val tiny = Bitmap.createScaledBitmap(mid, 10, tinyH, true)
            mid.recycle()
            bgSmall = tiny
            val px = Bitmap.createScaledBitmap(tiny, 1, 1, true).getPixel(0, 0)
            bgColor = Color.rgb(
                (Color.red(px) * 0.6f).toInt(),
                (Color.green(px) * 0.6f).toInt(),
                (Color.blue(px) * 0.6f).toInt()
            )
        }.onFailure {
            bgSmall = null
            bgColor = Color.BLACK
        }
    }
}
