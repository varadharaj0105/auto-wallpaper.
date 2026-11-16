package com.varadha.autowallpaper

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the rotation in step with one gallery album: photos added to the album are copied in,
 * photos deleted from the album leave the rotation. Runs when the app opens and, at most every
 * 15 minutes, when the phone locks. Only looks at the one album you picked.
 */
object AlbumSync {

    data class Album(val id: String, val name: String, val count: Int)

    private val running = AtomicBoolean(false)
    private const val LOCK_SYNC_GAP_MS = 15 * 60 * 1000L

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(c: Context) =
        c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** All gallery albums with photos, biggest first. */
    fun albums(c: Context): List<Album> {
        if (!hasPermission(c)) return emptyList()
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        c.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, null
        )?.use { cur ->
            val idCol = cur.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = cur.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cur.moveToNext()) {
                val id = cur.getString(idCol) ?: continue
                val name = cur.getString(nameCol) ?: "Unnamed"
                val old = counts[id]
                counts[id] = name to ((old?.second ?: 0) + 1)
            }
        }
        return counts.map { (id, v) -> Album(id, v.first, v.second) }.sortedByDescending { it.count }
    }

    /** Called by the wallpaper on lock: syncs in the background if it's been a while. */
    fun maybeSyncOnLock(c: Context) {
        if (Store.albumId(c) == null) return
        if (System.currentTimeMillis() - Store.albumSyncedAt(c) < LOCK_SYNC_GAP_MS) return
        Thread { sync(c.applicationContext) }.start()
    }

    /**
     * Brings the album and the rotation in step. Slow when there are new photos (copies and
     * face-frames them), so call off the main thread. Returns (added, removed).
     */
    fun sync(c: Context): Pair<Int, Int> {
        val album = Store.albumId(c) ?: return 0 to 0
        if (!hasPermission(c)) return 0 to 0
        if (!running.compareAndSet(false, true)) return 0 to 0
        try {
            val inAlbum = LinkedHashSet<Long>()
            c.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.BUCKET_ID} = ?",
                arrayOf(album),
                "${MediaStore.Images.Media.DATE_ADDED} ASC"
            )?.use { cur ->
                val col = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cur.moveToNext()) inAlbum += cur.getLong(col)
            }

            // Gone from the album -> gone from the rotation.
            val synced = Store.photos(c).filter { it.mid != 0L }
            val gone = synced.filter { it.mid !in inAlbum }
            gone.forEach { Store.remove(c, it.id, fromSync = true) }

            // New in the album -> copy in (in small batches so they appear as they're ready).
            val have = synced.map { it.mid }.toSet()
            val skip = Store.excluded(c)
            val fresh = inAlbum.filter { it !in have && it.toString() !in skip }
            var added = 0
            fresh.chunked(5).forEach { batch ->
                val photos = batch.mapNotNull { id ->
                    Importer.importOne(
                        c,
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        mid = id
                    )
                }
                Store.addAll(c, photos)
                added += photos.size
            }
            Store.setAlbumSyncedAt(c, System.currentTimeMillis())
            return added to gone.size
        } finally {
            running.set(false)
        }
    }
}
