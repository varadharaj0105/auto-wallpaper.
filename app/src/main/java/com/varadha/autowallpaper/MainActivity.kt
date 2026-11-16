package com.varadha.autowallpaper

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.varadha.autowallpaper.databinding.ActivityMainBinding
import com.varadha.autowallpaper.databinding.RowSwitchBinding
import com.varadha.autowallpaper.databinding.SheetPhotoBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val adapter = WallAdapter(::showPhotoSheet)

    /** Android's photo picker: looks like the gallery, not a file browser. */
    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
            if (uris.isNotEmpty()) importUris(uris)
        }

    private val adjust =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) snack("Position saved")
            render()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this) // Material You colours
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupGrid()

        b.fabAdd.setOnClickListener { pick() }
        b.btnEmptyAdd.setOnClickListener { pick() }
        b.btnApply.setOnClickListener { applyWallpaper() }
        b.btnNext.setOnClickListener {
            Store.advance(this)
            render()
        }
        b.btnAdjust.setOnClickListener { Store.current(this)?.let { openAdjust(it.id) } }
        b.heroImage.setOnClickListener { Store.current(this)?.let { openAdjust(it.id) } }
        b.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Remove all photos?")
                .setMessage("Your gallery is not touched. Only this list is cleared.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove all") { _, _ ->
                    Store.clear(this)
                    render()
                }
                .show()
        }

        setupSettings()

        b.versionText.text = "Auto Wallpaper · version 1.${Updater.currentBuild(this)}"
        b.btnUpdate.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.DOWNLOAD_URL)))
        }
        Updater.check(this) { latest ->
            b.updateText.text = "Version 1.$latest is ready to install"
            b.updateCard.visibility = View.VISIBLE
        }

        // Photos picked in older versions were links to the gallery. Copy them in once.
        if (Importer.hasLegacy(this)) {
            setBusy(true)
            Thread {
                Importer.migrateLegacy(applicationContext)
                runOnUiThread {
                    setBusy(false)
                    render()
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        render() // e.g. becomes "Active" after returning from the wallpaper picker
    }

    // ---------------- grid ----------------

    private fun setupGrid() {
        b.grid.layoutManager = GridLayoutManager(this, 3)
        b.grid.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    vh?.itemView?.animate()?.scaleX(1.06f)?.scaleY(1.06f)?.setDuration(120)?.start()
                }
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                // Save the new order, keeping each photo's latest framing.
                val latest = Store.photos(this@MainActivity).associateBy { it.id }
                Store.savePhotos(this@MainActivity, adapter.items.mapNotNull { latest[it.id] })
                renderHero()
            }
        }).attachToRecyclerView(b.grid)
    }

    private fun showPhotoSheet(position: Int) {
        val p = adapter.items.getOrNull(position) ?: return
        val sheet = BottomSheetDialog(this)
        val sb = SheetPhotoBinding.inflate(layoutInflater)
        Thumbs.load(sb.preview, p, 200)
        val isCurrent = Store.current(this)?.id == p.id
        sb.sheetTitle.text = "Photo ${position + 1}"
        sb.sheetSubtitle.text = if (isCurrent) "On your screen now" else "In your rotation"
        sb.actionShow.visibility = if (isCurrent) View.GONE else View.VISIBLE
        sb.actionShow.setOnClickListener {
            sheet.dismiss()
            Store.setCurrent(this, p.id)
            render()
            snack("Wallpaper changed")
        }
        // Quick Fit / Fill switch for photos that don't match the screen shape.
        val size = Importer.imageSize(p.file(this))
        val rw = size?.let { (p.cr - p.cl) * it.first }
        val rh = size?.let { (p.cb - p.ct) * it.second }
        val fitZoom = if (rw != null && rh != null) {
            val (sw, sh) = Importer.screenSize(this)
            Framing.minZoom(rw, rh, sw, sh)
        } else 1f
        val isFit = p.rows == 1 && kotlin.math.abs(p.zoom - fitZoom) < 0.01f
        if (fitZoom > 0.99f) {
            sb.actionFit.visibility = View.GONE // already the screen's shape
        } else {
            sb.actionFit.text = if (isFit) "Fill the screen" else "Fit whole photo"
        }
        sb.actionFit.setOnClickListener {
            sheet.dismiss()
            Store.update(
                this,
                if (isFit) p.copy(rows = 1, zoom = 1f)
                else p.copy(rows = 1, zoom = fitZoom, cx = 0.5f, cy = 0.5f)
            )
            render()
            snack(if (isFit) "Fills the screen" else "Whole photo visible")
        }
        sb.actionLayout.setOnClickListener {
            sheet.dismiss()
            val options = arrayOf("Single photo", "2× layout (photo twice)", "3× layout (photo three times)")
            MaterialAlertDialogBuilder(this)
                .setTitle("Layout")
                .setSingleChoiceItems(options, (p.rows - 1).coerceIn(0, 2)) { d, which ->
                    d.dismiss()
                    Store.update(this, p.copy(rows = which + 1, zoom = 1f, cx = 0.5f, cy = 0.5f))
                    render()
                    snack(if (which == 0) "Single photo" else "${which + 1}× layout")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        sb.actionAdjust.setOnClickListener {
            sheet.dismiss()
            openAdjust(p.id)
        }
        sb.actionRemove.setOnClickListener {
            sheet.dismiss()
            Store.remove(this, p.id)
            render()
            snack("Removed. Your gallery copy is untouched.")
        }
        sheet.setContentView(sb.root)
        sheet.show()
    }

    private fun openAdjust(id: String) {
        adjust.launch(Intent(this, AdjustActivity::class.java).putExtra(AdjustActivity.EXTRA_ID, id))
    }

    // ---------------- adding photos ----------------

    private fun pick() {
        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun importUris(uris: List<Uri>) {
        setBusy(true)
        snack("Adding ${uris.size} photo${if (uris.size == 1) "" else "s"}…")
        Thread {
            val photos = Importer.import(applicationContext, uris)
            Store.addAll(applicationContext, photos)
            runOnUiThread {
                setBusy(false)
                render()
                snack(
                    when (photos.size) {
                        0 -> "Couldn't read those photos"
                        1 -> "1 photo added"
                        else -> "${photos.size} photos added"
                    }
                )
            }
        }.start()
    }

    private fun setBusy(busy: Boolean) {
        b.progress.visibility = if (busy) View.VISIBLE else View.GONE
        b.fabAdd.isEnabled = !busy
        b.btnEmptyAdd.isEnabled = !busy
    }

    // ---------------- settings ----------------

    private fun setupSettings() {
        row(
            b.rowAuto, R.drawable.ic_autorenew, "Auto-change",
            "Change the wallpaper when you lock the phone", !Store.paused(this)
        ) {
            Store.setPaused(this, !it)
            render()
        }

        when (Store.every(this)) {
            2 -> b.chipEvery2
            5 -> b.chipEvery5
            10 -> b.chipEvery10
            else -> b.chipEvery1
        }.isChecked = true
        b.chipsEvery.setOnCheckedStateChangeListener { _, ids ->
            Store.setEvery(
                this, when (ids.firstOrNull()) {
                    R.id.chipEvery2 -> 2
                    R.id.chipEvery5 -> 5
                    R.id.chipEvery10 -> 10
                    else -> 1
                }
            )
            renderHero()
        }

        row(
            b.rowShuffle, R.drawable.ic_shuffle, "Shuffle",
            "Random order, never the same photo twice in a row", Store.shuffle(this)
        ) {
            Store.setShuffle(this, it)
            renderHero()
        }
        row(
            b.rowFitNew, R.drawable.ic_fit_screen, "Fit new photos",
            "Show the whole photo instead of filling the screen", Store.fitNew(this)
        ) { Store.setFitNew(this, it) }
        row(
            b.rowAnimate, R.drawable.ic_auto_awesome, "Wake-up animation",
            "Gentle zoom when you turn the screen on", Store.animate(this)
        ) { Store.setAnimate(this, it) }
        row(
            b.rowDim, R.drawable.ic_dark_mode, "Dim in dark mode",
            "Easier on the eyes at night", Store.dim(this)
        ) { Store.setDim(this, it) }
        row(
            b.rowColors, R.drawable.ic_palette, "Match system colours",
            "Your phone's theme colours follow the current photo", Store.matchColors(this)
        ) { Store.setMatchColors(this, it) }
        if (Build.VERSION.SDK_INT < 31) b.rowColors.root.visibility = View.GONE
    }

    private fun row(
        r: RowSwitchBinding,
        @DrawableRes icon: Int,
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        r.icon.setImageResource(icon)
        r.title.text = title
        r.subtitle.text = subtitle
        r.switchView.isChecked = checked
        r.switchView.setOnCheckedChangeListener { _, on -> onChange(on) }
        r.root.setOnClickListener { r.switchView.toggle() }
    }

    // ---------------- render ----------------

    private fun render() {
        val photos = Store.photos(this)
        val empty = photos.isEmpty()
        adapter.submit(photos, Store.current(this)?.id)
        b.grid.visibility = if (empty) View.GONE else View.VISIBLE
        b.gridHint.visibility = if (empty) View.GONE else View.VISIBLE
        b.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        b.btnClear.visibility = if (empty) View.GONE else View.VISIBLE
        b.gridTitle.text = if (empty) "Your photos" else "Your photos · ${photos.size}"
        b.everyBlock.alpha = if (Store.paused(this)) 0.4f else 1f
        renderHero()
    }

    private fun renderHero() {
        val photos = Store.photos(this)
        val cur = Store.current(this)
        val pos = Store.currentPos(this)
        val active = isActive()
        val paused = Store.paused(this)

        if (cur == null) {
            b.heroImage.setBitmap(null)
            b.heroPlaceholder.visibility = View.VISIBLE
        } else {
            b.heroPlaceholder.visibility = View.GONE
            Thumbs.load(b.heroImage, cur, 480)
        }

        when {
            !active -> {
                b.statusChip.text = "Not set yet"
                b.statusChip.setChipIconResource(R.drawable.ic_info)
            }
            paused -> {
                b.statusChip.text = "Paused"
                b.statusChip.setChipIconResource(R.drawable.ic_pause)
            }
            else -> {
                b.statusChip.text = "Active"
                b.statusChip.setChipIconResource(R.drawable.ic_check)
            }
        }

        b.heroTitle.text = if (cur == null) "No photos yet" else "Photo ${pos + 1} of ${photos.size}"
        val every = Store.every(this)
        val howOften = if (every == 1) "every time you lock your phone" else "every $every locks"
        b.heroSubtitle.text = when {
            cur == null -> "Add your photos and they'll take turns on your Home and lock screen."
            !active -> "Tap Set as wallpaper, then choose “Home screen and lock screen”."
            paused -> "Auto-change is paused. Turn it on in Settings below."
            photos.size == 1 -> "Add more photos to start rotating."
            else -> "Changes $howOften" + if (Store.shuffle(this)) ", in random order." else "."
        }

        b.btnApply.visibility = if (!active && cur != null) View.VISIBLE else View.GONE
        b.heroActions.visibility = if (cur == null) View.GONE else View.VISIBLE
        b.btnNext.isEnabled = photos.size > 1
    }

    private fun isActive(): Boolean =
        WallpaperManager.getInstance(this).wallpaperInfo?.component ==
            ComponentName(this, AutoWallpaperService::class.java)

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

    private fun snack(text: String) {
        Snackbar.make(b.coordinator, text, Snackbar.LENGTH_SHORT).setAnchorView(b.fabAdd).show()
    }

    companion object {
        private const val MAX_PICK = 100
    }
}
