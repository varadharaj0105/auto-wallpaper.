package com.varadha.autowallpaper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * One wallpaper photo. The image itself is a copy kept inside the app (so it keeps working
 * even if the original is moved or deleted). zoom/cx/cy is how the user framed it:
 * zoom 1 = fill the screen, cx/cy = which point of the photo sits in the screen centre (0..1).
 */
data class Photo(
    val id: String,
    val zoom: Float = 1f,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f
) {
    fun file(c: Context) = File(Store.dir(c), "$id.jpg")
}

/** Everything the app remembers. Both the app screen and the wallpaper read from here. */
object Store {
    const val PREFS = "auto_wallpaper"
    const val K_PHOTOS = "photos"
    const val K_CURRENT = "current"
    const val K_SHUFFLE = "shuffle"
    const val K_EVERY = "every"
    const val K_LOCKS = "locks"
    const val K_PAUSED = "paused"
    const val K_ANIMATE = "animate"
    const val K_DIM = "dim"
    const val K_COLORS = "match_colors"

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
                    o.getString("id"),
                    o.optDouble("z", 1.0).toFloat(),
                    o.optDouble("x", 0.5).toFloat(),
                    o.optDouble("y", 0.5).toFloat()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePhotos(c: Context, list: List<Photo>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject()
                    .put("id", it.id)
                    .put("z", it.zoom.toDouble())
                    .put("x", it.cx.toDouble())
                    .put("y", it.cy.toDouble())
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

    fun addAll(c: Context, new: List<Photo>) = savePhotos(c, photos(c) + new)

    fun update(c: Context, p: Photo) = savePhotos(c, photos(c).map { if (it.id == p.id) p else it })

    fun remove(c: Context, id: String) {
        val list = photos(c)
        val pos = list.indexOfFirst { it.id == id }
        if (pos < 0) return
        val wasCurrent = current(c)?.id == id
        val rest = list.filterNot { it.id == id }
        savePhotos(c, rest)
        if (wasCurrent && rest.isNotEmpty()) setCurrent(c, rest[pos % rest.size].id)
        File(dir(c), "$id.jpg").delete()
    }

    fun clear(c: Context) {
        photos(c).forEach { File(dir(c), "${it.id}.jpg").delete() }
        savePhotos(c, emptyList())
    }

    // ---- options ----

    fun shuffle(c: Context) = prefs(c).getBoolean(K_SHUFFLE, false)
    fun setShuffle(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_SHUFFLE, on).apply()

    fun paused(c: Context) = prefs(c).getBoolean(K_PAUSED, false)
    fun setPaused(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_PAUSED, on).apply()

    fun animate(c: Context) = prefs(c).getBoolean(K_ANIMATE, true)
    fun setAnimate(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_ANIMATE, on).apply()

    fun dim(c: Context) = prefs(c).getBoolean(K_DIM, false)
    fun setDim(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_DIM, on).apply()

    fun matchColors(c: Context) = prefs(c).getBoolean(K_COLORS, false)
    fun setMatchColors(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_COLORS, on).apply()

    /** Change on every lock (1), every 2nd lock, every 5th lock... */
    fun every(c: Context) = prefs(c).getInt(K_EVERY, 1).coerceAtLeast(1)
    fun setEvery(c: Context, n: Int) = prefs(c).edit().putInt(K_EVERY, n).putInt(K_LOCKS, 0).apply()

    // ---- rotation ----

    /** Called by the wallpaper each time the screen locks. */
    fun onLock(c: Context) {
        if (paused(c)) return
        val locks = prefs(c).getInt(K_LOCKS, 0) + 1
        if (locks >= every(c)) advance(c) // advance() resets the counter
        else prefs(c).edit().putInt(K_LOCKS, locks).apply()
    }

    /** Next photo, or a random different one when shuffle is on. */
    fun advance(c: Context) {
        val list = photos(c)
        val n = list.size
        if (n <= 1) return
        val cur = currentPos(c).coerceAtLeast(0)
        val next = if (shuffle(c)) {
            var r = Random.nextInt(n - 1)
            if (r >= cur) r++ // never the same photo twice in a row
            r
        } else {
            (cur + 1) % n
        }
        setCurrent(c, list[next].id)
    }
}
