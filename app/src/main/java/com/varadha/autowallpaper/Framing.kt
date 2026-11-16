package com.varadha.autowallpaper

import android.graphics.Matrix
import kotlin.math.max

/**
 * One place that decides how a photo is placed on a screen of any size.
 * Used by the wallpaper, the previews and the Adjust editor, so what you see is what you get.
 */
object Framing {

    /** Scale that fills the view (cover), times the user's zoom. */
    fun scale(bw: Int, bh: Int, vw: Float, vh: Float, zoom: Float) =
        max(vw / bw, vh / bh) * zoom

    fun matrix(
        out: Matrix,
        bw: Int, bh: Int,
        vw: Float, vh: Float,
        zoom: Float, cx: Float, cy: Float,
        extra: Float = 1f
    ): Matrix {
        val s = scale(bw, bh, vw, vh, zoom) * extra
        val px = clamp(cx * bw, vw / 2f / s, bw.toFloat())
        val py = clamp(cy * bh, vh / 2f / s, bh.toFloat())
        out.setScale(s, s)
        out.postTranslate(vw / 2f - px * s, vh / 2f - py * s)
        return out
    }

    /** Keeps a centre (0..1) where the photo still covers the whole view, no empty edges. */
    fun clampFraction(f: Float, imageSize: Int, viewSize: Float, scale: Float): Float =
        clamp(f * imageSize, viewSize / 2f / scale, imageSize.toFloat()) / imageSize

    private fun clamp(p: Float, half: Float, size: Float) =
        if (half * 2f >= size) size / 2f else p.coerceIn(half, size - half)
}
