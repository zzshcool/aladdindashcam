package com.aladdin.dashcam.ui

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.GridLayoutManager
import com.aladdin.dashcam.R
import com.aladdin.dashcam.databinding.FragmentAlbumBinding
import com.aladdin.dashcam.model.MediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlbumFragment : Fragment() {
    private var _binding: FragmentAlbumBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashCamViewModel by activityViewModels()
    private lateinit var adapter: MediaAdapter
    private lateinit var viewerStripAdapter: ViewerStripAdapter

    private var isDeviceSource = true
    private var isTimelineMode = false
    private var currentFilter = FilterCategory.ALL
    private var currentFilteredMediaList: List<MediaItem> = emptyList()
    private var selectedTimelineDateStr: String? = null

    // Media viewer & player state
    private var exoPlayer: ExoPlayer? = null
    private var sdpProxy: com.aladdin.dashcam.network.RtspSdpProxy? = null
    private var currentViewingItem: MediaItem? = null
    private var previewJob: Job? = null
    private var previewRequestId = 0L
    private var downloadJob: Job? = null

    // Touch & zoom matrix for photo viewer
    private val imageMatrix = Matrix()
    private val savedMatrix = Matrix()
    private var scaleDetector: ScaleGestureDetector? = null
    private var startX = 0f
    private var startY = 0f
    private var isDragging = false

    enum class FilterCategory { ALL, VIDEO, PHOTO, EMERGENCY }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (binding.layoutMediaViewer.visibility == View.VISIBLE) {
                closeViewer()
            } else if (adapter.isSelectionMode) {
                exitBatchSelectionMode()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlbumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
        setupRecyclerView()
        setupListeners()
        setupPhotoZoom()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = MediaAdapter(
            onItemClick = { item ->
                openViewer(item)
            },
            onItemLongClick = { _ ->
                enterBatchSelectionMode()
            },
            onFetchThumbnail = { item, imageView ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val file = viewModel.getOrFetchThumbnail(item)
                    if (file != null && imageView.tag == item.id) {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null && imageView.tag == item.id) {
                            imageView.setImageBitmap(bitmap)
                        }
                    }
                }
            },
            onSelectionChanged = { count, bytes ->
                val sizeMb = bytes / (1024 * 1024)
                binding.tvBatchSelectedCount.text = "已選取 $count 項 (${sizeMb} MB)"
                binding.btnBatchDownload.isEnabled = count > 0
                binding.btnBatchDelete.isEnabled = count > 0
            }
        )

        val gridLayoutManager = GridLayoutManager(requireContext(), 2)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == MediaAdapter.TYPE_HEADER) 2 else 1
            }
        }
        binding.rvAlbum.layoutManager = gridLayoutManager
        binding.rvAlbum.adapter = adapter

        // Setup adjacent segments strip under player
        viewerStripAdapter = ViewerStripAdapter(
            onItemClick = { clickedItem ->
                openViewer(clickedItem)
            },
            onFetchThumbnail = { item, imageView ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val file = viewModel.getOrFetchThumbnail(item)
                    if (file != null && imageView.tag == item.id) {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null && imageView.tag == item.id) {
                            imageView.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        )
        binding.rvViewerStrip.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            requireContext(),
            androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
            false
        )
        binding.rvViewerStrip.adapter = viewerStripAdapter
    }

    private fun enterBatchSelectionMode() {
        adapter.setSelectionMode(true)
        binding.layoutNormalHeader.visibility = View.GONE
        binding.layoutBatchHeader.visibility = View.VISIBLE
        binding.layoutBatchBottomBar.visibility = View.VISIBLE
        backPressedCallback.isEnabled = true
    }

    private fun exitBatchSelectionMode() {
        adapter.setSelectionMode(false)
        binding.layoutNormalHeader.visibility = View.VISIBLE
        binding.layoutBatchHeader.visibility = View.GONE
        binding.layoutBatchBottomBar.visibility = View.GONE
        backPressedCallback.isEnabled = binding.layoutMediaViewer.visibility == View.VISIBLE
    }

    private fun triggerRemoteListRefresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.layoutLoadingState.visibility = View.VISIBLE
            binding.layoutEmptyState.visibility = View.GONE
            val res = viewModel.fetchRemoteFileList()
            binding.layoutLoadingState.visibility = View.GONE
            res.onSuccess { list ->
                Toast.makeText(requireContext(), "成功取得記錄器清單: ${list.size} 筆", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(requireContext(), "取得影片清單失敗: ${err.message}", Toast.LENGTH_LONG).show()
                applyFilters()
            }
        }
    }

    private fun setupListeners() {
        binding.toggleAlbumSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (adapter.isSelectionMode) {
                    exitBatchSelectionMode()
                }
                isDeviceSource = (checkedId == R.id.btn_source_device)
                applyFilters()
            }
        }

        binding.toggleViewMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (adapter.isSelectionMode) {
                    exitBatchSelectionMode()
                }
                isTimelineMode = (checkedId == R.id.btn_mode_timeline)
                updateViewModeLayout()
            }
        }

        binding.btnEnterSelect.setOnClickListener {
            enterBatchSelectionMode()
        }

        binding.btnBatchCancel.setOnClickListener {
            exitBatchSelectionMode()
        }

        binding.btnBatchSelectAll.setOnClickListener {
            adapter.selectAll(currentFilteredMediaList)
        }

        binding.btnBatchDownload.setOnClickListener {
            val selected = adapter.getSelectedList()
            if (selected.isEmpty()) return@setOnClickListener
            Toast.makeText(requireContext(), "開始批次下載 ${selected.size} 個檔案", Toast.LENGTH_SHORT).show()
            for (item in selected) {
                if (!item.isDownloaded) {
                    viewModel.startPermanentDownload(item)
                }
            }
            exitBatchSelectionMode()
        }

        binding.btnBatchDelete.setOnClickListener {
            val selected = adapter.getSelectedList()
            if (selected.isEmpty()) return@setOnClickListener
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("確認刪除檔案")
                .setMessage("確定要從 ${if (isDeviceSource) "記錄器 SD 卡" else "本機儲存空間"} 刪除選取的 ${selected.size} 個檔案？此操作無法還原。")
                .setPositiveButton("確定刪除") { _, _ ->
                    executeBatchDelete(selected)
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // Timeline Seek Listeners
        binding.timelineRuler.onTimeSeekListener = { targetMillis, isFinished ->
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(targetMillis))
            val hitItem = binding.timelineRuler.findItemAtCursor()
            if (hitItem != null) {
                binding.tvTimelineCursorInfo.text = "定位到影片：${hitItem.fileName} ($timeStr)"
                binding.btnTimelineJumpNext.visibility = View.GONE
                if (isFinished) {
                    val inClipOffsetMs = if (hitItem.durationSeconds > 0) {
                        (targetMillis - hitItem.timestampMillis).coerceIn(0L, hitItem.durationSeconds * 1000L)
                    } else {
                        0L
                    }
                    openViewer(hitItem, startOffsetMs = inClipOffsetMs)
                }
            } else {
                binding.tvTimelineCursorInfo.text = "此時段無錄影片段 ($timeStr)"
                val nextItem = binding.timelineRuler.findNextItem()
                binding.btnTimelineJumpNext.visibility = if (nextItem != null) View.VISIBLE else View.GONE
                binding.btnTimelineJumpNext.setOnClickListener {
                    nextItem?.let {
                        binding.timelineRuler.seekToTime(it.timestampMillis)
                        openViewer(it, startOffsetMs = 0L)
                    }
                }
            }
        }

        binding.filterAll.setOnClickListener { selectFilter(FilterCategory.ALL) }
        binding.filterVideo.setOnClickListener { selectFilter(FilterCategory.VIDEO) }
        binding.filterPhoto.setOnClickListener { selectFilter(FilterCategory.PHOTO) }
        binding.filterEmergency.setOnClickListener { selectFilter(FilterCategory.EMERGENCY) }

        binding.btnRefreshAlbum.setOnClickListener {
            if (!isDeviceSource) {
                binding.toggleAlbumSource.check(R.id.btn_source_device)
            } else {
                triggerRemoteListRefresh()
            }
        }

        // Viewer controls
        binding.btnViewerBack.setOnClickListener {
            closeViewer()
        }

        binding.btnViewerRetry.setOnClickListener {
            currentViewingItem?.let { openViewer(it) }
        }

        binding.btnViewerDownload.setOnClickListener {
            val item = currentViewingItem ?: return@setOnClickListener
            if (item.isDownloaded) return@setOnClickListener
            startPermanentDownload(item)
        }
    }

    private fun executeBatchDelete(items: List<MediaItem>) {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.layoutLoadingState.visibility = View.VISIBLE
            var successCount = 0
            for (item in items) {
                val ok = if (isDeviceSource) {
                    viewModel.deleteRemoteMedia(item).isSuccess
                } else {
                    viewModel.deleteLocalFile(item)
                }
                if (ok) successCount++
            }
            binding.layoutLoadingState.visibility = View.GONE
            Toast.makeText(requireContext(), "成功刪除 $successCount / ${items.size} 個檔案", Toast.LENGTH_SHORT).show()
            exitBatchSelectionMode()
            if (isDeviceSource) {
                triggerRemoteListRefresh()
            } else {
                applyFilters()
            }
        }
    }

    private fun updateViewModeLayout() {
        if (isTimelineMode) {
            binding.rvAlbum.visibility = View.GONE
            binding.scrollFilterChips.visibility = View.GONE
            binding.layoutTimelineContainer.visibility = View.VISIBLE
            applyTimelineData()
        } else {
            binding.layoutTimelineContainer.visibility = View.GONE
            binding.scrollFilterChips.visibility = View.VISIBLE
            applyFilters()
        }
    }

    private fun selectFilter(filter: FilterCategory) {
        if (adapter.isSelectionMode) {
            exitBatchSelectionMode()
        }
        currentFilter = filter
        val context = requireContext()
        val defaultColor = ContextCompat.getColor(context, R.color.text_secondary)
        val selectedColor = ContextCompat.getColor(context, R.color.accent_teal)

        binding.filterAll.setTextColor(if (filter == FilterCategory.ALL) selectedColor else defaultColor)
        binding.filterVideo.setTextColor(if (filter == FilterCategory.VIDEO) selectedColor else defaultColor)
        binding.filterPhoto.setTextColor(if (filter == FilterCategory.PHOTO) selectedColor else defaultColor)
        binding.filterEmergency.setTextColor(if (filter == FilterCategory.EMERGENCY) selectedColor else defaultColor)

        applyFilters()
    }

    private fun formatGroupHeader(timestamp: Long): String {
        if (timestamp <= 0L) return "時間未知"
        val calNow = java.util.Calendar.getInstance()
        val calItem = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }

        val isSameYear = calNow.get(java.util.Calendar.YEAR) == calItem.get(java.util.Calendar.YEAR)
        val dayDiff = if (isSameYear) {
            calNow.get(java.util.Calendar.DAY_OF_YEAR) - calItem.get(java.util.Calendar.DAY_OF_YEAR)
        } else {
            val days = (calNow.timeInMillis - calItem.timeInMillis) / (24 * 3600_000L)
            days.toInt()
        }

        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
        return when (dayDiff) {
            0 -> "今天 ($ymd)"
            1 -> "昨天 ($ymd)"
            else -> ymd
        }
    }

    private fun applyTimelineData() {
        val rawList = if (isDeviceSource) viewModel.deviceMediaList.value else viewModel.localMediaList.value
        val videoList = rawList.filter { it.isVideo && it.timestampMillis > 0L }
        if (videoList.isEmpty()) {
            binding.containerTimelineDates.removeAllViews()
            binding.tvTimelineCursorInfo.text = "無可用錄影片段"
            binding.btnTimelineJumpNext.visibility = View.GONE
            return
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateMap = videoList.groupBy { dateFormat.format(Date(it.timestampMillis)) }
        binding.containerTimelineDates.removeAllViews()

        val dates = dateMap.keys.sortedDescending()
        val curDate = selectedTimelineDateStr
        if (curDate == null || !dateMap.containsKey(curDate)) {
            selectedTimelineDateStr = dates.firstOrNull()
        }
        val currentSelectedDate = selectedTimelineDateStr ?: return

        for (dateStr in dates) {
            val isSelected = dateStr == currentSelectedDate
            val chip = android.widget.TextView(requireContext()).apply {
                text = dateStr
                textSize = 13f
                setPadding(32, 16, 32, 16)
                setTextColor(ContextCompat.getColor(context, if (isSelected) R.color.accent_teal else R.color.text_secondary))
                setBackgroundResource(if (isSelected) R.drawable.bg_status_badge else 0)
                setOnClickListener {
                    selectedTimelineDateStr = dateStr
                    val items = dateMap[dateStr] ?: emptyList()
                    val targetTimestamp = items.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
                    binding.timelineRuler.setDayMedia(targetTimestamp, items)
                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(binding.timelineRuler.currentCursorMillis))
                    binding.tvTimelineCursorInfo.text = "當前定位時刻：$timeStr (共 ${items.size} 個片段)"
                    applyTimelineData() // refresh chip selection
                }
            }
            binding.containerTimelineDates.addView(chip)
        }

        val itemsForDate = dateMap[currentSelectedDate] ?: emptyList()
        val targetTimestamp = itemsForDate.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        binding.timelineRuler.setDayMedia(targetTimestamp, itemsForDate)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(binding.timelineRuler.currentCursorMillis))
        binding.tvTimelineCursorInfo.text = "當前定位時刻：$timeStr (共 ${itemsForDate.size} 個片段)"
    }

    private fun applyFilters() {
        if (isTimelineMode) {
            applyTimelineData()
            return
        }

        val rawList = if (isDeviceSource) viewModel.deviceMediaList.value else viewModel.localMediaList.value

        // Filter by category
        val filtered = rawList.filter { item ->
            when (currentFilter) {
                FilterCategory.ALL -> true
                FilterCategory.VIDEO -> item.isVideo
                FilterCategory.PHOTO -> !item.isVideo
                FilterCategory.EMERGENCY -> item.isEmergency
            }
        }

        // Sort reliably by recording time descending; unknown times placed after known times
        val sortedList = filtered.sortedWith { a, b ->
            if (a.timestampMillis > 0 && b.timestampMillis > 0) {
                b.timestampMillis.compareTo(a.timestampMillis)
            } else if (a.timestampMillis > 0) {
                -1
            } else if (b.timestampMillis > 0) {
                1
            } else {
                b.fileName.compareTo(a.fileName)
            }
        }
        currentFilteredMediaList = sortedList

        // Group by recording date into AlbumListItem items (Today / Yesterday / YYYY-MM-DD / Unknown)
        val itemsWithHeaders = mutableListOf<AlbumListItem>()
        var lastHeader = ""

        for (item in sortedList) {
            val headerText = formatGroupHeader(item.timestampMillis)
            if (headerText != lastHeader) {
                itemsWithHeaders.add(AlbumListItem.Header(headerText))
                lastHeader = headerText
            }
            itemsWithHeaders.add(AlbumListItem.Media(item))
        }

        adapter.submitList(itemsWithHeaders)

        // Accurate state separation: unqueried vs empty vs filter-no-result
        if (itemsWithHeaders.isEmpty()) {
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.rvAlbum.visibility = View.GONE

            if (isDeviceSource) {
                when (viewModel.albumQueryState.value) {
                    is AlbumQueryState.Success -> {
                        if (rawList.isEmpty()) {
                            binding.tvAlbumEmpty.text = "記錄器記憶卡內無檔案"
                            binding.btnRefreshAlbum.text = "重新整理"
                            binding.btnRefreshAlbum.visibility = View.VISIBLE
                            binding.btnRefreshAlbum.setOnClickListener { triggerRemoteListRefresh() }
                        } else {
                            binding.tvAlbumEmpty.text = "此分類尚無符合之媒體檔案"
                            binding.btnRefreshAlbum.text = "查看全部媒體"
                            binding.btnRefreshAlbum.visibility = View.VISIBLE
                            binding.btnRefreshAlbum.setOnClickListener { selectFilter(FilterCategory.ALL) }
                        }
                    }
                    is AlbumQueryState.Error -> {
                        binding.tvAlbumEmpty.text = "讀取記錄器清單失敗"
                        binding.btnRefreshAlbum.text = "重試讀取"
                        binding.btnRefreshAlbum.visibility = View.VISIBLE
                        binding.btnRefreshAlbum.setOnClickListener { triggerRemoteListRefresh() }
                    }
                    else -> {
                        binding.tvAlbumEmpty.text = "已連接記錄器，尚未讀取影片清單"
                        binding.btnRefreshAlbum.text = "讀取記錄器影片清單"
                        binding.btnRefreshAlbum.visibility = View.VISIBLE
                        binding.btnRefreshAlbum.setOnClickListener { triggerRemoteListRefresh() }
                    }
                }
            } else {
                if (rawList.isEmpty()) {
                    binding.tvAlbumEmpty.text = "本機尚未下載任何影片"
                    binding.btnRefreshAlbum.text = "切換至記錄器相簿"
                    binding.btnRefreshAlbum.visibility = View.VISIBLE
                    binding.btnRefreshAlbum.setOnClickListener {
                        binding.toggleAlbumSource.check(R.id.btn_source_device)
                    }
                } else {
                    binding.tvAlbumEmpty.text = "此分類尚無符合之媒體檔案"
                    binding.btnRefreshAlbum.text = "查看全部媒體"
                    binding.btnRefreshAlbum.visibility = View.VISIBLE
                    binding.btnRefreshAlbum.setOnClickListener { selectFilter(FilterCategory.ALL) }
                }
            }
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.rvAlbum.visibility = View.VISIBLE
        }
    }

    /**
     * Opens media viewer with fast RTSP playback on remote dashcam and offline local playback.
     */
    private fun openViewer(item: MediaItem, startOffsetMs: Long = 0L) {
        currentViewingItem = item
        binding.layoutMediaViewer.visibility = View.VISIBLE
        backPressedCallback.isEnabled = true

        val currentToken = ++previewRequestId
        previewJob?.cancel()
        previewJob = null
        downloadJob?.cancel()
        downloadJob = null

        binding.tvViewerTitle.text = item.fileName
        val timeStr = if (item.timestampMillis > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(item.timestampMillis))
        } else {
            "時間未知"
        }
        binding.tvViewerTime.text = if (item.isVideo) "錄製時間：$timeStr" else "拍攝時間：$timeStr"

        updateViewerDownloadButton(item)

        // Reset player & photo view
        releasePlayer()
        binding.playerView.visibility = View.GONE
        binding.ivPhotoViewer.visibility = View.GONE
        binding.viewerLoadingLayout.visibility = View.GONE
        binding.viewerErrorLayout.visibility = View.GONE

        // Update adjacent segments strip
        val allList = if (isDeviceSource) viewModel.deviceMediaList.value else viewModel.localMediaList.value
        val videoList = allList.filter { it.isVideo }
        if (item.isVideo && videoList.isNotEmpty()) {
            binding.layoutViewerStripContainer.visibility = View.VISIBLE
            viewerStripAdapter.submitList(videoList, item.id)
            val activeIdx = videoList.indexOfFirst { it.id == item.id }
            if (activeIdx >= 0) {
                binding.rvViewerStrip.scrollToPosition(activeIdx)
            }
        } else {
            binding.layoutViewerStripContainer.visibility = View.GONE
        }

        // Check if file is already on local device
        val localFile = viewModel.getLocalMediaFile(item)
        if (localFile != null && localFile.exists()) {
            displayMediaContent(item, localFile, startOffsetMs)
            return
        }

        // Check if temporary viewing cache already exists
        val cacheFile = viewModel.getPreviewCacheFile(item)
        if (cacheFile.exists() && (item.sizeBytes <= 0 || cacheFile.length() == item.sizeBytes)) {
            displayMediaContent(item, cacheFile, startOffsetMs)
            return
        }

        // Fast Remote Playback: Stream via RTSP directly from DashCam
        if (isDeviceSource && item.isVideo) {
            binding.viewerLoadingLayout.visibility = View.VISIBLE
            binding.tvViewerLoadingText.text = "正在啟動快速開片串流..."

            previewJob = viewLifecycleOwner.lifecycleScope.launch {
                // 先向 ViewModel 申請媒體所有權，使 LiveFragment 釋放 8080 RTSP 端口
                viewModel.requestStreamOwner(DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK)
                kotlinx.coroutines.delay(200)

                val streamResult = viewModel.startRemotePlayback(item)
                if (!isAdded || previewRequestId != currentToken || currentViewingItem?.fileName != item.fileName) {
                    viewModel.releaseStreamOwner(DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK)
                    return@launch
                }

                streamResult.onSuccess {
                    val host = viewModel.deviceHost.value
                    val network = viewModel.wifiNetwork.value
                    if (network != null) {
                        try {
                            sdpProxy?.stop()
                            val devId = "${host}@${viewModel.targetIdentity.value.epoch}"
                            val proxy = com.aladdin.dashcam.network.RtspSdpProxy(host, 8080, network, deviceIdentifier = devId)
                            val localPort = proxy.start()
                            sdpProxy = proxy
                            binding.viewerLoadingLayout.visibility = View.GONE
                            setupExoPlayerStream("rtsp://127.0.0.1:$localPort/?action=stream", startOffsetMs, item, currentToken)
                        } catch (e: Exception) {
                            binding.viewerLoadingLayout.visibility = View.GONE
                            fetchPreviewFallback(item, currentToken, startOffsetMs)
                        }
                    } else {
                        binding.viewerLoadingLayout.visibility = View.GONE
                        fetchPreviewFallback(item, currentToken, startOffsetMs)
                    }
                }.onFailure { _ ->
                    binding.viewerLoadingLayout.visibility = View.GONE
                    // Fallback to preview cache download if stream handshake rejected
                    fetchPreviewFallback(item, currentToken, startOffsetMs)
                }
            }
            return
        }

        // Remote Photo fetch or fallback
        fetchPreviewFallback(item, currentToken, startOffsetMs)
    }

    private fun fetchPreviewFallback(item: MediaItem, currentToken: Long, startOffsetMs: Long = 0L) {
        binding.viewerLoadingLayout.visibility = View.VISIBLE
        binding.tvViewerLoadingText.text = "正在讀取此檔案..."

        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            val res = viewModel.fetchPreviewCache(item) { bytesRead ->
                if (!isAdded || previewRequestId != currentToken || currentViewingItem?.fileName != item.fileName) return@fetchPreviewCache
                val progress = if (item.sizeBytes > 0) {
                    ((bytesRead * 100) / item.sizeBytes).toInt().coerceIn(0, 100)
                } else 0
                binding.tvViewerLoadingText.text = if (progress > 0) "讀取中: $progress%" else "正在讀取..."
            }

            if (!isAdded || previewRequestId != currentToken || currentViewingItem?.fileName != item.fileName) return@launch

            binding.viewerLoadingLayout.visibility = View.GONE
            res.onSuccess { file ->
                if (isAdded && previewRequestId == currentToken && currentViewingItem?.fileName == item.fileName) {
                    displayMediaContent(item, file, startOffsetMs)
                }
            }.onFailure { err ->
                if (isAdded && previewRequestId == currentToken && currentViewingItem?.fileName == item.fileName) {
                    binding.viewerErrorLayout.visibility = View.VISIBLE
                    binding.tvViewerErrorMsg.text = "載入失敗：${err.message}"
                }
            }
        }
    }

    private fun setupExoPlayerStream(streamUri: String, startOffsetMs: Long = 0L, item: MediaItem, currentToken: Long) {
        // N01: Only release previous exoPlayer instance, do NOT kill active sdpProxy
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        binding.playerView.player = null

        val player = ExoPlayer.Builder(requireContext()).build()
        binding.playerView.player = player
        binding.playerView.visibility = View.VISIBLE
        binding.ivPhotoViewer.visibility = View.GONE

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("AlbumFragment", "RTSP stream playback failed: ${error.message}, falling back to preview download")
                if (isAdded && previewRequestId == currentToken && currentViewingItem?.fileName == item.fileName) {
                    releasePlayer()
                    fetchPreviewFallback(item, currentToken, startOffsetMs)
                } else {
                    binding.viewerErrorLayout.visibility = View.VISIBLE
                    binding.tvViewerErrorMsg.text = "影片串流播放中斷：${error.message ?: "無法讀取串流"}"
                }
            }
        })

        val factory = androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
        val mediaSource = factory.createMediaSource(ExoMediaItem.fromUri(streamUri))
        player.setMediaSource(mediaSource)
        if (startOffsetMs > 0L) {
            player.seekTo(startOffsetMs)
        }
        player.prepare()
        player.playWhenReady = true
        exoPlayer = player
    }

    private fun displayMediaContent(item: MediaItem, file: File, startOffsetMs: Long = 0L) {
        if (item.isVideo) {
            binding.playerView.visibility = View.VISIBLE
            binding.ivPhotoViewer.visibility = View.GONE
            setupExoPlayer(file, startOffsetMs)
        } else {
            binding.playerView.visibility = View.GONE
            binding.ivPhotoViewer.visibility = View.VISIBLE
            displayPhoto(file)
        }
    }

    private fun setupExoPlayer(file: File, startOffsetMs: Long = 0L) {
        releasePlayer()
        val player = ExoPlayer.Builder(requireContext()).build()
        binding.playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                binding.viewerErrorLayout.visibility = View.VISIBLE
                binding.tvViewerErrorMsg.text = "影片播放失敗：${error.message ?: "無法解碼影片"}"
            }
        })
        val mediaItem = ExoMediaItem.fromUri(Uri.fromFile(file))
        player.setMediaItem(mediaItem)
        if (startOffsetMs > 0L) {
            player.seekTo(startOffsetMs)
        }
        player.prepare()
        player.playWhenReady = true
        exoPlayer = player
    }

    private fun displayPhoto(file: File) {
        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                binding.ivPhotoViewer.setImageBitmap(bitmap)
                applyFitCenterMatrix(bitmap)
            } else {
                binding.viewerErrorLayout.visibility = View.VISIBLE
                binding.tvViewerErrorMsg.text = "無法解析照片檔案"
            }
        } catch (e: Exception) {
            binding.viewerErrorLayout.visibility = View.VISIBLE
            binding.tvViewerErrorMsg.text = "照片載入失敗：${e.message}"
        }
    }

    /**
     * Calculates fit-center matrix preserving aspect ratio and centering photo in viewer (C3)
     */
    private fun applyFitCenterMatrix(bitmap: android.graphics.Bitmap) {
        val view = binding.ivPhotoViewer
        view.post {
            if (_binding == null) return@post
            val viewWidth = view.width.toFloat()
            val viewHeight = view.height.toFloat()
            val bmWidth = bitmap.width.toFloat()
            val bmHeight = bitmap.height.toFloat()

            if (viewWidth <= 0f || viewHeight <= 0f || bmWidth <= 0f || bmHeight <= 0f) {
                imageMatrix.reset()
                view.imageMatrix = imageMatrix
                savedMatrix.set(imageMatrix)
                return@post
            }

            val scale = minOf(viewWidth / bmWidth, viewHeight / bmHeight)
            val dx = (viewWidth - bmWidth * scale) / 2f
            val dy = (viewHeight - bmHeight * scale) / 2f

            imageMatrix.reset()
            imageMatrix.postScale(scale, scale)
            imageMatrix.postTranslate(dx, dy)
            view.imageMatrix = imageMatrix
            savedMatrix.set(imageMatrix)
        }
    }

    /**
     * Pinch-to-zoom and pan support for photo viewer
     */
    private fun setupPhotoZoom() {
        scaleDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                imageMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                binding.ivPhotoViewer.imageMatrix = imageMatrix
                return true
            }
        })

        binding.ivPhotoViewer.setOnTouchListener { _, event ->
            scaleDetector?.onTouchEvent(event)
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    savedMatrix.set(imageMatrix)
                    startX = event.x
                    startY = event.y
                    isDragging = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging && event.pointerCount == 1) {
                        imageMatrix.set(savedMatrix)
                        imageMatrix.postTranslate(event.x - startX, event.y - startY)
                        binding.ivPhotoViewer.imageMatrix = imageMatrix
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    isDragging = false
                }
            }
            true
        }
    }

    /**
     * Starts user-confirmed permanent download to local storage.
     * Delegates to DashCamViewModel's unified startPermanentDownload (F1 / Single Ownership).
     */
    private fun startPermanentDownload(item: MediaItem) {
        if (downloadJob?.isActive == true) return

        binding.btnViewerDownload.isEnabled = false
        binding.btnViewerDownload.text = "下載中..."

        downloadJob = viewModel.startPermanentDownload(item)
    }

    private fun updateViewerDownloadButton(item: MediaItem) {
        if (item.isDownloaded) {
            binding.btnViewerDownload.isEnabled = false
            binding.btnViewerDownload.text = "已下載"
            binding.btnViewerDownload.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface_elevated))
            binding.btnViewerDownload.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        } else {
            binding.btnViewerDownload.isEnabled = true
            binding.btnViewerDownload.text = "下載到本機"
            binding.btnViewerDownload.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.accent_teal))
            binding.btnViewerDownload.setTextColor(ContextCompat.getColor(requireContext(), R.color.bg_dark))
        }
    }

    private fun closeViewer() {
        ++previewRequestId // Invalidate any in-flight preview requests (R2)
        previewJob?.cancel()
        previewJob = null
        downloadJob = null
        releasePlayer()
        currentViewingItem = null
        binding.layoutMediaViewer.visibility = View.GONE
        backPressedCallback.isEnabled = false

        if (isDeviceSource) {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.stopRemotePlayback()
                viewModel.releaseStreamOwner(DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK)
            }
        }
    }

    private fun releasePlayer() {
        sdpProxy?.stop()
        sdpProxy = null
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        binding.playerView.player = null
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.deviceMediaList.collect {
                        if (isDeviceSource) applyFilters()
                    }
                }
                launch {
                    viewModel.localMediaList.collect {
                        if (!isDeviceSource) applyFilters()
                    }
                }
                launch {
                    viewModel.downloadTasks.collect { tasks ->
                        val current = currentViewingItem ?: return@collect
                        val task = tasks.find { it.id == current.fileName } ?: return@collect
                        when (task.status) {
                            DownloadStatus.COMPLETED -> {
                                val updated = current.copy(isDownloaded = true)
                                currentViewingItem = updated
                                updateViewerDownloadButton(updated)
                            }
                            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                                binding.btnViewerDownload.isEnabled = true
                                binding.btnViewerDownload.text = "下載到本機"
                                binding.btnViewerDownload.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.accent_teal))
                                binding.btnViewerDownload.setTextColor(ContextCompat.getColor(requireContext(), R.color.bg_dark))
                            }
                            DownloadStatus.DOWNLOADING -> {
                                binding.btnViewerDownload.isEnabled = false
                                val progress = if (task.totalBytes > 0) {
                                    ((task.bytesRead * 100) / task.totalBytes).toInt().coerceIn(0, 100)
                                } else 0
                                binding.btnViewerDownload.text = if (progress > 0) "下載中 $progress%" else "下載中..."
                                binding.btnViewerDownload.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface_elevated))
                                binding.btnViewerDownload.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                            }
                            DownloadStatus.PENDING -> {
                                binding.btnViewerDownload.isEnabled = false
                                binding.btnViewerDownload.text = "等待下載..."
                            }
                        }
                    }
                }
                launch {
                    viewModel.openMediaItemEvent.collect { item ->
                        if (isDeviceSource) {
                            binding.toggleAlbumSource.check(R.id.btn_source_local)
                        }
                        openViewer(item)
                    }
                }
                launch {
                    viewModel.albumQueryState.collect { state ->
                        when (state) {
                            is AlbumQueryState.Idle -> {
                                binding.layoutLoadingState.visibility = View.GONE
                                if (isDeviceSource && viewModel.deviceMediaList.value.isEmpty()) {
                                    applyFilters()
                                }
                            }
                            is AlbumQueryState.Loading -> {
                                binding.layoutLoadingState.visibility = View.VISIBLE
                                binding.layoutEmptyState.visibility = View.GONE
                                binding.rvAlbum.visibility = View.GONE
                            }
                            is AlbumQueryState.Success -> {
                                binding.layoutLoadingState.visibility = View.GONE
                                applyFilters()
                            }
                            is AlbumQueryState.Error -> {
                                binding.layoutLoadingState.visibility = View.GONE
                                applyFilters()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (viewModel.currentStreamOwner.value == DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK) {
            viewModel.releaseStreamOwner(DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK)
        }
        ++previewRequestId
        previewJob?.cancel()
        previewJob = null
        downloadJob = null
        currentViewingItem = null
        releasePlayer()
        _binding = null
    }
}
