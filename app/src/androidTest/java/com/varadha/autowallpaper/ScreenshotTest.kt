package com.varadha.autowallpaper

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Not a pass/fail test: opens every screen with sample photos and saves screenshots,
 * so the design can be checked without a phone. Run on GitHub by the "Screenshots" workflow.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val out get() = File(ctx.filesDir, "shots").apply { mkdirs() }

    @Before
    fun seed() {
        Store.prefs(ctx).edit().clear().commit()
        Store.dir(ctx).listFiles()?.forEach { it.delete() }
        val photos = (0 until 10).map { i ->
            val id = "sample$i"
            val landscape = i % 3 == 0
            val bmp = sampleImage(i, if (landscape) 1600 else 1080, if (landscape) 1000 else 1920)
            FileOutputStream(File(Store.dir(ctx), "$id.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            Photo(
                id = id,
                fav = i == 1 || i == 4,
                mid = if (i >= 7) 1000L + i else 0L,
                rows = if (i == 3) 3 else 1
            )
        }
        Store.savePhotos(ctx, photos)
        Store.setAlbum(ctx, "fake", "Wallpapers")
        Store.setShuffle(ctx, true)
        Store.prefs(ctx).edit().commit()
    }

    @Test
    fun screens() {
        capture("light")
        inst.runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
        capture("dark")
        inst.runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO) }
    }

    private fun capture(theme: String) {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            shot("${theme}_01_home")
            sc.onActivity { it.findViewById<View>(R.id.home).scrollTo(0, 10_000) }
            shot("${theme}_02_home_bottom")

            sc.onActivity { it.showTab(R.id.navPhotos) }
            shot("${theme}_03_photos")

            sc.onActivity { it.showPhotoSheet(1) }
            shot("${theme}_04_photo_sheet")
            back()

            sc.onActivity {
                it.showAlbumSheet(
                    listOf(
                        AlbumSync.Album("1", "Camera", 483),
                        AlbumSync.Album("2", "WhatsApp Images", 322),
                        AlbumSync.Album("3", "Screenshots", 113),
                        AlbumSync.Album("4", "Wallpapers", 58),
                        AlbumSync.Album("5", "Snapchat", 45),
                        AlbumSync.Album("6", "Download", 10)
                    )
                )
            }
            shot("${theme}_05_albums")
            back()

            sc.onActivity { it.showTab(R.id.navSettings) }
            shot("${theme}_06_settings")
            sc.onActivity { it.findViewById<View>(R.id.settings).scrollTo(0, 10_000) }
            shot("${theme}_07_settings_bottom")
        }

        val edit = Intent(ctx, AdjustActivity::class.java).putExtra(AdjustActivity.EXTRA_ID, "sample3")
        ActivityScenario.launch<AdjustActivity>(edit).use {
            shot("${theme}_08_editor")
        }
    }

    private fun back() {
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        Thread.sleep(600)
    }

    private fun shot(name: String) {
        Thread.sleep(1800) // let images load and animations settle
        inst.waitForIdleSync()
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A simple landscape-style picture: sky gradient, sun, hills. Different colours per index. */
    private fun sampleImage(i: Int, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = (i * 37f) % 360f
        val top = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.95f))
        val bottom = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.65f, 0.55f))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.argb(230, 255, 240, 200)
        c.drawCircle(w * 0.68f, h * 0.32f, minOf(w, h) * 0.12f, p)
        p.color = Color.HSVToColor(floatArrayOf((hue + 120f) % 360f, 0.5f, 0.35f))
        val hills = Path().apply {
            moveTo(0f, h * 0.72f)
            quadTo(w * 0.25f, h * 0.55f, w * 0.5f, h * 0.7f)
            quadTo(w * 0.75f, h * 0.82f, w.toFloat(), h * 0.62f)
            lineTo(w.toFloat(), h.toFloat())
            lineTo(0f, h.toFloat())
            close()
        }
        c.drawPath(hills, p)
        return bmp
    }
}
