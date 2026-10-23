package com.aladdin.dashcam.ui

import android.graphics.Typeface
import android.content.res.Configuration
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
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
    private val onItemClick: (MediaItem) -> Unit,
    private val onItemLongClick: ((MediaItem) -> Unit)? = null,
    private val onFetchThumbnail: ((MediaItem, android.widget.ImageView) -> Unit)? = null,
    private val onSelectionChanged: ((selectedCount: Int, selectedBytes: Long) -> Unit)? = null
) : ListAdapter<AlbumListItem, RecyclerView.ViewHolder>(DiffCallback) {

    var isSelectionMode: Boolean = false
        private set

    private val selectedItems = mutableMapOf<String, MediaItem>()

    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode != enabled) {
            isSelectionMode = enabled
            if (!enabled) {
                selectedItems.clear()
            }
            notifyItemRangeChanged(0, itemCount, SELECTION_PAYLOAD)
            notifySelectionStats()
        }
    }

    fun toggleSelection(item: MediaItem) {
        if (selectedItems.containsKey(item.id)) {
            selectedItems.remove(item.id)
        } else {
            selectedItems[item.id] = item
        }
        val position = currentList.indexOfFirst { it is AlbumListItem.Media && it.item.id == item.id }
        if (position >= 0) notifyItemChanged(position, SELECTION_PAYLOAD)
        notifySelectionStats()
    }

    fun selectAll(allItems: List<MediaItem>) {
        selectedItems.clear()
        for (item in allItems) {
            selectedItems[item.id] = item
        }
        notifyItemRangeChanged(0, itemCount, SELECTION_PAYLOAD)
        notifySelectionStats()
    }

    fun clearSelection() {
        selectedItems.clear()
        notifyItemRangeChanged(0, itemCount, SELECTION_PAYLOAD)
        notifySelectionStats()
    }

    fun getSelectedList(): List<MediaItem> = selectedItems.values.toList()

    private fun notifySelectionStats() {
        val count = selectedItems.size
        val totalBytes = selectedItems.values.sumOf { it.sizeBytes }
        onSelectionChanged?.invoke(count, totalBytes)
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_MEDIA = 1
        private const val SELECTION_PAYLOAD = "selection"

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
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (6 * dm.density).toInt()
                    bottomMargin = (4 * dm.density).toInt()
                }
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding((12 * dm.density).toInt(), (4 * dm.density).toInt(), (12 * dm.density).toInt(), (4 * dm.density).toInt())
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                includeFontPadding = false
            }
            HeaderViewHolder(textView)
        } else {
            val binding = ItemMediaCardBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            MediaViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == SELECTION_PAYLOAD }) {
            val media = (getItem(position) as? AlbumListItem.Media)?.item ?: return
            val binding = (holder as? MediaViewHolder)?.binding ?: return
            binding.cbSelect.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            binding.cbSelect.isChecked = selectedItems.containsKey(media.id)
        } else super.onBindViewHolder(holder, position, payloads)
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
                val landscape = binding.root.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val density = binding.root.resources.displayMetrics.density
                val thumbnailFrame = binding.ivThumbnail.parent as View
                val row = thumbnailFrame.parent as LinearLayout
                row.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                thumbnailFrame.layoutParams = LinearLayout.LayoutParams(
                    if (landscape) (88 * density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT,
                    ((if (landscape) 88 else 100) * density).toInt()
                )
                row.getChildAt(1).layoutParams = LinearLayout.LayoutParams(
                    if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (landscape) 1f else 0f
                )

                // Primary: Recording time
                if (media.timestampMillis > 0L) {
                    binding.tvRecordTime.text = TIME_FORMAT.format(Date(media.timestampMillis))
                } else {
                    binding.tvRecordTime.text = "時間未知"
                }

                // Secondary: File name
                binding.tvMediaTitle.text = media.fileName.replace("_", "_\u200B")

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
                        binding.tvDurationBadge.visibility = View.GONE
                    }
                } else {
                    binding.tvMediaType.text = "照片"
                    binding.tvDurationBadge.visibility = View.GONE
                }

                // Downloaded badge
                binding.tvDownloadedBadge.visibility = if (media.isDownloaded) View.VISIBLE else View.GONE

                // Batch Selection Checkbox
                if (isSelectionMode) {
                    binding.cbSelect.visibility = View.VISIBLE
                    binding.cbSelect.isChecked = selectedItems.containsKey(media.id)
                } else {
                    binding.cbSelect.visibility = View.GONE
                }

                // Fetch real thumbnail asynchronously if callback provided
                binding.ivThumbnail.setImageDrawable(null)
                binding.ivThumbnail.tag = media.id
                onFetchThumbnail?.invoke(media, binding.ivThumbnail)

                // Click and Long click handling
                binding.root.setOnClickListener {
                    if (isSelectionMode) {
                        toggleSelection(media)
                    } else {
                        onItemClick(media)
                    }
                }

                binding.root.setOnLongClickListener {
                    if (!isSelectionMode) {
                        setSelectionMode(true)
                        toggleSelection(media)
                        onItemLongClick?.invoke(media)
                    }
                    true
                }
            }
        }
    }
}
