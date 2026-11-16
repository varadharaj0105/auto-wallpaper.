package com.varadha.autowallpaper

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.color.DynamicColors
import com.varadha.autowallpaper.databinding.ActivityAdjustBinding

/** Full-screen editor: frame the photo exactly as it will sit on the phone. */
class AdjustActivity : AppCompatActivity() {

    private lateinit var b: ActivityAdjustBinding
    private lateinit var photo: Photo
    private var guidesOn = true

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityAdjustBinding.inflate(layoutInflater)
        setContentView(b.root)

        val id = intent.getStringExtra(EXTRA_ID)
        photo = Store.photos(this).firstOrNull { it.id == id } ?: run {
            finish()
            return
        }

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.bottomBar.updatePadding(bottom = bars.bottom + dp(16))
            b.topBar.updatePadding(top = bars.top + dp(8))
            insets
        }

        b.crop.setFraming(photo.zoom, photo.cx, photo.cy)
        Thread {
            val bmp = Thumbs.decodeFull(photo.file(this))
            runOnUiThread {
                b.loading.visibility = View.GONE
                b.crop.bitmap = bmp
                b.crop.setFraming(photo.zoom, photo.cx, photo.cy)
            }
        }.start()

        b.btnCancel.setOnClickListener { finish() }
        b.btnReset.setOnClickListener { b.crop.setFraming(1f, 0.5f, 0.5f) }
        b.btnGuides.setOnClickListener {
            guidesOn = !guidesOn
            val v = if (guidesOn) View.VISIBLE else View.INVISIBLE
            b.clockGuide.visibility = v
            b.dateGuide.visibility = v
            b.hint.visibility = v
        }
        b.btnSave.setOnClickListener {
            Store.update(this, photo.copy(zoom = b.crop.zoom, cx = b.crop.cx, cy = b.crop.cy))
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ID = "photo_id"
    }
}
