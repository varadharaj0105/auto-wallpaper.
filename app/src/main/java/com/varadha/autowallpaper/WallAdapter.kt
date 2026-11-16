package com.varadha.autowallpaper

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.varadha.autowallpaper.databinding.ItemWallpaperBinding

/** Grid of picked photos. Tap = show this one now, long-press = remove. */
class WallAdapter(
    private val onTap: (Int) -> Unit,
    private val onLongPress: (Int) -> Unit
) : RecyclerView.Adapter<WallAdapter.VH>() {

    private var items: List<Uri> = emptyList()
    private var current = -1

    fun submit(list: List<Uri>, currentIndex: Int) {
        items = list
        current = currentIndex
        notifyDataSetChanged()
    }

    class VH(val b: ItemWallpaperBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemWallpaperBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val width = (if (parent.width > 0) parent.width else parent.resources.displayMetrics.widthPixels) / 3
        b.thumb.layoutParams.height = (width * 1.6f).toInt() // phone-shaped tiles
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = holder.b
        val isCurrent = position == current
        Thumbs.load(b.thumb, items[position], 320)
        b.nowBadge.visibility = if (isCurrent) View.VISIBLE else View.GONE
        (b.root as MaterialCardView).strokeWidth =
            if (isCurrent) (3 * b.root.resources.displayMetrics.density).toInt() else 0
        b.root.setOnClickListener { holder.bindingAdapterPosition.takeIf { it >= 0 }?.let(onTap) }
        b.root.setOnLongClickListener {
            holder.bindingAdapterPosition.takeIf { it >= 0 }?.let(onLongPress)
            true
        }
    }

    override fun getItemCount() = items.size
}
