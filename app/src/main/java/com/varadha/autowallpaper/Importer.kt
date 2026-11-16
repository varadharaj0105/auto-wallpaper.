package com.varadha.autowallpaper

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Copies picked photos into the app, resized to a sensible wallpaper size.
 * Keeps them working if the original is moved/deleted, and makes switching instant.
 */
object Importer {
    private const val MAX_SIDE = 2560

    /** Slow (decodes and saves images): call off the main thread. */
    fun import(c: Context, uris: List<Uri>): List<Photo> = uris.mapNotNull { uri ->
        runCatching {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val s = MAX_SIDE.toFloat() / max(w, h)
                if (s < 1f) {
                    d.setTargetSize(
                        (w * s).roundToInt().coerceAtLeast(1),
                        (h * s).roundToInt().coerceAtLeast(1)
                    )
                }
            }
            val id = UUID.randomUUID().toString()
            FileOutputStream(File(Store.dir(c), "$id.jpg")).use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            bmp.recycle()
            Photo(id)
        }.getOrNull()
    }

    /** Version 1.4 and older stored links to the originals. Copy those in once. */
    fun hasLegacy(c: Context) = Store.prefs(c).contains("uris")

    fun migrateLegacy(c: Context): Int {
        val p = Store.prefs(c)
        val raw = p.getString("uris", null) ?: return 0
        val uris = raw.split("\n").filter { it.isNotBlank() }.map(Uri::parse)
        val photos = import(c, uris)
        Store.addAll(c, photos)
        uris.forEach {
            runCatching {
                c.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        p.edit().remove("uris").remove("index").apply()
        return photos.size
    }
}
