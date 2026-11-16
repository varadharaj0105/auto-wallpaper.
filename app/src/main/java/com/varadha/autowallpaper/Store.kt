package com.varadha.autowallpaper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One wallpaper photo. The image itself is a copy kept inside the app (so it keeps working
 * even if the original is moved or deleted).
 *
 * cl/ct/cr/cb = crop box (0..1 of the photo).
 * zoom: 1 = fill the screen, below 1 = show more (down to "fit whole photo"), above 1 = zoom in.
 * cx/cy = which point of the cropped photo sits in the screen centre (0..1).
 * bg = what fills empty space when the photo doesn't cover the screen.
 */
data class Photo(
    val id: String,
    val zoom: Float = 1f,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val cl: Float = 0f,
    val ct: Float = 0f,
    val cr: Float = 1f,
    val cb: Float = 1f,
    val bg: Int = BG_BLUR,
    /** 1 = single photo, 2 or 3 = the same photo stacked like a collage. */
    val rows: Int = 1,
    /** Favourites come up twice as often in shuffle. */
    val fav: Boolean = false,
    /** Gallery id when the photo came from a synced album (0 = added by hand). */
    val mid: Long = 0L
) {
    fun file(c: Context) = File(Store.dir(c), "$id.jpg")

    companion object {
        const val BG_BLUR = 0
        const val BG_DARK = 1
        const val BG_COLOR = 2
    }
}

/**
 * Everything the app remembers. The app screen, the wallpaper and album sync all read and write
 * here, so changes are @Synchronized to avoid two of them overwriting each other.
 */
object Store {
    const val PREFS = "auto_wallpaper"
    const val K_PHOTOS = "photos"
    const val K_CURRENT = "current"
    const val K_SHUFFLE = "shuffle"
    const val K_BAG = "shuffle_bag"
    const val K_EVERY = "every"
    const val K_LOCKS = "locks"
    const val K_PAUSED = "paused"
    const val K_ANIMATE = "animate"
    const val K_DIM = "dim"
    const val K_COLORS = "match_colors"
    const val K_FIT_NEW = "fit_new"
    const val K_AUTO_FACE = "auto_face"
    const val K_ALBUM_ID = "album_id"
    const val K_ALBUM_NAME = "album_name"
    const val K_ALBUM_SYNCED = "album_synced_at"
    const val K_EXCLUDED = "album_excluded"

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dir(c: Context) = File(c.filesDir, "photos").apply { mkdirs() }

    // ---- photos ----

