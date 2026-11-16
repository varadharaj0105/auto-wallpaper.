package com.varadha.autowallpaper

import android.graphics.Matrix
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * One place that decides how a photo is placed on a screen of any size.
 * Used by the wallpaper, the previews and the editor, so what you see is what you get.
 */
object Framing {

    /** The crop box in bitmap pixels. */
    fun region(bw: Int, bh: Int, p: Photo, out: RectF = RectF()): RectF {
        out.set(p.cl * bw, p.ct * bh, p.cr * bw, p.cb * bh)
        if (out.width() < 1f) out.right = out.left + 1f
        if (out.height() < 1f) out.bottom = out.top + 1f
        return out
    }

    /** Scale where the photo fills the whole view (some edges cut off). */
    fun cover(rw: Float, rh: Float, vw: Float, vh: Float) = max(vw / rw, vh / rh)

    /** Scale where the whole photo is visible (empty bands at the sides). */
    fun fit(rw: Float, rh: Float, vw: Float, vh: Float) = min(vw / rw, vh / rh)

    /** Smallest zoom allowed = "fit whole photo". */
    fun minZoom(rw: Float, rh: Float, vw: Float, vh: Float) = fit(rw, rh, vw, vh) / cover(rw, rh, vw, vh)

    fun matrix(
        out: Matrix,
        r: RectF,
        vw: Float, vh: Float,
        zoom: Float, cx: Float, cy: Float,
        extra: Float = 1f
    ): Matrix {
        val s = cover(r.width(), r.height(), vw, vh) * zoom * extra
        val px = r.left + clamp(cx * r.width(), vw / 2f / s, r.width())
        val py = r.top + clamp(cy * r.height(), vh / 2f / s, r.height())
        out.setScale(s, s)
        out.postTranslate(vw / 2f - px * s, vh / 2f - py * s)
        return out
    }

    /** Gap between copies in a 2x/3x layout. */
    fun gap(vw: Float, vh: Float) = min(vw, vh) * 0.018f

    /** Corner radius of each copy in a 2x/3x layout. */
    fun radius(vw: Float, vh: Float) = min(vw, vh) * 0.04f

    /** Size of one cell when the photo is stacked [rows] times. */
    fun cellSize(vw: Float, vh: Float, rows: Int): Pair<Float, Float> {
        if (rows <= 1) return vw to vh
        val g = gap(vw, vh)
        return (vw - 2f * g) to ((vh - g * (rows + 1)) / rows)
    }

    /** Keeps a centre (0..1) so no empty edge appears on a side the photo is big enough to cover. */
    fun clampFraction(f: Float, regionSize: Float, viewSize: Float, scale: Float): Float =
        clamp(f * regionSize, viewSize / 2f / scale, regionSize) / regionSize

    private fun clamp(p: Float, half: Float, size: Float) =
        if (half * 2f >= size) size / 2f else p.coerceIn(half, size - half)
}
