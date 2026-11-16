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

    /** Gallery permission, only asked when you turn on album sync. */
    private val askPhotos =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) chooseAlbum()
            else snack("Album sync needs access to your photos. Choose “Allow all”.")
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
        b.rowAlbum.root.setOnClickListener { albumMenu() }

        b.versionText.text = "Auto Wallpaper · version 1.${Updater.currentBuild(this)}"
        setupUpdates()

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
        quietSync()
        // Came back from the "Install unknown apps" switch: carry on with the update.
        if (pendingUpdateAfterPermission && Updater.canInstall(this)) {
            pendingUpdateAfterPermission = false
            startUpdate()
        }
    }

    override fun onDestroy() {
        Updater.onInstallResult = null
        super.onDestroy()
    }

    // ---------------- album sync ----------------

    private fun renderAlbum() {
        val r = b.rowAlbum
        r.icon.setImageResource(R.drawable.ic_folder_sync)
        r.switchView.visibility = View.GONE
        val name = Store.albumName(this)
        if (name == null) {
            r.title.text = "Sync an album"
            r.subtitle.text = "Pick a gallery album. New photos in it join automatically."
        } else {
            val count = Store.photos(this).count { it.mid != 0L }
            r.title.text = "Syncing “$name”"
            r.subtitle.text = "$count photo${if (count == 1) "" else "s"} from this album · tap for options"
        }
    }

    private fun albumMenu() {
        val name = Store.albumName(this)
        if (name == null) {
            startAlbumPick()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Album “$name”")
            .setItems(arrayOf("Sync now", "Choose a different album", "Stop syncing")) { _, which ->
                when (which) {
                    0 -> runSync(loud = true)
                    1 -> startAlbumPick()
                    2 -> confirmStopAlbum()
                }
            }
            .show()
    }

    private fun confirmStopAlbum() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Stop syncing?")
            .setMessage("Keep the photos already added, or remove the ones that came from the album?")
            .setNegativeButton("Keep photos") { _, _ ->
                // They become normal photos.
                Store.savePhotos(this, Store.photos(this).map { it.copy(mid = 0L) })
                Store.stopAlbum(this)
                render()
            }
            .setPositiveButton("Remove them") { _, _ ->
                Store.photos(this).filter { it.mid != 0L }.forEach { Store.remove(this, it.id, fromSync = true) }
                Store.stopAlbum(this)
                render()
            }
            .show()
    }

    private fun startAlbumPick() {
        if (AlbumSync.hasPermission(this)) chooseAlbum() else askPhotos.launch(AlbumSync.permission)
    }

    private fun chooseAlbum() {
        Thread {
            val albums = AlbumSync.albums(applicationContext)
            runOnUiThread {
                if (albums.isEmpty()) {
                    snack("No albums found")
                    return@runOnUiThread
                }
                val labels = albums.map { "${it.name}  ·  ${it.count}" }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle("Pick an album")
                    .setItems(labels) { _, which ->
                        val a = albums[which]
                        if (Store.albumId(this) != null && Store.albumId(this) != a.id) {
                            // Switching albums: photos from the old one leave.
                            Store.photos(this).filter { it.mid != 0L }
                                .forEach { Store.remove(this, it.id, fromSync = true) }
                        }
                        Store.setAlbum(this, a.id, a.name)
                        render()
                        runSync(loud = true)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    private fun runSync(loud: Boolean) {
        if (loud) {
            setBusy(true)
            snack("Syncing album… new photos appear as they're ready")
        }
        Thread {
            val (added, removed) = AlbumSync.sync(applicationContext)
            runOnUiThread {
                if (loud) setBusy(false)
                render()
                if (loud || added + removed > 0) {
                    snack(
                        when {
                            added == 0 && removed == 0 -> "Album is up to date"
                            removed == 0 -> "$added new photo${if (added == 1) "" else "s"} from the album"
                            added == 0 -> "$removed photo${if (removed == 1) "" else "s"} removed (deleted from album)"
                            else -> "$added added, $removed removed"
                        }
                    )
                }
            }
        }.start()
    }

    /** Light check every time the app opens. */
    private fun quietSync() {
        if (Store.albumId(this) != null && AlbumSync.hasPermission(this)) runSync(loud = false)
    }

    // ---------------- faces ----------------

    private fun frameAllFaces() {
        val photos = Store.photos(this).filter { it.rows == 1 }
        if (photos.isEmpty()) return
        setBusy(true)
        snack("Finding faces in ${photos.size} photos…")
        Thread {
            var framed = 0
            photos.forEach { p ->
                Faces.autoFrame(applicationContext, p)?.let {
                    // Keep anything changed meanwhile (favourite, background...), only move the framing.
                    val latest = Store.photos(applicationContext).firstOrNull { x -> x.id == p.id } ?: return@let
                    Store.update(applicationContext, latest.copy(zoom = it.zoom, cx = it.cx, cy = it.cy, rows = 1))
                    framed++
                }
            }
            runOnUiThread {
                setBusy(false)
                render()
                snack(if (framed == 0) "No faces found" else "Framed $framed photo${if (framed == 1) "" else "s"} around faces")
            }
        }.start()
    }

    // ---------------- in-app updates ----------------

    private var pendingUpdateAfterPermission = false
    private var updating = false

    private fun setupUpdates() {
        Updater.cleanUp(this) // leftover download from the last update
        Updater.onInstallResult = { ok, message ->
            updating = false
            if (ok) {
                b.updateText.text = "Updated!"
            } else {
                showUpdateIdle()
                snack(message ?: "Update failed")
            }
        }
        b.btnUpdate.setOnClickListener { startUpdate() }
        Updater.check(this) { release ->
            b.updateText.text = "Version 1.${release.build} is available"
            if (release.notes.isNotBlank()) {
                b.updateNotes.text = release.notes
                b.updateNotes.visibility = View.VISIBLE
            }
            b.updateCard.visibility = View.VISIBLE
        }
    }

    private fun showUpdateIdle() {
        b.btnUpdate.isEnabled = true
        b.btnUpdate.text = "Update"
        b.updateProgress.visibility = View.GONE
    }

    private fun startUpdate() {
        if (updating) return
        // Android asks once: allow this app to install its own updates.
        if (!Updater.canInstall(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow updates")
                .setMessage(
                    "To update itself, Auto Wallpaper needs permission to install apps.\n\n" +
                        "On the next screen, turn on “Allow from this source”, then come back."
                )
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open settings") { _, _ ->
                    pendingUpdateAfterPermission = true
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .show()
            return
        }
        updating = true
        b.btnUpdate.isEnabled = false
        b.btnUpdate.text = "0%"
        b.updateText.text = "Downloading update…"
        b.updateProgress.visibility = View.INVISIBLE
        b.updateProgress.isIndeterminate = false
        b.updateProgress.progress = 0
        b.updateProgress.visibility = View.VISIBLE
        Updater.downloadAndInstall(
            this,
            onProgress = { pct ->
                b.updateProgress.setProgressCompat(pct, true)
                b.btnUpdate.text = "$pct%"
            },
            onInstalling = {
                b.updateText.text = "Installing… the app will restart"
                b.btnUpdate.text = "…"
                b.updateProgress.visibility = View.INVISIBLE
                b.updateProgress.isIndeterminate = true
                b.updateProgress.visibility = View.VISIBLE
            },
            onError = { msg ->
                updating = false
                b.updateText.text = "Update failed"
                showUpdateIdle()
                snack("Couldn't download: $msg")
            }
        )
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
        if (p.mid != 0L) sb.sheetSubtitle.text = "${sb.sheetSubtitle.text} · from “${Store.albumName(this) ?: "album"}”"

        sb.actionFav.text = if (p.fav) "Remove from favourites" else "Add to favourites"
        sb.actionFav.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (p.fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border, 0, 0, 0
        )
        sb.actionFav.setOnClickListener {
            sheet.dismiss()
            Store.update(this, p.copy(fav = !p.fav))
            render()
            snack(if (p.fav) "Removed from favourites" else "Favourite: shows up twice as often in shuffle")
        }

        sb.actionFace.setOnClickListener {
            sheet.dismiss()
            setBusy(true)
            Thread {
                val framed = Faces.autoFrame(applicationContext, p)
                if (framed != null) Store.update(applicationContext, framed)
                runOnUiThread {
                    setBusy(false)
                    render()
                    snack(if (framed != null) "Framed around faces" else "No faces found in this photo")
                }
            }.start()
        }
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
            b.rowAutoFace, R.drawable.ic_face, "Auto-frame faces",
            "New photos are placed so faces sit below the clock", Store.autoFace(this)
        ) { Store.setAutoFace(this, it) }
        b.rowFrameAll.icon.setImageResource(R.drawable.ic_auto_awesome)
        b.rowFrameAll.title.text = "Frame all photos around faces"
        b.rowFrameAll.subtitle.text = "Re-position every photo you already have"
        b.rowFrameAll.switchView.visibility = View.GONE
        b.rowFrameAll.root.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Frame all photos?")
                .setMessage("Photos with faces get re-positioned so faces sit below the clock. Photos without faces stay as they are.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Frame all") { _, _ -> frameAllFaces() }
                .show()
        }
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
        b.rowShuffle.subtitle.text = "Every photo once before any repeats · favourites twice as often"
        renderAlbum()
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
