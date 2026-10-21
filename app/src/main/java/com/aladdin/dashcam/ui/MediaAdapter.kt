package com.aladdin.dashcam.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aladdin.dashcam.R
import com.aladdin.dashcam.databinding.ItemMediaCardBinding
import com.aladdin.dashcam.model.MediaItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class AlbumListItem {
    data class Header(val dateString: String) : AlbumListItem()
    data class Media(val item: MediaItem) : AlbumListItem()
}

class MediaAdapter(
    private val onItemClick: (MediaItem) -> Unit
) : ListAdapter<AlbumListItem, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_MEDIA = 1

        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        object DiffCallback : DiffUtil.ItemCallback<AlbumListItem>() {
            override fun areItemsTheSame(oldItem: AlbumListItem, newItem: AlbumListItem): Boolean {
                return when {
                    oldItem is AlbumListItem.Header && newItem is AlbumListItem.Header ->
                        oldItem.dateString == newItem.dateString
                    oldItem is AlbumListItem.Media && newItem is AlbumListItem.Media ->
                        oldItem.item.id == newItem.item.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: AlbumListItem, newItem: AlbumListItem): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is AlbumListItem.Header -> TYPE_HEADER
            is AlbumListItem.Media -> TYPE_MEDIA
        }
    }

    class HeaderViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    class MediaViewHolder(val binding: ItemMediaCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val context = parent.context
            val dm = context.resources.displayMetrics
            val textView = TextView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (42 * dm.density).toInt()
                )
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * dm.density).toInt(), (8 * dm.density).toInt(), (12 * dm.density).toInt(), 0)
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
            }
            HeaderViewHolder(textView)
        } else {
            val binding = ItemMediaCardBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            MediaViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is AlbumListItem.Header -> {
                val headerHolder = holder as HeaderViewHolder
                headerHolder.textView.text = item.dateString
            }
            is AlbumListItem.Media -> {
                val mediaHolder = holder as MediaViewHolder
                val media = item.item
                val binding = mediaHolder.binding

                // Primary: Recording time
                if (media.timestampMillis > 0L) {
                    binding.tvRecordTime.text = TIME_FORMAT.format(Date(media.timestampMillis))
                } else {
                    binding.tvRecordTime.text = "時間未知"
                }

                // Secondary: File name
                binding.tvMediaTitle.text = media.fileName

                // File size
                val sizeMb = media.sizeBytes / (1024 * 1024)
                if (sizeMb > 0) {
                    binding.tvMediaSize.text = String.format(Locale.getDefault(), "%d MB", sizeMb)
                } else {
                    val sizeKb = media.sizeBytes / 1024
                    binding.tvMediaSize.text = String.format(Locale.getDefault(), "%d KB", sizeKb)
                }

                // Media type and duration
                if (media.isVideo) {
                    binding.tvMediaType.text = if (media.isEmergency) "緊急事件" else "常規錄影"
                    if (media.durationSeconds > 0) {
                        val min = media.durationSeconds / 60
                        val sec = media.durationSeconds % 60
                        binding.tvDurationBadge.visibility = View.VISIBLE
                        binding.tvDurationBadge.text = String.format(Locale.getDefault(), "%02d:%02d", min, sec)
                    } else {
                        // Do not display hardcoded 01:00, hide when real duration is unknown
                        binding.tvDurationBadge.visibility = View.GONE
                    }
                } else {
                    binding.tvMediaType.text = "照片"
                    binding.tvDurationBadge.visibility = View.GONE
                }

                // Downloaded badge
                binding.tvDownloadedBadge.visibility = if (media.isDownloaded) View.VISIBLE else View.GONE

                binding.root.setOnClickListener {
                    onItemClick(media)
                }
            }
        }
    }
}
