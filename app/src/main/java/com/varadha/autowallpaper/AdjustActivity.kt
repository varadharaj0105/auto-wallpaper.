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

/** Full-screen editor: Position (fit/fill, zoom, move, background) and Crop. */
class AdjustActivity : AppCompatActivity() {

    private lateinit var b: ActivityAdjustBinding
    private lateinit var original: Photo
    private var guidesOn = true
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityAdjustBinding.inflate(layoutInflater)
        setContentView(b.root)

        val id = intent.getStringExtra(EXTRA_ID)
        original = Store.photos(this).firstOrNull { it.id == id } ?: run {
            finish()
            return
        }

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.bottomBar.updatePadding(bottom = bars.bottom + dp(12))
            b.topBar.updatePadding(top = bars.top + dp(8))
            insets
        }
        // In crop mode the photo sits between the hint and the controls.
        val updateInsets = {
            b.crop.setCropInsets(b.hint.bottom.toFloat() + dp(16), b.bottomBar.height.toFloat() + dp(8))
        }
        b.bottomBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateInsets() }
        b.topBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateInsets() }

        b.crop.setPhoto(original)
        b.crop.onChange = { sync() }
        Thread {
            val bmp = Thumbs.decodeFull(original.file(this))
            runOnUiThread {
                b.loading.visibility = View.GONE
                b.crop.bitmap = bmp
                b.crop.setPhoto(original)
                sync()
            }
        }.start()

        // Tabs
        b.tabs.check(R.id.tabPosition)
        b.tabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) setMode(if (checkedId == R.id.tabCrop) CropView.Mode.CROP else CropView.Mode.POSITION)
        }

        // Fit / Fill
        b.chipsSize.setOnCheckedStateChangeListener { _, ids ->
            if (syncing) return@setOnCheckedStateChangeListener
            when (ids.firstOrNull()) {
                R.id.chipFit -> b.crop.fit()
                R.id.chipFill -> b.crop.fill()
                R.id.chipRows2 -> b.crop.setRows(2)
                R.id.chipRows3 -> b.crop.setRows(3)
            }
        }

        // Background
        b.chipsBg.check(
            when (original.bg) {
                Photo.BG_DARK -> R.id.chipBgDark
                Photo.BG_COLOR -> R.id.chipBgColor
                else -> R.id.chipBgBlur
            }
        )
        b.chipsBg.setOnCheckedStateChangeListener { _, ids ->
            if (syncing) return@setOnCheckedStateChangeListener
            b.crop.setBg(
                when (ids.firstOrNull()) {
                    R.id.chipBgDark -> Photo.BG_DARK
                    R.id.chipBgColor -> Photo.BG_COLOR
                    else -> Photo.BG_BLUR
                }
            )
        }

        // Crop shape
        b.chipsAspect.check(R.id.chipFree)
        b.chipsAspect.setOnCheckedStateChangeListener { _, ids ->
            if (syncing) return@setOnCheckedStateChangeListener
            b.crop.setAspect(
                when (ids.firstOrNull()) {
                    R.id.chipScreen -> b.crop.width.toFloat() / b.crop.height.coerceAtLeast(1)
                    R.id.chipSquare -> 1f
                    R.id.chipFourFive -> 4f / 5f
                    else -> null
                }
            )
        }

        b.btnCancel.setOnClickListener { finish() }
        b.btnReset.setOnClickListener {
            if (b.crop.mode == CropView.Mode.CROP) {
                syncing = true
                b.chipsAspect.check(R.id.chipFree)
                syncing = false
                b.crop.resetCrop()
            } else {
                b.crop.resetPosition()
            }
        }
        b.btnGuides.setOnClickListener {
            guidesOn = !guidesOn
            updateGuides()
        }
        b.btnSave.setOnClickListener {
            Store.update(this, b.crop.photo.copy(id = original.id))
            setResult(RESULT_OK)
            finish()
        }
        setMode(CropView.Mode.POSITION)
    }

    private fun setMode(m: CropView.Mode) {
        b.crop.mode = m
        val crop = m == CropView.Mode.CROP
        b.positionOptions.visibility = if (crop) View.GONE else View.VISIBLE
        b.cropOptions.visibility = if (crop) View.VISIBLE else View.GONE
        b.hint.text = if (crop) "Drag the corners or edges · Drag inside to move"
        else "Pinch to zoom · Drag to move · Double-tap to fit"
        updateGuides()
        sync()
    }

    private fun updateGuides() {
        val show = guidesOn && b.crop.mode == CropView.Mode.POSITION
        val v = if (show) View.VISIBLE else View.INVISIBLE
        b.clockGuide.visibility = v
        b.dateGuide.visibility = v
    }

    /** Keeps the Fit/Fill chips in step with pinching. */
    private fun sync() {
        if (b.crop.bitmap == null) return
        syncing = true
        val same = b.crop.fitEqualsFill
        val rows = b.crop.photo.rows
        b.chipFit.isEnabled = !same
        when {
            rows == 2 -> b.chipsSize.check(R.id.chipRows2)
            rows == 3 -> b.chipsSize.check(R.id.chipRows3)
            b.crop.isFit && !same -> b.chipsSize.check(R.id.chipFit)
            b.crop.photo.zoom in 0.99f..1.01f -> b.chipsSize.check(R.id.chipFill)
            else -> b.chipsSize.clearCheck()
        }
        b.sizeHint.text = when {
            rows > 1 -> "Same photo $rows times, like a collage · drag to reposition"
            same -> "This photo already matches your screen shape"
            b.crop.isFit -> "Whole photo visible"
            b.crop.photo.zoom < 1f -> "Custom size"
            b.crop.photo.zoom > 1.01f -> "Zoomed in ${"%.1f".format(b.crop.photo.zoom)}×"
            else -> "Fills the screen"
        }
        syncing = false
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ID = "photo_id"
    }
}
