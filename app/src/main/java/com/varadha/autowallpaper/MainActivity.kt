package com.varadha.autowallpaper

import android.app.StatusBarManager
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.varadha.autowallpaper.databinding.ActivityMainBinding
import com.varadha.autowallpaper.databinding.ItemAlbumBinding
import com.varadha.autowallpaper.databinding.ItemUpnextBinding
import com.varadha.autowallpaper.databinding.RowSwitchBinding
import com.varadha.autowallpaper.databinding.SheetAlbumsBinding
import com.varadha.autowallpaper.databinding.SheetPhotoBinding
import kotlin.math.abs

/**
 * The app screen: three tabs.
 *  Home     - what's on screen now, quick actions, what's coming up.
 *  Photos   - the rotation, filters, album sync, add photos.
 *  Settings - grouped options.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val grid = WallAdapter(::showPhotoSheet)
    private val upNext = UpNextAdapter { showPhotoSheetFor(it) }
    private var filter = Filter.ALL

    private enum class Filter { ALL, FAV, ALBUM }

    // ---------------- result handlers ----------------

    /** Android's photo picker: looks like the gallery, not a file browser. */
    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
            if (uris.isNotEmpty()) importUris(uris)
        }

    private val editor =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) snack("Saved")
            render()
        }

    /** Gallery permission, only asked when you turn on album sync. */
    private val askPhotos =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) chooseAlbum()
            else snack("Album sync needs access to your photos. Choose “Allow all”.")
        }

    // ---------------- lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this) // Material You colours
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.nav.setOnItemSelectedListener {
            showTab(it.itemId)
            true
        }
        setupHome()
        setupPhotos()
        setupSettings()
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

    /** Switches tab. Also used by the screenshot test. */
    fun showTab(id: Int) {
        b.home.root.visibility = if (id == R.id.navHome) View.VISIBLE else View.GONE
        b.photos.root.visibility = if (id == R.id.navPhotos) View.VISIBLE else View.GONE
        b.settings.root.visibility = if (id == R.id.navSettings) View.VISIBLE else View.GONE
        if (b.nav.selectedItemId != id) b.nav.selectedItemId = id
    }

    private fun render() {
        renderHome()
        renderPhotos()
        renderSettings()
    }

    // =====================================================================
    // HOME
    // =====================================================================

    private fun setupHome() {
        val h = b.home
        // Phone preview has the real screen's shape.
        val (sw, sh) = Importer.screenSize(this)
        h.phone.layoutParams = h.phone.layoutParams.apply {
            height = (width * sh / sw).toInt()
        }
        h.upNext.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        h.upNext.adapter = upNext

        h.btnApply.setOnClickListener { applyWallpaper() }
        h.btnHomeAdd.setOnClickListener { pick() }
        h.phone.setOnClickListener { Store.current(this)?.let { openEditor(it.id) } }
        h.btnEdit.setOnClickListener { Store.current(this)?.let { openEditor(it.id) } }
        h.btnNext.setOnClickListener {
            Store.advance(this)
            render()
        }
        h.btnFav.setOnClickListener {
            val p = Store.current(this) ?: return@setOnClickListener
            Store.update(this, p.copy(fav = !p.fav))
            render()
            snack(if (p.fav) "Removed from favourites" else "Added to favourites")
        }
        h.btnMore.setOnClickListener { Store.current(this)?.let { showPhotoSheetFor(it) } }
        h.summaryCard.setOnClickListener { showTab(R.id.navSettings) }
    }

    private fun renderHome() {
        val h = b.home
        val photos = Store.photos(this)
        val cur = Store.current(this)
        val pos = Store.currentPos(this)
        val active = isActive()
        val paused = Store.paused(this)
        val empty = cur == null

        // Status
        when {
            !active -> {
                h.statusChip.text = "Off"
                h.statusChip.setChipIconResource(R.drawable.ic_info)
            }
            paused -> {
                h.statusChip.text = "Paused"
                h.statusChip.setChipIconResource(R.drawable.ic_pause)
            }
            else -> {
                h.statusChip.text = "On"
                h.statusChip.setChipIconResource(R.drawable.ic_check)
            }
        }
        h.setupCard.visibility = if (!active && !empty) View.VISIBLE else View.GONE

        // Preview
        if (cur == null) {
            h.heroImage.setBitmap(null)
            h.heroPlaceholder.visibility = View.VISIBLE
        } else {
            h.heroPlaceholder.visibility = View.GONE
            Thumbs.load(h.heroImage, cur, 560)
        }

        h.heroTitle.text = if (empty) "No photos yet" else "Photo ${pos + 1} of ${photos.size}"
        h.heroSubtitle.text = when {
            empty -> "Add a few photos and they'll take turns on your Home and lock screen."
            cur?.mid != 0L -> "From your “${Store.albumName(this) ?: "album"}” album"
            cur?.fav == true -> "One of your favourites"
            else -> "On your Home and lock screen"
        }

        // Quick actions
        h.actions.visibility = if (empty) View.GONE else View.VISIBLE
        h.btnHomeAdd.visibility = if (empty) View.VISIBLE else View.GONE
        h.btnNext.isEnabled = photos.size > 1
        val fav = cur?.fav == true
        h.btnFav.setIconResource(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        h.favLabel.text = if (fav) "Favourite ✓" else "Favourite"

        // Coming up
        val next = Store.upcoming(this, 8)
        upNext.submit(next)
        h.upNextBlock.visibility = if (next.isEmpty()) View.GONE else View.VISIBLE

        // Summary
        h.summaryCard.visibility = if (empty) View.GONE else View.VISIBLE
        h.summaryTitle.text = if (paused) "Auto-change is paused" else "Changes ${everyText(Store.every(this))}"
        h.summaryText.text = buildString {
            append(if (Store.shuffle(this@MainActivity)) "Shuffle on" else "In order")
            val favs = photos.count { it.fav }
            if (favs > 0) append(" · $favs favourite${if (favs == 1) "" else "s"}")
            append(" · tap to change")
        }
    }

    // =====================================================================
    // PHOTOS
    // =====================================================================

    private fun setupPhotos() {
        val p = b.photos
        p.grid.layoutManager = GridLayoutManager(this, 3)
        p.grid.adapter = grid
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun isLongPressDragEnabled() = filter == Filter.ALL

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                grid.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
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
                val latest = Store.photos(this@MainActivity).associateBy { it.id }
                Store.savePhotos(this@MainActivity, grid.items.mapNotNull { latest[it.id] })
                renderHome()
            }
        }).attachToRecyclerView(p.grid)

        p.fabAdd.setOnClickListener { pick() }
        p.albumCard.setOnClickListener { albumMenu() }
        p.filters.setOnCheckedStateChangeListener { _, ids ->
            filter = when (ids.firstOrNull()) {
                R.id.filterFav -> Filter.FAV
                R.id.filterAlbum -> Filter.ALBUM
                else -> Filter.ALL
            }
            renderPhotos()
        }
        p.btnPhotosMenu.setOnClickListener { v ->
            PopupMenu(this, v).apply {
                menuInflater.inflate(R.menu.photos, menu)
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        R.id.menuFrameAll -> confirmFrameAll()
                        R.id.menuClear -> confirmClear()
                    }
                    true
                }
            }.show()
        }
    }

    private fun renderPhotos() {
        val p = b.photos
        val all = Store.photos(this)
        val favs = all.count { it.fav }
        val fromAlbum = all.count { it.mid != 0L }
        val album = Store.albumName(this)

        p.photosCount.text = when {
            all.isEmpty() -> "Nothing here yet"
            else -> "${all.size} photo${if (all.size == 1) "" else "s"} in rotation"
        }

        // Filters
        p.filtersScroll.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        p.filterAll.text = "All · ${all.size}"
        p.filterFav.text = "Favourites · $favs"
        p.filterAlbum.text = "From album · $fromAlbum"
        p.filterAlbum.visibility = if (album != null || fromAlbum > 0) View.VISIBLE else View.GONE
        if (filter == Filter.ALBUM && p.filterAlbum.visibility != View.VISIBLE) {
            filter = Filter.ALL
            p.filters.check(R.id.filterAll)
        }

        val shown = when (filter) {
            Filter.ALL -> all
            Filter.FAV -> all.filter { it.fav }
            Filter.ALBUM -> all.filter { it.mid != 0L }
        }
        grid.submit(shown, Store.current(this)?.id)
        p.grid.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        p.gridHint.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        p.gridHint.text = if (filter == Filter.ALL) "Tap for options · Hold and drag to reorder"
        else "Tap for options"
        p.emptyState.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        when {
            all.isEmpty() -> {
                p.emptyTitle.text = "No photos yet"
                p.emptyText.text = "Tap Add photos, or sync an album above."
            }
            filter == Filter.FAV -> {
                p.emptyTitle.text = "No favourites yet"
                p.emptyText.text = "Tap a photo and choose Favourite.\nFavourites come up twice as often."
            }
            else -> {
                p.emptyTitle.text = "Nothing from the album yet"
                p.emptyText.text = "New photos in the album appear here."
            }
        }

        // Album card
        if (album == null) {
            p.albumTitle.text = "Sync an album"
            p.albumText.text = "New photos in it join your wallpapers by themselves"
        } else {
            p.albumTitle.text = "Syncing “$album”"
            p.albumText.text = "$fromAlbum photo${if (fromAlbum == 1) "" else "s"} from this album · tap to manage"
        }
    }

    private fun showPhotoSheetFor(photo: Photo) {
        val index = grid.items.indexOfFirst { it.id == photo.id }
        if (index >= 0) showPhotoSheet(index)
        else showPhotoSheet(photo, Store.photos(this).indexOfFirst { it.id == photo.id })
    }

    /** Options for the photo at [index] in the grid. Also used by the screenshot test. */
    fun showPhotoSheet(index: Int) {
        val p = grid.items.getOrNull(index) ?: return
        showPhotoSheet(p, Store.photos(this).indexOfFirst { it.id == p.id })
    }

    private fun showPhotoSheet(p: Photo, number: Int) {
        val sheet = BottomSheetDialog(this)
        val sb = SheetPhotoBinding.inflate(layoutInflater)
        Thumbs.load(sb.preview, p, 220)
        val isCurrent = Store.current(this)?.id == p.id

        sb.sheetTitle.text = "Photo ${number + 1}"
        sb.sheetSubtitle.text = buildString {
            append(if (isCurrent) "On your screen now" else "In your rotation")
            if (p.fav) append("\nFavourite")
            if (p.mid != 0L) append("\nFrom “${Store.albumName(this@MainActivity) ?: "album"}”")
            if (p.rows > 1) append("\n${p.rows}× layout")
        }

        // Main actions
        sb.boxShow.visibility = if (isCurrent) View.GONE else View.VISIBLE
        sb.actionShow.setOnClickListener {
            sheet.dismiss()
            Store.setCurrent(this, p.id)
            render()
            snack("Wallpaper changed")
        }
        sb.actionAdjust.setOnClickListener {
            sheet.dismiss()
            openEditor(p.id)
        }
        sb.actionFav.setIconResource(if (p.fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        sb.actionFavLabel.text = if (p.fav) "Favourite ✓" else "Favourite"
        sb.actionFav.setOnClickListener {
            sheet.dismiss()
            Store.update(this, p.copy(fav = !p.fav))
            render()
            snack(if (p.fav) "Removed from favourites" else "Added to favourites")
        }

        // Fit / Fill switch for photos that don't match the screen shape.
        val size = Importer.imageSize(p.file(this))
        val fitZoom = if (size != null) {
            val (sw, sh) = Importer.screenSize(this)
            Framing.minZoom((p.cr - p.cl) * size.first, (p.cb - p.ct) * size.second, sw, sh)
        } else 1f
        val isFit = p.rows == 1 && abs(p.zoom - fitZoom) < 0.01f
        if (fitZoom > 0.99f) sb.actionFit.visibility = View.GONE
        sb.actionFit.text = if (isFit) "Fill the screen" else "Fit whole photo"
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

        sb.actionLayout.text = if (p.rows > 1) "Layout: ${p.rows}× (change)" else "Layout: repeat 2× or 3×"
        sb.actionLayout.setOnClickListener {
            sheet.dismiss()
            val options = arrayOf("Single photo", "2× layout", "3× layout")
            MaterialAlertDialogBuilder(this)
                .setTitle("Layout")
                .setSingleChoiceItems(options, (p.rows - 1).coerceIn(0, 2)) { d, which ->
                    d.dismiss()
                    Store.update(this, p.copy(rows = which + 1, zoom = 1f, cx = 0.5f, cy = 0.5f))
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
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

        sb.actionRemove.setOnClickListener {
            sheet.dismiss()
            Store.remove(this, p.id)
            render()
            snack("Removed. Your gallery copy is untouched.")
        }

        sheet.setContentView(sb.root)
        sheet.show()
    }

    private fun confirmClear() {
        if (Store.photos(this).isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove all photos?")
            .setMessage("Your gallery is not touched. Only this list is cleared, and album sync stops.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove all") { _, _ ->
                Store.clear(this)
                render()
            }
            .show()
    }

    private fun confirmFrameAll() {
        if (Store.photos(this).isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Frame all photos?")
            .setMessage("Photos with faces get moved so faces sit below the clock. Photos without faces stay as they are.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Frame all") { _, _ -> frameAllFaces() }
            .show()
    }

    private fun frameAllFaces() {
        val photos = Store.photos(this).filter { it.rows == 1 }
        if (photos.isEmpty()) return
        setBusy(true)
        snack("Finding faces in ${photos.size} photos…")
        Thread {
            var framed = 0
            photos.forEach { p ->
                Faces.autoFrame(applicationContext, p)?.let {
                    val latest = Store.photos(applicationContext).firstOrNull { x -> x.id == p.id } ?: return@let
                    Store.update(applicationContext, latest.copy(zoom = it.zoom, cx = it.cx, cy = it.cy, rows = 1))
                    framed++
                }
            }
            runOnUiThread {
                setBusy(false)
                render()
                snack(if (framed == 0) "No faces found" else "Framed $framed photo${if (framed == 1) "" else "s"}")
            }
        }.start()
    }

    // ---------------- adding photos ----------------

    private fun pick() {
        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun importUris(uris: List<Uri>) {
        showTab(R.id.navPhotos)
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
        b.photos.progress.visibility = if (busy) View.VISIBLE else View.GONE
        b.photos.fabAdd.isEnabled = !busy
    }

    // ---------------- album sync ----------------

    private fun albumMenu() {
        val name = Store.albumName(this) ?: return startAlbumPick()
        MaterialAlertDialogBuilder(this)
            .setTitle("“$name” album")
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
            .setMessage("Keep the photos already added from the album, or remove them?")
            .setNegativeButton("Keep photos") { _, _ ->
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
                if (albums.isEmpty()) snack("No albums found") else showAlbumSheet(albums)
            }
        }.start()
    }

    /** Album picker with covers. Also used by the screenshot test. */
    fun showAlbumSheet(albums: List<AlbumSync.Album>) {
        val sheet = BottomSheetDialog(this)
        val sb = SheetAlbumsBinding.inflate(layoutInflater)
        sb.albumGrid.layoutManager = GridLayoutManager(this, 2)
        sb.albumGrid.adapter = AlbumAdapter(albums) { a ->
            sheet.dismiss()
            if (Store.albumId(this) != null && Store.albumId(this) != a.id) {
                Store.photos(this).filter { it.mid != 0L }.forEach { Store.remove(this, it.id, fromSync = true) }
            }
            Store.setAlbum(this, a.id, a.name)
            showTab(R.id.navPhotos)
            render()
            runSync(loud = true)
        }
        sheet.setContentView(sb.root)
        sheet.behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
        sheet.show()
    }

    private fun runSync(loud: Boolean) {
        if (loud) {
            setBusy(true)
            snack("Syncing… new photos appear as they're ready")
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
                            added == 0 -> "$removed removed (deleted from the album)"
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

    // =====================================================================
    // SETTINGS
    // =====================================================================

    private fun setupSettings() {
        val s = b.settings
        switchRow(s.rowAuto, R.drawable.ic_autorenew, "Auto-change", "Change the wallpaper when you lock the phone", { !Store.paused(this) }) {
            Store.setPaused(this, !it)
            render()
        }
        valueRow(s.rowEvery, R.drawable.ic_timer, "How often") { chooseEvery() }
        switchRow(s.rowShuffle, R.drawable.ic_shuffle, "Shuffle", "Every photo once before any repeats; favourites twice as often", { Store.shuffle(this) }) {
            Store.setShuffle(this, it)
            render()
        }
        switchRow(s.rowAutoFace, R.drawable.ic_face, "Auto-frame faces", "Faces placed below the clock", { Store.autoFace(this) }) {
            Store.setAutoFace(this, it)
        }
        switchRow(s.rowFitNew, R.drawable.ic_fit_screen, "Fit whole photo", "Show all of a new photo instead of filling the screen", { Store.fitNew(this) }) {
            Store.setFitNew(this, it)
        }
        switchRow(s.rowAnimate, R.drawable.ic_auto_awesome, "Wake-up animation", "Gentle zoom when the screen turns on", { Store.animate(this) }) {
            Store.setAnimate(this, it)
        }
        switchRow(s.rowDim, R.drawable.ic_dark_mode, "Dim in dark mode", "Easier on the eyes at night", { Store.dim(this) }) {
            Store.setDim(this, it)
        }
        switchRow(s.rowColors, R.drawable.ic_palette, "Match system colours", "Phone theme follows the current photo", { Store.matchColors(this) }) {
            Store.setMatchColors(this, it)
        }
        if (Build.VERSION.SDK_INT < 31) s.rowColors.root.visibility = View.GONE

        valueRow(s.rowTile, R.drawable.ic_skip_next, "Quick Settings tile") { addTile() }
        s.rowTile.subtitle.text = "Skip a photo from the notification panel"
        valueRow(s.rowVersion, R.drawable.ic_download, "Version 1.${Updater.currentBuild(this)}") { checkForUpdate() }
        s.rowVersion.subtitle.text = "Tap to check for updates"
    }

    private fun renderSettings() {
        val s = b.settings
        s.rowEvery.subtitle.text = everyText(Store.every(this)).replaceFirstChar { it.uppercase() }
        val on = !Store.paused(this)
        s.rowEvery.root.alpha = if (on) 1f else 0.4f
        s.rowShuffle.root.alpha = if (on) 1f else 0.4f
        // Keep switches in step without firing their listeners.
        listOf(
            s.rowAuto to on,
            s.rowShuffle to Store.shuffle(this),
            s.rowAutoFace to Store.autoFace(this),
            s.rowFitNew to Store.fitNew(this),
            s.rowAnimate to Store.animate(this),
            s.rowDim to Store.dim(this),
            s.rowColors to Store.matchColors(this)
        ).forEach { (row, value) ->
            if (row.switchView.isChecked != value) {
                row.switchView.tag = SYNCING
                row.switchView.isChecked = value
                row.switchView.tag = null
            }
        }
    }

    private fun switchRow(
        r: RowSwitchBinding,
        @DrawableRes icon: Int,
        title: String,
        subtitle: String,
        value: () -> Boolean,
        onChange: (Boolean) -> Unit
    ) {
        r.icon.setImageResource(icon)
        r.title.text = title
        r.subtitle.text = subtitle
        r.switchView.isChecked = value()
        r.switchView.setOnCheckedChangeListener { v, on -> if (v.tag != SYNCING) onChange(on) }
        r.root.setOnClickListener { r.switchView.toggle() }
    }

    private fun valueRow(r: RowSwitchBinding, @DrawableRes icon: Int, title: String, onClick: () -> Unit) {
        r.icon.setImageResource(icon)
        r.title.text = title
        r.switchView.visibility = View.GONE
        r.chevron.visibility = View.VISIBLE
        r.root.setOnClickListener { onClick() }
    }

    private fun chooseEvery() {
        val values = intArrayOf(1, 2, 5, 10)
        val labels = values.map { everyText(it).replaceFirstChar { c -> c.uppercase() } }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("How often")
            .setSingleChoiceItems(labels, values.indexOf(Store.every(this)).coerceAtLeast(0)) { d, which ->
                Store.setEvery(this, values[which])
                d.dismiss()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun everyText(n: Int) = when (n) {
        1 -> "every time you lock"
        2 -> "every 2nd lock"
        else -> "every ${n}th lock"
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, NextTileService::class.java),
                "Next wallpaper",
                Icon.createWithResource(this, R.drawable.ic_skip_next),
                mainExecutor
            ) { result ->
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) snack("Tile added")
            }
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Add the tile")
                .setMessage("Pull down the notification panel, tap the pencil (edit) button, and drag “Next wallpaper” into your tiles.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ---------------- in-app updates ----------------

    private var pendingUpdateAfterPermission = false
    private var updating = false

    private fun setupUpdates() {
        Updater.cleanUp(this) // leftover download from the last update
        Updater.onInstallResult = { ok, message ->
            updating = false
            if (ok) b.home.updateText.text = "Updated!"
            else {
                showUpdateIdle()
                snack(message ?: "Update failed")
            }
        }
        b.home.btnUpdate.setOnClickListener { startUpdate() }
        Updater.check(this, ::showUpdate)
    }

    private fun showUpdate(release: Updater.Release) {
        val h = b.home
        h.updateText.text = "Version 1.${release.build} is available"
        if (release.notes.isNotBlank()) {
            h.updateNotes.text = release.notes
            h.updateNotes.visibility = View.VISIBLE
        }
        h.updateCard.visibility = View.VISIBLE
    }

    private fun checkForUpdate() {
        snack("Checking…")
        var found = false
        Updater.check(this) {
            found = true
            showUpdate(it)
            showTab(R.id.navHome)
        }
        b.root.postDelayed({ if (!found) snack("You have the latest version") }, 4000)
    }

    private fun showUpdateIdle() {
        val h = b.home
        h.btnUpdate.isEnabled = true
        h.btnUpdate.text = "Update"
        h.updateProgress.visibility = View.GONE
    }

    private fun startUpdate() {
        if (updating) return
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
        val h = b.home
        updating = true
        h.btnUpdate.isEnabled = false
        h.btnUpdate.text = "0%"
        h.updateText.text = "Downloading update…"
        h.updateProgress.visibility = View.INVISIBLE
        h.updateProgress.isIndeterminate = false
        h.updateProgress.progress = 0
        h.updateProgress.visibility = View.VISIBLE
        Updater.downloadAndInstall(
            this,
            onProgress = { pct ->
                h.updateProgress.setProgressCompat(pct, true)
                h.btnUpdate.text = "$pct%"
            },
            onInstalling = {
                h.updateText.text = "Installing… the app will restart"
                h.btnUpdate.text = "…"
                h.updateProgress.visibility = View.INVISIBLE
                h.updateProgress.isIndeterminate = true
                h.updateProgress.visibility = View.VISIBLE
            },
            onError = { msg ->
                updating = false
                h.updateText.text = "Update failed"
                showUpdateIdle()
                snack("Couldn't download: $msg")
            }
        )
    }

    // ---------------- helpers ----------------

    private fun openEditor(id: String) {
        editor.launch(Intent(this, AdjustActivity::class.java).putExtra(AdjustActivity.EXTRA_ID, id))
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
        val onPhotos = b.photos.root.visibility == View.VISIBLE
        val s = Snackbar.make(if (onPhotos) b.photos.photosRoot else b.root, text, Snackbar.LENGTH_SHORT)
        s.anchorView = if (onPhotos) b.photos.fabAdd else b.nav
        s.show()
    }

    companion object {
        private const val MAX_PICK = 100
        private const val SYNCING = "syncing"
    }
}

/** Small "Coming up" strip on Home. */
class UpNextAdapter(private val onTap: (Photo) -> Unit) : RecyclerView.Adapter<UpNextAdapter.VH>() {
    private var items: List<Photo> = emptyList()

    fun submit(list: List<Photo>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemUpnextBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemUpnextBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        Thumbs.load(holder.b.thumb, p, 160)
        holder.b.root.setOnClickListener { onTap(p) }
    }

    override fun getItemCount() = items.size
}

/** Album covers in the album picker. */
class AlbumAdapter(
    private val albums: List<AlbumSync.Album>,
    private val onPick: (AlbumSync.Album) -> Unit
) : RecyclerView.Adapter<AlbumAdapter.VH>() {

    class VH(val b: ItemAlbumBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = albums[position]
        holder.b.albumName.text = a.name
        holder.b.albumCount.text = "${a.count} photo${if (a.count == 1) "" else "s"}"
        val uri = a.coverUri
        if (uri != null) Thumbs.loadUri(holder.b.cover, uri, 360) else holder.b.cover.setImageDrawable(null)
        holder.b.root.setOnClickListener { onPick(a) }
    }

    override fun getItemCount() = albums.size
}
