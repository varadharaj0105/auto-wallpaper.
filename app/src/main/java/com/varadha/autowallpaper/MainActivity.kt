package com.varadha.autowallpaper

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.varadha.autowallpaper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val adapter = WallAdapter(::onTap, ::onLongPress)

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            uris.forEach {
                runCatching {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val added = Store.add(this, uris)
            render()
            snack(if (added == 1) "1 photo added" else "$added photos added")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this) // Material You colours from the phone's theme
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.grid.layoutManager = GridLayoutManager(this, 3)
        b.grid.adapter = adapter

        b.fabAdd.setOnClickListener { pickImages.launch(arrayOf("image/*")) }
        b.btnEmptyAdd.setOnClickListener { pickImages.launch(arrayOf("image/*")) }
        b.btnApply.setOnClickListener { applyWallpaper() }
        b.btnNext.setOnClickListener {
            Store.advance(this)
            render()
        }
        b.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Remove all photos?")
                .setMessage("They stay in your gallery. Only this list is cleared.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove all") { _, _ ->
                    Store.clear(this)
                    render()
                }
                .show()
        }

        b.switchShuffle.isChecked = Store.shuffle(this)
        b.switchShuffle.setOnCheckedChangeListener { _, on ->
            Store.setShuffle(this, on)
            render()
        }

        when (Store.every(this)) {
            2 -> b.chipEvery2
            5 -> b.chipEvery5
            else -> b.chipEvery1
        }.isChecked = true
        b.chipsEvery.setOnCheckedStateChangeListener { _, ids ->
            val n = when (ids.firstOrNull()) {
                R.id.chipEvery2 -> 2
                R.id.chipEvery5 -> 5
                else -> 1
            }
            Store.setEvery(this, n)
            render()
        }

        b.versionText.text = "Version 1.${Updater.currentBuild(this)}"
        b.btnUpdate.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.DOWNLOAD_URL)))
        }
        Updater.check(this) { latest ->
            b.updateText.text = "Version 1.$latest is ready to install"
            b.updateCard.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        render() // picks up "Active" after coming back from the wallpaper picker
    }

    private fun render() {
        val uris = Store.uris(this)
        val empty = uris.isEmpty()
        val cur = Store.index(this)
        val active = isActive()
        val every = Store.every(this)
        val shuffle = Store.shuffle(this)

        adapter.submit(uris, if (empty) -1 else cur)
        b.grid.visibility = if (empty) View.GONE else View.VISIBLE
        b.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        b.btnClear.visibility = if (empty) View.GONE else View.VISIBLE
        b.gridTitle.text = if (empty) "Your photos" else "Your photos · ${uris.size}"

        // Hero preview
        if (empty) {
            b.heroImage.setImageDrawable(null)
            b.heroPlaceholder.visibility = View.VISIBLE
        } else {
            b.heroPlaceholder.visibility = View.GONE
            Thumbs.load(b.heroImage, uris[cur], 900)
        }

        b.statusChip.text = if (active) "Active" else "Not active"
        b.statusChip.setChipIconResource(if (active) R.drawable.ic_check else R.drawable.ic_pause)

        b.heroTitle.text = when {
            empty -> "No photos yet"
            else -> "Photo ${cur + 1} of ${uris.size}"
        }
        val howOften = when (every) {
            1 -> "every time you lock"
            2 -> "every 2nd lock"
            else -> "every ${every}th lock"
        }
        b.heroSubtitle.text = when {
            empty -> "Add some photos and they'll rotate on your Home and lock screen."
            !active -> "Tap Set as wallpaper to start. Then it changes $howOften."
            else -> "Changes $howOften" + if (shuffle) ", in random order." else "."
        }

        b.btnApply.visibility = if (active || empty) View.GONE else View.VISIBLE
        b.btnNext.visibility = if (empty) View.GONE else View.VISIBLE
        b.btnNext.isEnabled = uris.size > 1
    }

    private fun onTap(position: Int) {
        Store.setIndex(this, position)
        render()
        snack("This one shows next time you unlock")
    }

    private fun onLongPress(position: Int) {
        val uri = Store.uris(this).getOrNull(position) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove this photo?")
            .setMessage("It stays in your gallery. It just won't be used as a wallpaper.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                Store.remove(this, uri)
                render()
            }
            .show()
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
        Snackbar.make(b.root, text, Snackbar.LENGTH_SHORT).setAnchorView(b.fabAdd).show()
    }
}
