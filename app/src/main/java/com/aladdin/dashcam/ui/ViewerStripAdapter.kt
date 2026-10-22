package com.aladdin.dashcam.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.aladdin.dashcam.R
import com.aladdin.dashcam.databinding.ItemViewerStripBinding
import com.aladdin.dashcam.model.MediaItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViewerStripAdapter(
    private val onItemClick: (MediaItem) -> Unit,
    private val onFetchThumbnail: ((MediaItem, android.widget.ImageView) -> Unit)? = null
) : RecyclerView.Adapter<ViewerStripAdapter.StripViewHolder>() {

    private val items = mutableListOf<MediaItem>()
    private var currentPlayingId: String? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun submitList(newItems: List<MediaItem>, activeId: String?) {
        items.clear()
        items.addAll(newItems)
        currentPlayingId = activeId
        notifyDataSetChanged()
    }

    fun setActiveId(activeId: String?) {
        if (currentPlayingId != activeId) {
            currentPlayingId = activeId
            notifyDataSetChanged()
        }
    }

    class StripViewHolder(val binding: ItemViewerStripBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StripViewHolder {
        val binding = ItemViewerStripBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return StripViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: StripViewHolder, position: Int) {
        val item = items[position]
        val binding = holder.binding

        val isPlaying = item.id == currentPlayingId
        val strokeColor = if (isPlaying) {
            ContextCompat.getColor(binding.root.context, R.color.accent_teal)
        } else {
            Color.TRANSPARENT
        }
        binding.cardStripContainer.strokeColor = strokeColor
        binding.cardStripContainer.strokeWidth = if (isPlaying) 4 else 0

        if (item.timestampMillis > 0L) {
            binding.tvStripTime.text = timeFormat.format(Date(item.timestampMillis))
        } else {
            binding.tvStripTime.text = "--:--:--"
        }

        binding.ivStripThumbnail.setImageDrawable(null)
        binding.ivStripThumbnail.tag = item.id
        onFetchThumbnail?.invoke(item, binding.ivStripThumbnail)

        binding.root.setOnClickListener {
            onItemClick(item)
        }
    }
}
