package com.varadha.autowallpaper

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Small image loader: decodes off the main thread at the size needed and caches results. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: FramedImageView, p: Photo, targetWidth: Int) {
        view.setPhoto(p)
        val key = "${p.id}@$targetWidth"
        view.loadKey = key
        cache.get(key)?.let {
            view.setBitmap(it)
            return
        }
        view.setBitmap(null)
        val file = p.file(view.context)
        pool.execute {
            val bmp = decode(file, targetWidth) ?: return@execute
            cache.put(key, bmp)
            main.post {
                if (view.loadKey == key) {
                    view.setBitmap(bmp)
                    view.alpha = 0f
                    view.animate().alpha(1f).setDuration(160).start()
                }
            }
        }
    }

    /** Loads a gallery image (album covers) into a plain ImageView. */
    fun loadUri(view: ImageView, uri: Uri, size: Int) {
        val key = "uri:$uri@$size"
        view.tag = key
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        val cr = view.context.applicationContext.contentResolver
        pool.execute {
            val bmp = runCatching {
                if (Build.VERSION.SDK_INT >= 29) cr.loadThumbnail(uri, Size(size, size), null)
                else ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val s = size.toFloat() / min(info.size.width, info.size.height)
                    if (s < 1f) d.setTargetSize(
                        (info.size.width * s).roundToInt().coerceAtLeast(1),
                        (info.size.height * s).roundToInt().coerceAtLeast(1)
                    )
                }
            }.getOrNull() ?: return@execute
            cache.put(key, bmp)
            main.post {
                if (view.tag == key) {
                    view.setImageBitmap(bmp)
                    view.alpha = 0f
                    view.animate().alpha(1f).setDuration(160).start()
                }
            }
        }
    }

    /** Full-size-ish decode for the editor. Off the main thread. */
    fun decodeFull(file: File): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    private fun decode(file: File, targetWidth: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            val w = info.size.width
            val h = info.size.height
            // Big enough to fill a phone-shaped tile (about 1 : 2.1) of this width.
            val s = max(targetWidth.toFloat() / w, targetWidth * 2.1f / h)
            if (s < 1f) {
                d.setTargetSize((w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1))
            }
        }
    }.getOrNull()
}
