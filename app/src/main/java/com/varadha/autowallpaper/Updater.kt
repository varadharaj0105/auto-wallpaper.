package com.varadha.autowallpaper

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer build. Runs only while the app screen is open,
 * never in the background, so it costs nothing the rest of the time.
 */
object Updater {
    private const val REPO = "varadharaj0105/auto-wallpaper."
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    const val DOWNLOAD_URL = "https://github.com/$REPO/releases/latest/download/AutoWallpaper.apk"

    /** Calls [onNewer] on the main thread with the new build number, only if one exists. */
    fun check(context: Context, onNewer: (Int) -> Unit) {
        val current = currentBuild(context)
        val main = Handler(Looper.getMainLooper())
        Thread {
            val latest = runCatching { fetchLatestBuild() }.getOrNull() ?: return@Thread
            if (latest > current) main.post { onNewer(latest) }
        }.start()
    }

    fun currentBuild(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    }

    /** Release tags look like "build-7"; returns 7. */
    private fun fetchLatestBuild(): Int? {
        val conn = URL(LATEST_API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body).optString("tag_name").removePrefix("build-").toIntOrNull()
        } finally {
            conn.disconnect()
        }
    }
}
