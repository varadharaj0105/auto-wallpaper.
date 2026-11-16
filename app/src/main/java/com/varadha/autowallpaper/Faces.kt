package com.varadha.autowallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds faces on the phone (Google ML Kit, nothing is uploaded) and frames the photo so
 * the faces sit in the lower-middle of the screen, clear of the lock-screen clock.
 */
object Faces {
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.06f)
                .build()
        )
    }

    /** Box around all faces in [bmp] pixels, or null if none. Call off the main thread. */
    fun find(bmp: Bitmap): RectF? = runCatching {
        val k = min(1f, 1024f / max(bmp.width, bmp.height))
        val small = if (k < 1f) {
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * k).roundToInt().coerceAtLeast(1),
                (bmp.height * k).roundToInt().coerceAtLeast(1),
                true
            )
        } else bmp
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(small, 0)), 15, TimeUnit.SECONDS)
        if (small !== bmp) small.recycle()
        if (faces.isEmpty()) return@runCatching null
        val box = RectF(faces[0].boundingBox)
        faces.drop(1).forEach { box.union(RectF(it.boundingBox)) }
        RectF(box.left / k, box.top / k, box.right / k, box.bottom / k)
    }.getOrNull()

    /**
     * Places the faces horizontally centred, a little below the middle of the screen.
     * If a group is too wide for the screen, it zooms out just enough to keep everyone in
     * (the sides get the photo's blurred background).
     */
    fun frame(p: Photo, imageW: Int, imageH: Int, faces: RectF, screenW: Float, screenH: Float): Photo {
        val rx = p.cl * imageW
        val ry = p.ct * imageH
        val rw = max(1f, (p.cr - p.cl) * imageW)
        val rh = max(1f, (p.cb - p.ct) * imageH)
        val f = RectF(faces).apply { offset(-rx, -ry) }

        val cover = Framing.cover(rw, rh, screenW, screenH)
        val wanted = f.width() * 1.3f // a bit of room around the faces
        var zoom = 1f
        if (wanted * cover > screenW) {
            zoom = max(Framing.minZoom(rw, rh, screenW, screenH), screenW / (wanted * cover))
        }
        val s = cover * zoom
        val cx = f.centerX() / rw
        // Faces' centre lands at about 58% of the screen height: below the clock.
        val cy = (f.centerY() - 0.08f * screenH / s) / rh
        return p.copy(rows = 1, zoom = zoom, cx = cx.coerceIn(0f, 1f), cy = cy.coerceIn(0f, 1f))
    }

    /** Re-frames one saved photo around its faces. Returns the new photo, or null if no face. */
    fun autoFrame(c: Context, p: Photo): Photo? {
        val bmp = Thumbs.decodeFull(p.file(c)) ?: return null
        val faces = find(bmp)
        val result = faces?.let {
            val (sw, sh) = Importer.screenSize(c)
            frame(p, bmp.width, bmp.height, it, sw, sh)
        }
        bmp.recycle()
        return result
    }
}
