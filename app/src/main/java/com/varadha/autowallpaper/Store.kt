package com.varadha.autowallpaper

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlin.random.Random

/** Remembers the picked photos, which one is showing, and the user's options. */
object Store {
    private const val PREFS = "auto_wallpaper"
    private const val KEY_URIS = "uris"
    private const val KEY_INDEX = "index"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_EVERY = "every"
    private const val KEY_LOCKS = "locks"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun uris(c: Context): List<Uri> =
        prefs(c).getString(KEY_URIS, "").orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
            .map(Uri::parse)

    private fun saveUris(c: Context, uris: List<Uri>) {
        prefs(c).edit().putString(KEY_URIS, uris.joinToString("\n")).apply()
    }

    fun index(c: Context): Int {
        val n = uris(c).size
        return if (n == 0) 0 else prefs(c).getInt(KEY_INDEX, 0).coerceIn(0, n - 1)
    }

    fun setIndex(c: Context, i: Int) {
        prefs(c).edit().putInt(KEY_INDEX, i).apply()
    }

    /** Adds photos to the end of the list, skipping duplicates. Returns how many were new. */
    fun add(c: Context, newUris: List<Uri>): Int {
        val current = uris(c)
        val fresh = newUris.filter { it !in current }.distinct()
        saveUris(c, current + fresh)
        return fresh.size
    }

    fun remove(c: Context, uri: Uri) {
        val list = uris(c)
        val pos = list.indexOf(uri)
        if (pos < 0) return
        val idx = index(c)
        val newList = list - uri
        saveUris(c, newList)
        val newIdx = when {
            newList.isEmpty() -> 0
            pos < idx -> idx - 1
            idx >= newList.size -> 0
            else -> idx
        }
        setIndex(c, newIdx)
        release(c, uri)
    }

    fun clear(c: Context) {
        uris(c).forEach { release(c, it) }
        saveUris(c, emptyList())
        setIndex(c, 0)
    }

    private fun release(c: Context, uri: Uri) {
        runCatching {
            c.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shuffle(c: Context) = prefs(c).getBoolean(KEY_SHUFFLE, false)
    fun setShuffle(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_SHUFFLE, on).apply()

    /** Change on every lock (1), every 2nd lock, every 5th lock... */
    fun every(c: Context) = prefs(c).getInt(KEY_EVERY, 1).coerceAtLeast(1)
    fun setEvery(c: Context, n: Int) =
        prefs(c).edit().putInt(KEY_EVERY, n).putInt(KEY_LOCKS, 0).apply()

    /** Called by the wallpaper each time the screen locks. */
    fun onLock(c: Context) {
        val locks = prefs(c).getInt(KEY_LOCKS, 0) + 1
        if (locks >= every(c)) {
            prefs(c).edit().putInt(KEY_LOCKS, 0).apply()
            advance(c)
        } else {
            prefs(c).edit().putInt(KEY_LOCKS, locks).apply()
        }
    }

    /** Moves to the next photo (or a random different one when shuffle is on). */
    fun advance(c: Context) {
        val n = uris(c).size
        if (n <= 1) return
        val cur = index(c)
        val next = if (shuffle(c)) {
            var r = Random.nextInt(n - 1)
            if (r >= cur) r++ // never repeat the same photo twice in a row
            r
        } else {
            (cur + 1) % n
        }
        setIndex(c, next)
    }
}
