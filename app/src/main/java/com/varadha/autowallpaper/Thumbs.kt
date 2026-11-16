package com.varadha.autowallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** Small image loader: decodes off the main thread at the size needed and caches results. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, uri: Uri, targetWidth: Int, onDone: (() -> Unit)? = null) {
        val key = "$uri@$targetWidth"
        view.tag = key
        cache.get(key)?.let {
            view.setImageBitmap(it)
            onDone?.invoke()
            return
        }
        view.setImageDrawable(null)
        val ctx = view.context.applicationContext
        pool.execute {
            val bmp = decode(ctx, uri, targetWidth) ?: return@execute
            cache.put(key, bmp)
            main.post {
                if (view.tag == key) {
                    view.setImageBitmap(bmp)
                    view.alpha = 0f
                    view.animate().alpha(1f).setDuration(180).start()
                    onDone?.invoke()
                }
            }
        }
    }

    private fun decode(c: Context, uri: Uri, targetWidth: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
            d.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            val scale = targetWidth.toFloat() / info.size.width
            if (scale < 1f) {
                d.setTargetSize(
                    targetWidth,
                    max(1, (info.size.height * scale).roundToInt())
                )
            }
        }
    } catch (e: Exception) {
        null
    }
}
