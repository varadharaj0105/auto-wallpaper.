package com.varadha.autowallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Two buttons: pick the images, then set the live wallpaper. No libraries needed. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply { textSize = 16f }
        val pick = Button(this).apply {
            text = "1. Pick wallpapers"
            setOnClickListener { pickImages() }
        }
        val apply = Button(this).apply {
            text = "2. Set as wallpaper (Home + Lock)"
            setOnClickListener { applyWallpaper() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(pick)
            addView(apply)
        })
        refresh()
    }

    private fun refresh() {
        val n = Store.uris(this).size
        status.text = if (n == 0) "No images picked yet."
        else "$n image(s) picked. The wallpaper changes every time you lock the phone."
    }

    private fun pickImages() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Plain Activity API, fine here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return

        val picked = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) picked += clip.getItemAt(i).uri
        } else {
            data.data?.let { picked += it }
        }
        if (picked.isEmpty()) return

        // Drop access to the old set, keep access to the new set across reboots.
        contentResolver.persistedUriPermissions.forEach {
            runCatching {
                contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        picked.forEach {
            runCatching {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        Store.save(this, picked)
        refresh()
    }

    private fun applyWallpaper() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, AutoWallpaperService::class.java)
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    companion object {
        private const val REQ_PICK = 1
    }
}
