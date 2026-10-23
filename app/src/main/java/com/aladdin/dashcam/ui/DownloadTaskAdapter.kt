package com.aladdin.dashcam.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aladdin.dashcam.R
import com.aladdin.dashcam.databinding.ItemDownloadTaskBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadTaskAdapter(
    private val onActionClick: (DownloadTask) -> Unit
) : ListAdapter<DownloadTask, DownloadTaskAdapter.TaskViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val binding = ItemDownloadTaskBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TaskViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TaskViewHolder(
        private val binding: ItemDownloadTaskBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(task: DownloadTask) {
            val context = itemView.context
            val item = task.mediaItem

            // Format title with recording time if available
            val timeStr = if (item.timestampMillis > 0L) {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(item.timestampMillis))
            } else null

            binding.tvTaskTitle.text = if (timeStr != null) {
                "${item.fileName} ($timeStr)"
            } else {
                item.fileName
            }

            val totalSizeStr = formatFileSize(task.totalBytes)

            when (task.status) {
                DownloadStatus.DOWNLOADING -> {
                    val readStr = formatFileSize(task.bytesRead)
                    binding.tvTaskDetails.text = if (task.totalBytes > 0) {
                        val progress = ((task.bytesRead * 100) / task.totalBytes).toInt().coerceIn(0, 100)
                        itemView.context.getString(R.string.download_task_adapter_downloading_s_s_s, progress, readStr, totalSizeStr)
                    } else {
                        itemView.context.getString(R.string.download_task_adapter_downloading_s, readStr)
                    }
                    binding.tvTaskDetails.setTextColor(ContextCompat.getColor(context, R.color.accent_teal))

                    binding.pbTaskProgress.visibility = View.VISIBLE
                    if (task.totalBytes > 0) {
                        val progress = ((task.bytesRead * 100) / task.totalBytes).toInt().coerceIn(0, 100)
                        binding.pbTaskProgress.isIndeterminate = false
                        binding.pbTaskProgress.progress = progress
                    } else {
                        binding.pbTaskProgress.isIndeterminate = true
                    }

                    binding.btnTaskAction.text = itemView.context.getString(R.string.album_cancel)
                    binding.btnTaskAction.setTextColor(ContextCompat.getColor(context, R.color.error_red))
                }
                DownloadStatus.COMPLETED -> {
                    binding.tvTaskDetails.text = itemView.context.getString(R.string.download_task_adapter_downloaded_s, totalSizeStr)
                    binding.tvTaskDetails.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    binding.pbTaskProgress.visibility = View.GONE

                    binding.btnTaskAction.text = itemView.context.getString(R.string.action_open)
                    binding.btnTaskAction.setTextColor(ContextCompat.getColor(context, R.color.accent_teal))
                }
                DownloadStatus.FAILED -> {
                    binding.tvTaskDetails.text = itemView.context.getString(R.string.download_task_adapter_download_failed_s, task.errorMessage ?: itemView.context.getString(R.string.download_task_adapter_connection_timed_out))
                    binding.tvTaskDetails.setTextColor(ContextCompat.getColor(context, R.color.error_red))
                    binding.pbTaskProgress.visibility = View.GONE

                    binding.btnTaskAction.text = itemView.context.getString(R.string.album_retry_077)
                    binding.btnTaskAction.setTextColor(ContextCompat.getColor(context, R.color.warning_yellow))
                }
                DownloadStatus.CANCELLED -> {
                    binding.tvTaskDetails.text = itemView.context.getString(R.string.downloads_download_cancelled)
                    binding.tvTaskDetails.setTextColor(ContextCompat.getColor(context, R.color.text_disabled))
                    binding.pbTaskProgress.visibility = View.GONE

                    binding.btnTaskAction.text = itemView.context.getString(R.string.download_task_adapter_download_again)
                    binding.btnTaskAction.setTextColor(ContextCompat.getColor(context, R.color.accent_teal))
                }
                DownloadStatus.PENDING -> {
                    binding.tvTaskDetails.text = itemView.context.getString(R.string.album_waiting_to_download)
                    binding.tvTaskDetails.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    binding.pbTaskProgress.visibility = View.GONE

                    binding.btnTaskAction.text = itemView.context.getString(R.string.download_task_adapter_waiting)
                    binding.btnTaskAction.setTextColor(ContextCompat.getColor(context, R.color.text_disabled))
                }
            }

            binding.btnTaskAction.setOnClickListener {
                onActionClick(task)
            }
        }

        private fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.getDefault(), "%.1f GB", gb)
                mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
                else -> "$bytes B"
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<DownloadTask>() {
        override fun areItemsTheSame(oldItem: DownloadTask, newItem: DownloadTask): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DownloadTask, newItem: DownloadTask): Boolean {
            return oldItem == newItem
        }
    }
}
