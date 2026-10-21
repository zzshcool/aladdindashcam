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
                        "下載中 $progress% ($readStr / $totalSizeStr)"
                    } else {
                        "下載中... ($readStr)"
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

                    binding.btnTaskAction.text = "取消"
                    binding.btnTaskAction.setTextColor(ContextCompat.getColor(context, R.color.error_red))
                }
                DownloadStatus.COMPLETED -> {
                    binding.tvTaskDetails.text = "已下載完成 ($totalSizeStr)"
                    binding.tvTaskDetails.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    binding.pbTaskProgress.visibility = View.GONE

                    binding.btnTaskAction.text = "開啟"
                    binding.btnTaskAction.setTextColor(ContextCompat.getColor(context, R.color.accent_teal))
                }
                DownloadStatus.FAILED -> {
                    binding.tvTaskDetails.text = "下載失敗: ${task.errorMessage ?: "連線逾時"}"
                    binding.tvTaskDetails.setTextColor(ContextCompat.getColor(context, R.color.error_red))
                    binding.pbTaskProgress.visibility = View.GONE

                    binding.btnTaskAction.text = "重試"
                    binding.btnTaskAction.setTextColor(ContextCompat.getColor(context, R.color.warning_yellow))
                }
                DownloadStatus.CANCELLED -> {
                    binding.tvTaskDetails.text = "已取消下載"
                    binding.tvTaskDetails.setTextColor(ContextCompat.getColor(context, R.color.text_disabled))
                    binding.pbTaskProgress.visibility = View.GONE

                    binding.btnTaskAction.text = "重新下載"
                    binding.btnTaskAction.setTextColor(ContextCompat.getColor(context, R.color.accent_teal))
                }
                DownloadStatus.PENDING -> {
                    binding.tvTaskDetails.text = "等待下載..."
                    binding.tvTaskDetails.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    binding.pbTaskProgress.visibility = View.GONE

                    binding.btnTaskAction.text = "等待"
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
