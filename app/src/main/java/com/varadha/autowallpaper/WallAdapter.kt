package com.varadha.autowallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.varadha.autowallpaper.databinding.ItemWallpaperBinding

/** Grid of photos. Tap = options sheet, long-press and drag = reorder. */
class WallAdapter(
    private val onTap: (Int) -> Unit
) : RecyclerView.Adapter<WallAdapter.VH>() {

    val items = mutableListOf<Photo>()
    private var currentId: String? = null

    fun submit(list: List<Photo>, current: String?) {
        items.clear()
        items.addAll(list)
        currentId = current
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
        val p = items.removeAt(from)
        items.add(to, p)
        notifyItemMoved(from, to)
    }

    class VH(val b: ItemWallpaperBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemWallpaperBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val width = (if (parent.width > 0) parent.width else parent.resources.displayMetrics.widthPixels) / 3
        b.thumb.layoutParams.height = (width * 1.9f).toInt() // phone-shaped tiles
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = holder.b
        val p = items[position]
        val isCurrent = p.id == currentId
        Thumbs.load(b.thumb, p, 320)
        b.nowBadge.visibility = if (isCurrent) View.VISIBLE else View.GONE
        b.root.strokeWidth = if (isCurrent) (3 * b.root.resources.displayMetrics.density).toInt() else 0
        b.root.setOnClickListener {
            holder.bindingAdapterPosition.takeIf { it >= 0 }?.let(onTap)
        }
    }

    override fun getItemCount() = items.size
}