    fun photos(c: Context): List<Photo> {
        val raw = prefs(c).getString(K_PHOTOS, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Photo(
                    id = o.getString("id"),
                    zoom = o.optDouble("z", 1.0).toFloat(),
                    cx = o.optDouble("x", 0.5).toFloat(),
                    cy = o.optDouble("y", 0.5).toFloat(),
                    cl = o.optDouble("cl", 0.0).toFloat(),
                    ct = o.optDouble("ct", 0.0).toFloat(),
                    cr = o.optDouble("cr", 1.0).toFloat(),
                    cb = o.optDouble("cb", 1.0).toFloat(),
                    bg = o.optInt("bg", Photo.BG_BLUR),
                    rows = o.optInt("rows", 1).coerceIn(1, 3),
                    fav = o.optBoolean("fav", false),
                    mid = o.optLong("mid", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun savePhotos(c: Context, list: List<Photo>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject()
                    .put("id", it.id)
                    .put("z", it.zoom.toDouble())
                    .put("x", it.cx.toDouble())
                    .put("y", it.cy.toDouble())
                    .put("cl", it.cl.toDouble())
                    .put("ct", it.ct.toDouble())
                    .put("cr", it.cr.toDouble())
                    .put("cb", it.cb.toDouble())
                    .put("bg", it.bg)
                    .put("rows", it.rows)
                    .put("fav", it.fav)
                    .put("mid", it.mid)
            )
        }
        prefs(c).edit().putString(K_PHOTOS, a.toString()).apply()
    }

    fun current(c: Context): Photo? {
        val list = photos(c)
        val id = prefs(c).getString(K_CURRENT, null)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun currentPos(c: Context): Int {
        val cur = current(c) ?: return -1
        return photos(c).indexOfFirst { it.id == cur.id }
    }

    fun setCurrent(c: Context, id: String) {
        prefs(c).edit().putString(K_CURRENT, id).putInt(K_LOCKS, 0).apply()
    }

    @Synchronized
    fun addAll(c: Context, new: List<Photo>) {
        if (new.isNotEmpty()) savePhotos(c, photos(c) + new)
    }

    @Synchronized
    fun update(c: Context, p: Photo) = savePhotos(c, photos(c).map { if (it.id == p.id) p else it })

    @Synchronized
    fun remove(c: Context, id: String, fromSync: Boolean = false) {
        val list = photos(c)
        val pos = list.indexOfFirst { it.id == id }
        if (pos < 0) return
        val removed = list[pos]
        val wasCurrent = current(c)?.id == id
        val rest = list.filterNot { it.id == id }
        savePhotos(c, rest)
        if (wasCurrent && rest.isNotEmpty()) setCurrent(c, rest[pos % rest.size].id)
        File(dir(c), "$id.jpg").delete()
        // Removed by hand from a synced album: don't bring it back on the next sync.
        if (!fromSync && removed.mid != 0L) {
            prefs(c).edit().putStringSet(K_EXCLUDED, excluded(c) + removed.mid.toString()).apply()
        }
    }

    @Synchronized
    fun clear(c: Context) {
        photos(c).forEach { File(dir(c), "${it.id}.jpg").delete() }
        savePhotos(c, emptyList())
        stopAlbum(c)
    }

    // ---- options ----

    fun shuffle(c: Context) = prefs(c).getBoolean(K_SHUFFLE, false)
    fun setShuffle(c: Context, on: Boolean) =
        prefs(c).edit().putBoolean(K_SHUFFLE, on).remove(K_BAG).apply()

    fun paused(c: Context) = prefs(c).getBoolean(K_PAUSED, false)
    fun setPaused(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_PAUSED, on).apply()

    fun animate(c: Context) = prefs(c).getBoolean(K_ANIMATE, true)
    fun setAnimate(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_ANIMATE, on).apply()

    fun dim(c: Context) = prefs(c).getBoolean(K_DIM, false)
    fun setDim(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_DIM, on).apply()

    fun fitNew(c: Context) = prefs(c).getBoolean(K_FIT_NEW, false)
    fun setFitNew(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_FIT_NEW, on).apply()

    fun autoFace(c: Context) = prefs(c).getBoolean(K_AUTO_FACE, true)
    fun setAutoFace(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_AUTO_FACE, on).apply()

    fun matchColors(c: Context) = prefs(c).getBoolean(K_COLORS, false)
    fun setMatchColors(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_COLORS, on).apply()

    /** Change on every lock (1), every 2nd lock, every 5th lock... */
    fun every(c: Context) = prefs(c).getInt(K_EVERY, 1).coerceAtLeast(1)
    fun setEvery(c: Context, n: Int) = prefs(c).edit().putInt(K_EVERY, n).putInt(K_LOCKS, 0).apply()

    // ---- album sync ----

    fun albumId(c: Context): String? = prefs(c).getString(K_ALBUM_ID, null)
    fun albumName(c: Context): String? = prefs(c).getString(K_ALBUM_NAME, null)
    fun albumSyncedAt(c: Context) = prefs(c).getLong(K_ALBUM_SYNCED, 0L)
    fun setAlbumSyncedAt(c: Context, t: Long) = prefs(c).edit().putLong(K_ALBUM_SYNCED, t).apply()
    fun excluded(c: Context): Set<String> = prefs(c).getStringSet(K_EXCLUDED, emptySet()).orEmpty()

    fun setAlbum(c: Context, id: String, name: String) {
        prefs(c).edit()
            .putString(K_ALBUM_ID, id)
            .putString(K_ALBUM_NAME, name)
            .putLong(K_ALBUM_SYNCED, 0L)
            .remove(K_EXCLUDED)
            .apply()
    }

    fun stopAlbum(c: Context) {
        prefs(c).edit()
            .remove(K_ALBUM_ID)
            .remove(K_ALBUM_NAME)
            .remove(K_ALBUM_SYNCED)
            .remove(K_EXCLUDED)
            .apply()
    }

    // ---- rotation ----

    /** Called by the wallpaper each time the screen locks. */
    @Synchronized
    fun onLock(c: Context) {
        if (paused(c)) return
        val locks = prefs(c).getInt(K_LOCKS, 0) + 1
        if (locks >= every(c)) advance(c) // advance() resets the counter
        else prefs(c).edit().putInt(K_LOCKS, locks).apply()
    }

    /**
     * Next photo. With shuffle on it works like a deck of cards: every photo comes up once
     * before any repeats, favourites are in the deck twice, and never the same photo twice in a row.
     */
    @Synchronized
    fun advance(c: Context) {
        val list = photos(c)
        if (list.size <= 1) return
        val curId = current(c)?.id
        if (!shuffle(c)) {
            val cur = list.indexOfFirst { it.id == curId }.coerceAtLeast(0)
            setCurrent(c, list[(cur + 1) % list.size].id)
            return
        }
        val ids = list.map { it.id }.toSet()
        var bag = prefs(c).getString(K_BAG, "").orEmpty()
            .split(",")
            .filter { it in ids && it != curId }
            .toMutableList()
        if (bag.isEmpty()) bag = newDeck(list, curId)
        val next = bag.removeAt(0)
        prefs(c).edit().putString(K_BAG, bag.joinToString(",")).apply()
        setCurrent(c, next)
    }

    private fun newDeck(list: List<Photo>, curId: String?): MutableList<String> {
        val deck = list.flatMap { if (it.fav) listOf(it.id, it.id) else listOf(it.id) }
            .shuffled()
            .toMutableList()
        // Keep copies of a favourite apart, and don't start with what's on screen now.
        for (i in deck.indices) {
            val prev = if (i == 0) curId else deck[i - 1]
            if (deck[i] == prev) {
                val j = (i + 1 until deck.size).firstOrNull { deck[it] != prev && (i == 0 || deck[it] != deck[i - 1]) }
                if (j != null) deck[i] = deck[j].also { deck[j] = deck[i] }
            }
        }
        return deck.filterIndexed { i, id -> !(i == 0 && id == curId) }.toMutableList()
    }
}
