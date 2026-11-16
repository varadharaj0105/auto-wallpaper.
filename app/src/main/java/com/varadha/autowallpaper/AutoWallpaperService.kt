package com.varadha.autowallpaper

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The live wallpaper. Android keeps it running as part of the Home/lock screen, so there is
 * no background service and nothing to exempt from battery optimisation.
 *
 * Screen off  -> pick the next photo and draw it while the display is dark.
 * Screen on   -> optional short "wake-up" zoom (about half a second), then idle again.
 */
class AutoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = AutoEngine()

    inner class AutoEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val ctx: Context get() = this@AutoWallpaperService
        private val handler = Handler(Looper.getMainLooper())

        private var surfaceW = 0
        private var surfaceH = 0
        private var bitmap: Bitmap? = null
        private var photo: Photo? = null
        private var loadedKey: String? = null
        private var registered = false
        private var visible = false
        private var lockedSinceShown = false
        private var animStart = 0L

        private val renderer = Renderer()
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 42f
        }

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        lockedSinceShown = true
                        handler.removeCallbacks(anim)
                        animStart = 0L
                        Store.onLock(ctx)
                        reload()
                        // Draw the first animation frame now, so waking up has no jump.
                        draw(if (Store.animate(ctx)) START_ZOOM else 1f)
                    }
                    Intent.ACTION_SCREEN_ON -> maybeAnimate()
                }
            }
        }

        private val anim = object : Runnable {
            override fun run() {
                val t = ((SystemClock.uptimeMillis() - animStart) / ANIM_MS.toFloat()).coerceIn(0f, 1f)
                val eased = 1f - (1f - t).pow(3)
                draw(1f + (START_ZOOM - 1f) * (1f - eased))
                if (t < 1f && visible) handler.postDelayed(this, 16) else animStart = 0L
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(false)
            if (!isPreview) {
                registerReceiver(screenReceiver, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                })
                registered = true
            }
            Store.prefs(ctx).registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            handler.removeCallbacks(anim)
            if (registered) runCatching { unregisterReceiver(screenReceiver) }
            registered = false
            Store.prefs(ctx).unregisterOnSharedPreferenceChangeListener(this)
            bitmap = null
            super.onDestroy()
        }

        /** Photo picked in the app, framing saved, Quick Settings tile... show it right away. */
        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            if (key == Store.K_LOCKS) return
            reload()
            if (key == Store.K_COLORS) notifyColorsChanged()
            if (animStart == 0L) draw(1f)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceW = width
            surfaceH = height
            loadedKey = null
            reload()
            draw(1f)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            if (animStart == 0L) draw(1f)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                reload()
                if (!maybeAnimate()) draw(1f)
            } else {
                handler.removeCallbacks(anim)
                animStart = 0L
            }
        }

        /** Plays the wake-up zoom once after a lock, if turned on. */
        private fun maybeAnimate(): Boolean {
            if (!lockedSinceShown) return false
            lockedSinceShown = false
            if (!Store.animate(ctx) || bitmap == null) {
                draw(1f)
                return false
            }
            handler.removeCallbacks(anim)
            animStart = SystemClock.uptimeMillis()
            handler.post(anim)
            return true
        }

        override fun onComputeColors(): WallpaperColors? {
            if (!Store.matchColors(ctx)) return null
            return bitmap?.let { runCatching { WallpaperColors.fromBitmap(it) }.getOrNull() }
        }

        private fun reload() {
            val p = Store.current(ctx)
            if (p == null || surfaceW == 0 || surfaceH == 0) {
                val had = bitmap != null
                bitmap = null
                photo = null
                loadedKey = null
                if (had) notifyColorsChanged()
                return
            }
            photo = p // framing can change without a new decode
            val key = "${p.id}|${surfaceW}x$surfaceH|${(p.zoom * 2).roundToInt()}|${p.cl},${p.ct},${p.cr},${p.cb}"
            if (key == loadedKey && bitmap != null) return
            bitmap = decode(p)
            loadedKey = key
            notifyColorsChanged()
        }

        /** Decodes only as many pixels as the screen (and zoom) needs. */
        private fun decode(p: Photo): Bitmap? = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(p.file(ctx))) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                // Pixels needed for the cropped part to fill the screen at this zoom.
                val rw = max(1f, (p.cr - p.cl) * w)
                val rh = max(1f, (p.cb - p.ct) * h)
                val need = min(1f, max(surfaceW / rw, surfaceH / rh) * max(1f, p.zoom))
                if (need < 1f) {
                    d.setTargetSize(
                        (w * need).roundToInt().coerceAtLeast(1),
                        (h * need).roundToInt().coerceAtLeast(1)
                    )
                }
            }
        } catch (e: Exception) {
            null
        }

        private fun isNight() =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        private fun draw(extraZoom: Float) {
            val holder = surfaceHolder
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (e: Exception) {
                null
            } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                val b = bitmap
                val p = photo
                if (b != null && p != null) {
                    renderer.draw(canvas, b, p, surfaceW.toFloat(), surfaceH.toFloat(), extraZoom)
                    if (Store.dim(ctx) && isNight()) canvas.drawColor(DIM_COLOR)
                } else {
                    canvas.drawText(
                        "Open Auto Wallpaper and add photos",
                        surfaceW / 2f, surfaceH / 2f, textPaint
                    )
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    companion object {
        private const val ANIM_MS = 550L
        private const val START_ZOOM = 1.08f
        private const val DIM_COLOR = 0x66000000
    }
}
