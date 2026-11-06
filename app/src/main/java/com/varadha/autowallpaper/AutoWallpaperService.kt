package com.varadha.autowallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Live wallpaper that shows the next picked image every time the screen is locked.
 * It is driven by the system's wallpaper host, so there is no background service
 * and nothing to exempt from battery optimization.
 */
class AutoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = AutoEngine()

    inner class AutoEngine : Engine() {
        private var surfaceW = 0
        private var surfaceH = 0
        private var bitmap: Bitmap? = null
        private var loadedUri: Uri? = null
        private var registered = false
        private val matrix = Matrix()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 42f
        }

        // Screen locked -> move to the next image and prepare it while the display is off,
        // so the new wallpaper is already there when the screen comes back on.
        private val screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Store.advance(this@AutoWallpaperService)
                ensureLoaded()
                draw()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(false)
            if (!isPreview) {
                registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
                registered = true
            }
        }

        override fun onDestroy() {
            if (registered) {
                runCatching { unregisterReceiver(screenOffReceiver) }
                registered = false
            }
            bitmap = null
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceW = width
            surfaceH = height
            loadedUri = null // size changed -> decode again at the new size
            ensureLoaded()
            draw()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            draw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            // Also picks up a freshly chosen set of images from the app.
            if (visible) {
                ensureLoaded()
                draw()
            }
        }

        private fun ensureLoaded() {
            val uris = Store.uris(this@AutoWallpaperService)
            if (uris.isEmpty() || surfaceW == 0 || surfaceH == 0) {
                bitmap = null
                loadedUri = null
                return
            }
            val uri = uris[Store.index(this@AutoWallpaperService) % uris.size]
            if (uri == loadedUri && bitmap != null) return
            bitmap = decode(uri)
            loadedUri = uri
        }

        /** Decodes at roughly screen size (keeps memory low) and respects photo rotation. */
        private fun decode(uri: Uri): Bitmap? = try {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = max(
                    surfaceW.toFloat() / info.size.width,
                    surfaceH.toFloat() / info.size.height
                )
                if (scale < 1f) {
                    decoder.setTargetSize(
                        max(1, (info.size.width * scale).roundToInt()),
                        max(1, (info.size.height * scale).roundToInt())
                    )
                }
            }
        } catch (e: Exception) {
            null // image deleted or permission lost: show the hint instead of crashing
        }

        private fun draw() {
            val holder = surfaceHolder
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (e: Exception) {
                null
            } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                val bmp = bitmap
                if (bmp != null) {
                    // Center-crop to fill the screen.
                    val scale = max(
                        surfaceW.toFloat() / bmp.width,
                        surfaceH.toFloat() / bmp.height
                    )
                    matrix.setScale(scale, scale)
                    matrix.postTranslate(
                        (surfaceW - bmp.width * scale) / 2f,
                        (surfaceH - bmp.height * scale) / 2f
                    )
                    canvas.drawBitmap(bmp, matrix, paint)
                } else {
                    canvas.drawText(
                        "Open Auto Wallpaper and pick images",
                        surfaceW / 2f, surfaceH / 2f, textPaint
                    )
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
