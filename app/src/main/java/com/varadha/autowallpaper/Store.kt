package com.varadha.autowallpaper

import android.content.Context
import android.net.Uri

/** Remembers the picked images and which one is showing. */
object Store {
    private const val PREFS = "auto_wallpaper"
    private const val KEY_URIS = "uris"
    private const val KEY_INDEX = "index"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(c: Context, uris: List<Uri>) {
        prefs(c).edit()
            .putString(KEY_URIS, uris.joinToString("\n"))
            .putInt(KEY_INDEX, 0)
            .apply()
    }

    fun uris(c: Context): List<Uri> =
        prefs(c).getString(KEY_URIS, "").orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
            .map(Uri::parse)

    fun index(c: Context): Int = prefs(c).getInt(KEY_INDEX, 0)

    fun advance(c: Context) {
        val count = uris(c).size
        if (count > 0) prefs(c).edit().putInt(KEY_INDEX, (index(c) + 1) % count).apply()
    }
}
