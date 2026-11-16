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

    /** [coverId] = newest photo in the album, used as its cover (0 = none). */
    data class Album(val id: String, val name: String, val count: Int, val coverId: Long = 0L) {
        val coverUri get() = if (coverId == 0L) null
        else ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, coverId)
    }

    private val running = AtomicBoolean(false)
    private const val LOCK_SYNC_GAP_MS = 15 * 60 * 1000L

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(c: Context) =
        c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** All gallery albums with photos, biggest first, each with its newest photo as cover. */
    fun albums(c: Context): List<Album> {
        if (!hasPermission(c)) return emptyList()
        data class Acc(val name: String, var count: Int, val cover: Long)
        val acc = LinkedHashMap<String, Acc>()
        c.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            ),
            null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cur ->
            val idCol = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketCol = cur.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = cur.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cur.moveToNext()) {
                val bucket = cur.getString(bucketCol) ?: continue
                val a = acc[bucket]
                if (a == null) {
                    acc[bucket] = Acc(cur.getString(nameCol) ?: "Other photos", 1, cur.getLong(idCol))
                } else {
                    a.count++
                }
            }
        }
        return acc.map { (id, a) -> Album(id, a.name, a.count, a.cover) }.sortedByDescending { it.count }
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
