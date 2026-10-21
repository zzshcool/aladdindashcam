package com.aladdin.dashcam.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aladdin.dashcam.databinding.ItemMediaCardBinding
import com.aladdin.dashcam.model.MediaItem
import java.util.Locale

class MediaAdapter(
    private val onItemClick: (MediaItem) -> Unit
) : ListAdapter<MediaItem, MediaAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val binding: ItemMediaCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMediaCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val binding = holder.binding

        binding.tvMediaTitle.text = item.fileName
        val sizeMb = item.sizeBytes / (1024 * 1024)
        binding.tvMediaSize.text = String.format(Locale.getDefault(), "%d MB", sizeMb)

        if (item.isVideo) {
            val min = item.durationSeconds / 60
            val sec = item.durationSeconds % 60
            binding.tvDurationBadge.visibility = View.VISIBLE
            binding.tvDurationBadge.text = String.format(Locale.getDefault(), "%02d:%02d", min, sec)
            binding.tvMediaType.text = if (item.isEmergency) "緊急事件" else "常規錄影"
        } else {
            binding.tvDurationBadge.visibility = View.GONE
            binding.tvMediaType.text = "照片"
        }

        binding.tvDownloadedBadge.visibility = if (item.isDownloaded) View.VISIBLE else View.GONE

        binding.root.setOnClickListener {
            onItemClick(item)
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
            oldItem == newItem
    }
}
