package com.aladdin.dashcam.ui

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.GridLayoutManager
import com.aladdin.dashcam.R
import com.aladdin.dashcam.databinding.FragmentAlbumBinding
import com.aladdin.dashcam.model.MediaItem
import com.aladdin.dashcam.model.ConnectionStatus
import com.aladdin.dashcam.model.DeviceMode
import com.aladdin.dashcam.model.RecordingStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
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

    fun showLocalMedia() {
        if (_binding != null) binding.toggleAlbumSource.check(R.id.btn_source_local)
        else isDeviceSource = false
    }
    fun showDeviceMedia() {
        if (_binding != null) binding.toggleAlbumSource.check(R.id.btn_source_device)
        else isDeviceSource = true
        if (_binding != null && !isHidden && viewModel.connectionStatus.value == com.aladdin.dashcam.model.ConnectionStatus.CONNECTED) {
            triggerRemoteListRefresh()
        }
    }
    private var isTimelineMode = false
    private var currentFilter = FilterCategory.ALL
    private var currentFilteredMediaList: List<MediaItem> = emptyList()
    private var selectedTimelineDateStr: String? = null

    // Media viewer & player state
    private var nativePlayer: com.aladdin.dashcam.media.NativeFfmpegPlayer? = null
    private var remotePlayback = false
    private var videoDurationMs = 0L
    private var seekingVideo = false
    private var currentViewingItem: MediaItem? = null
    private var previewJob: Job? = null
    private var playbackCommandJob: Job? = null
    private var previewRequestId = 0L
    private var downloadJob: Job? = null
    private var listRefreshJob: Job? = null
    private var albumEnterJob: Deferred<Result<Unit>>? = null
    private var albumExitJob: Job? = null
    private var playbackStopJob: Job? = null
    private var protectedPreviewFile: File? = null
    private var previewStartedAt = 0L

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
        initVideoPlayer()
        setupRecyclerView()
        setupListeners()
        updateResponsiveControls(resources.configuration)
        if (!isDeviceSource) binding.toggleAlbumSource.check(R.id.btn_source_local)
        setupPhotoZoom()
        observeViewModel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null) {
            updateResponsiveControls(newConfig)
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
            updateViewerOrientation(newConfig)
        }
    }

    private fun updateResponsiveControls(config: Configuration) {
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.layoutNormalHeader.visibility = if (landscape || adapter.isSelectionMode) View.GONE else View.VISIBLE
        binding.layoutFilterControls.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        binding.scrollFilterChips.layoutParams = LinearLayout.LayoutParams(
            if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (landscape) 1f else 0f
        )
        binding.toggleViewMode.layoutParams = LinearLayout.LayoutParams(
            if (landscape) (220 * resources.displayMetrics.density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = if (landscape) 0 else (8 * resources.displayMetrics.density).toInt() }
    }

    private fun updateViewerOrientation(config: Configuration) {
        val viewingVideo = currentViewingItem?.isVideo == true
        (activity as? MainActivity)?.setAlbumPreviewExpanded(
            currentViewingItem != null && !isHidden && config.orientation == Configuration.ORIENTATION_LANDSCAPE
        )
        binding.layoutViewerStripContainer.visibility = if (viewingVideo && config.orientation != Configuration.ORIENTATION_LANDSCAPE) View.VISIBLE else View.GONE
        binding.viewerTopBar.visibility = View.VISIBLE
        binding.layoutViewerControls.visibility = if (viewingVideo) View.VISIBLE else View.GONE
        (binding.layoutViewerControls.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin =
            if (binding.layoutViewerStripContainer.visibility == View.VISIBLE) (112 * resources.displayMetrics.density).toInt() else 0
        binding.layoutMediaViewer.doOnLayout {
            if (_binding != null && currentViewingItem?.isVideo == true) {
                val stripHeight = if (binding.layoutViewerStripContainer.visibility == View.VISIBLE) binding.layoutViewerStripContainer.height else 0
                val controls = binding.layoutViewerControls.layoutParams as android.widget.FrameLayout.LayoutParams
                controls.bottomMargin = stripHeight
                binding.layoutViewerControls.layoutParams = controls
                val video = binding.glVideoView.layoutParams as android.widget.FrameLayout.LayoutParams
                video.topMargin = if (binding.viewerTopBar.visibility == View.VISIBLE) binding.viewerTopBar.height else 0
                video.bottomMargin = stripHeight + binding.layoutViewerControls.height
                binding.glVideoView.layoutParams = video
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden && _binding != null && currentViewingItem != null) closeViewer()
        if (_binding != null) updateViewerOrientation(resources.configuration)
    }

    override fun onResume() {
        super.onResume()
        if (isDeviceSource && viewModel.connectionStatus.value == com.aladdin.dashcam.model.ConnectionStatus.CONNECTED) {
            triggerRemoteListRefresh()
        }
    }

    private val thumbnailJobs = mutableMapOf<android.widget.ImageView, Job>()

    private fun loadThumbnail(item: MediaItem, imageView: android.widget.ImageView) {
        val cached = viewModel.getCachedThumbnailBitmap(item)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        thumbnailJobs.remove(imageView)?.cancel()
        thumbnailJobs[imageView] = viewLifecycleOwner.lifecycleScope.launch {
            val file = viewModel.getOrFetchThumbnail(item)
            if (file != null && imageView.tag == item.id) {
                val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
                if (bitmap != null) {
                    viewModel.putCachedThumbnailBitmap(item, bitmap)
                    if (imageView.tag == item.id) imageView.setImageBitmap(bitmap)
                }
            }
        }
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
                loadThumbnail(item, imageView)
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
                loadThumbnail(item, imageView)
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
        updateResponsiveControls(resources.configuration)
        binding.layoutBatchHeader.visibility = View.GONE
        binding.layoutBatchBottomBar.visibility = View.GONE
        backPressedCallback.isEnabled = binding.layoutMediaViewer.visibility == View.VISIBLE
    }

    private fun triggerRemoteListRefresh() {
        if (!isDeviceSource || isHidden || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (listRefreshJob?.isActive == true) return
        listRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.layoutLoadingState.visibility = View.VISIBLE
            binding.layoutEmptyState.visibility = View.GONE
            albumExitJob?.join()
            binding.tvAlbumRecordingPaused.text = "切換裝置相簿中 · 錄影將暫停"
            binding.tvAlbumRecordingPaused.visibility = View.VISIBLE
            val enterJob = albumEnterJob ?: viewModel.viewModelScope.async {
                viewModel.enterRemoteAlbumBrowsing()
            }.also { albumEnterJob = it }
            val enterResult = enterJob.await()
            if (enterResult.isFailure) {
                albumEnterJob = null
                binding.tvAlbumRecordingPaused.visibility = View.GONE
                binding.layoutLoadingState.visibility = View.GONE
                Toast.makeText(requireContext(), "無法進入裝置相簿：${enterResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            updateAlbumRecordingStatus()
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
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

    private fun updateAlbumRecordingStatus() {
        if (!isDeviceSource || isHidden || albumEnterJob == null || albumExitJob?.isActive == true) {
            binding.tvAlbumRecordingPaused.visibility = View.GONE
            return
        }
        val status = viewModel.deviceStatus.value
        val ageMs = System.currentTimeMillis() - status.lastUpdatedTimestamp
        binding.tvAlbumRecordingPaused.text = when {
            viewModel.connectionStatus.value != ConnectionStatus.CONNECTED -> "記錄器未連線 · 錄影狀態未知"
            albumEnterJob?.isActive == true -> "切換裝置相簿中 · 錄影將暫停"
            status.lastUpdatedTimestamp <= 0L || ageMs !in 0L..10_000L -> "錄影狀態未知 · 等待設備回報"
            status.mode == DeviceMode.RECORD && status.recordingStatus == RecordingStatus.RECORDING ->
                "記錄器錄影中 · 裝置相簿模式已中斷"
            status.mode == DeviceMode.PLAYBACK -> "設備處於回放模式 · 錄影已暫停"
            status.recordingStatus == RecordingStatus.STOPPED -> "記錄器目前未錄影"
            else -> "錄影狀態未知 · 等待設備回報"
        }
        binding.tvAlbumRecordingPaused.visibility = View.VISIBLE
    }

    private fun leaveDeviceAlbum() {
        binding.tvAlbumRecordingPaused.visibility = View.GONE
        if (albumExitJob?.isActive == true) return
        val pendingEnter = albumEnterJob
        val pendingPlaybackStop = playbackStopJob
        albumExitJob = viewModel.viewModelScope.launch {
            pendingEnter?.join()
            pendingPlaybackStop?.join()
            val result = viewModel.exitRemoteAlbumBrowsing()
            albumEnterJob = null
            if (result.isFailure && isAdded) {
                Toast.makeText(context, "未確認恢復錄影：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initVideoPlayer() {
        nativePlayer = com.aladdin.dashcam.media.NativeFfmpegPlayer(
            binding.glVideoView,
            onFirstFrameRendered = {
                if (_binding != null && currentViewingItem?.isVideo == true) {
                    binding.viewerLoadingLayout.visibility = View.GONE
                    binding.btnVideoPlayPause.isEnabled = true
                    binding.btnVideoPlayPause.text = "暫停"
                    android.util.Log.i("AlbumFragment", "FFmpeg file frame index=${currentViewingItem?.deviceIndex}, elapsedMs=${android.os.SystemClock.elapsedRealtime() - previewStartedAt}")
                }
            },
            onError = { error -> if (_binding != null) showPlaybackError(previewRequestId, error) },
            onProgress = { position, duration ->
                if (_binding != null && currentViewingItem?.isVideo == true) {
                    videoDurationMs = duration
                    val positionText = formatVideoTime(position)
                    binding.tvVideoPosition.text = if (remotePlayback) {
                        "$positionText · 裝置回放（精確跳轉需先下載）"
                    } else {
                        "$positionText / ${if (duration > 0L) formatVideoTime(duration) else "時長未知"}"
                    }
                    binding.seekVideo.isEnabled = !remotePlayback && duration > 0L
                    if (!seekingVideo && duration > 0L) {
                        binding.seekVideo.progress = (position * 1000L / duration).toInt().coerceIn(0, 1000)
                    }
                }
            },
            onEnded = {
                if (_binding != null) {
                    binding.btnVideoPlayPause.text = "重播"
                    binding.btnVideoPlayPause.isEnabled = true
                }
            }
        )
        binding.glVideoView.setOnClickListener {
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                binding.viewerTopBar.visibility = if (binding.viewerTopBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        binding.btnVideoPlayPause.setOnClickListener {
            val item = currentViewingItem ?: return@setOnClickListener
            val native = nativePlayer ?: return@setOnClickListener
            if (!native.hasSource) {
                openViewer(item)
                return@setOnClickListener
            }
            val token = previewRequestId
            val pause = native.isPlaying
            if (!pause && remotePlayback && native.needsRestart) {
                Toast.makeText(context, "設備已結束暫停的串流，將從片頭重新播放", Toast.LENGTH_LONG).show()
                openViewer(item)
                return@setOnClickListener
            }
            binding.btnVideoPlayPause.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    if (remotePlayback) viewModel.toggleRemotePlaybackPause(item).getOrThrow()
                    if (previewRequestId != token) return@launch
                    native.setPaused(pause, controlSource = !remotePlayback)
                    binding.btnVideoPlayPause.text = if (pause) "播放" else "暫停"
                } catch (error: Exception) {
                    if (previewRequestId == token) showPlaybackError(token, error.message ?: "暫停狀態未確認，請重新開啟影片")
                } finally {
                    if (_binding != null && previewRequestId == token) binding.btnVideoPlayPause.isEnabled = true
                }
            }
        }
        binding.seekVideo.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) { seekingVideo = true }
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                seekingVideo = false
                if (remotePlayback || videoDurationMs <= 0L) return
                val token = previewRequestId
                val targetMs = (seekBar?.progress ?: 0) * videoDurationMs / 1000L
                viewLifecycleOwner.lifecycleScope.launch {
                    if (nativePlayer?.seekTo(targetMs) != true && _binding != null && token == previewRequestId) {
                        Toast.makeText(context, "無法跳轉此影片", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun formatVideoTime(positionMs: Long): String {
        val seconds = positionMs.coerceAtLeast(0L) / 1000L
        return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60L, seconds % 60L)
    }
    private fun setupListeners() {
        binding.toggleAlbumSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (adapter.isSelectionMode) {
                    exitBatchSelectionMode()
                }
                val wasDeviceSource = isDeviceSource
                isDeviceSource = (checkedId == R.id.btn_source_device)
                if (wasDeviceSource && !isDeviceSource) leaveDeviceAlbum()
                if (isDeviceSource && viewModel.connectionStatus.value == com.aladdin.dashcam.model.ConnectionStatus.CONNECTED) {
                    triggerRemoteListRefresh()
                } else if (!isDeviceSource) viewModel.loadLocalMedia()
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
                b.deviceIndex.compareTo(a.deviceIndex)
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
        playbackCommandJob?.cancel()
        if (remotePlayback) {
            playbackStopJob = viewModel.viewModelScope.launch { viewModel.stopRemotePlayback() }
        }
        remotePlayback = false
        videoDurationMs = 0L
        seekingVideo = false
        previewStartedAt = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("AlbumFragment", "Open media index=${item.deviceIndex}, file=${item.fileName}")
        thumbnailJobs.values.forEach { it.cancel() }
        thumbnailJobs.clear()
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
        releasePreviewProtection()
        binding.glVideoView.visibility = View.GONE
        binding.ivPhotoViewer.visibility = View.GONE
        binding.ivPhotoViewer.setImageDrawable(null)
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
        updateViewerOrientation(resources.configuration)

        // Check if file is already on local device
        val localFile = viewModel.getLocalMediaFile(item)
        if (localFile != null && localFile.exists()) {
            displayMediaContent(item, localFile, startOffsetMs)
            return
        }

        // Check if temporary viewing cache already exists
        val cacheFile = viewModel.getPreviewCacheFile(item)
        viewModel.mediaCacheManager.protectFile(cacheFile)
        protectedPreviewFile = cacheFile
        if (cacheFile.exists() && (item.sizeBytes <= 0 || cacheFile.length() == item.sizeBytes)) {
            displayMediaContent(item, cacheFile, startOffsetMs)
            return
        }

        if (isDeviceSource && item.isVideo) {
            remotePlayback = true
            binding.viewerLoadingLayout.visibility = View.VISIBLE
            binding.tvViewerLoadingText.text = "正在開啟錄影檔（回放時暫停錄影）…"
            binding.glVideoView.visibility = View.VISIBLE
            binding.layoutViewerControls.visibility = View.VISIBLE
            binding.seekVideo.isEnabled = false
            binding.btnVideoPlayPause.isEnabled = false
            if (startOffsetMs > 0L) Toast.makeText(context, "裝置回放從片頭開始；精確跳轉請先下載影片", Toast.LENGTH_LONG).show()
            previewJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    viewModel.requestStreamOwner(DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK)
                    playbackStopJob?.join()
                    val network = viewModel.wifiNetwork.value ?: error("記錄器 Wi-Fi 已中斷")
                    val sent = kotlinx.coroutines.CompletableDeferred<Unit>()
                    playbackCommandJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val result = runCatching {
                            viewModel.restartStreaming().getOrThrow()
                            viewModel.startRemotePlayback(item) { sent.complete(Unit) }.getOrThrow()
                        }
                        result.exceptionOrNull()?.let { error ->
                            sent.completeExceptionally(error)
                            withContext(Dispatchers.Main) {
                                if (_binding != null && previewRequestId == currentToken) {
                                    nativePlayer?.stop()
                                    showPlaybackError(currentToken, error.message ?: "記錄器拒絕回放")
                                }
                            }
                        }
                    }
                    kotlinx.coroutines.withTimeout(20_000L) { sent.await() }
                    if (previewRequestId == currentToken) {
                        nativePlayer?.start("rtsp://${viewModel.deviceHost.value}:8080/?action=stream",
                            targetNetwork = network, knownDurationMs = item.durationSeconds.toLong() * 1000L)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (_binding != null && previewRequestId == currentToken) {
                        nativePlayer?.stop()
                        showPlaybackError(currentToken, error.message ?: "錄影檔傳輸中斷")
                    }
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

    private fun showPlaybackError(token: Long, message: String) {
        if (_binding == null || previewRequestId != token) return
        binding.viewerLoadingLayout.visibility = View.GONE
        if (isDeviceSource && currentViewingItem?.isVideo == true) {
            binding.ivPhotoViewer.setImageDrawable(null)
            binding.ivPhotoViewer.visibility = View.GONE
        }
        nativePlayer?.stop()
        binding.glVideoView.visibility = View.GONE
        binding.btnVideoPlayPause.text = "重試"
        binding.btnVideoPlayPause.isEnabled = true
        binding.viewerErrorLayout.visibility = View.VISIBLE
        binding.tvViewerErrorMsg.text = "回放失敗：$message"
    }

    private fun displayMediaContent(item: MediaItem, file: File, startOffsetMs: Long = 0L) {
        if (item.isVideo) {
            binding.glVideoView.visibility = View.VISIBLE
            binding.ivPhotoViewer.visibility = View.GONE
            setupNativePlayer(file, startOffsetMs)
        } else {
            binding.glVideoView.visibility = View.GONE
            binding.ivPhotoViewer.visibility = View.VISIBLE
            binding.ivPhotoViewer.scaleType = android.widget.ImageView.ScaleType.MATRIX
            displayPhoto(file)
        }
    }

    private fun setupNativePlayer(file: File, startOffsetMs: Long = 0L) {
        val item = currentViewingItem ?: return
        val token = previewRequestId
        remotePlayback = false
        binding.layoutViewerControls.visibility = View.VISIBLE
        binding.viewerLoadingLayout.visibility = View.VISIBLE
        binding.tvViewerLoadingText.text = "正在開啟本機影片…"
        binding.btnVideoPlayPause.isEnabled = false
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.requestStreamOwner(DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK)
            playbackStopJob?.join()
            if (token == previewRequestId) {
                nativePlayer?.start(file.absolutePath, streaming = false, startPositionMs = startOffsetMs,
                    knownDurationMs = item.durationSeconds.toLong() * 1000L)
            }
        }
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
            if (currentViewingItem?.isVideo == true) {
                if (event.action == MotionEvent.ACTION_UP && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    binding.viewerTopBar.visibility = if (binding.viewerTopBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
                return@setOnTouchListener true
            }
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
        playbackCommandJob?.cancel()
        ++previewRequestId // Invalidate any in-flight preview requests (R2)
        previewJob?.cancel()
        previewJob = null
        downloadJob = null
        releasePlayer()
        remotePlayback = false
        releasePreviewProtection()
        currentViewingItem = null
        (activity as? MainActivity)?.setAlbumPreviewExpanded(false)
        binding.layoutMediaViewer.visibility = View.GONE
        backPressedCallback.isEnabled = false

        if (isDeviceSource) {
            playbackStopJob = viewModel.viewModelScope.launch {
                val result = viewModel.stopRemotePlayback()
                if (currentViewingItem == null) viewModel.releaseStreamOwner(DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK)
                if (result.isFailure && isAdded) Toast.makeText(context, "回放已關閉，但未確認恢復錄影：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            viewModel.releaseStreamOwner(DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK)
        }
    }

    private fun releasePlayer() {
        nativePlayer?.stop()
    }

    private fun releasePreviewProtection() {
        val file = protectedPreviewFile ?: return
        protectedPreviewFile = null
        viewModel.mediaCacheManager.unprotectFile(file)
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            file.parentFile?.parentFile?.let { directory ->
                viewModel.mediaCacheManager.evictDirectoryIfExceeds(
                    directory,
                    viewModel.mediaCacheManager.previewQuotaBytes,
                    viewModel.mediaCacheManager.previewLowWatermarkBytes
                )
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.deviceStatus, viewModel.connectionStatus) { _, _ -> Unit }
                        .collectLatest {
                            updateAlbumRecordingStatus()
                            delay(10_001L)
                            updateAlbumRecordingStatus()
                        }
                }
                launch {
                    viewModel.connectionStatus.collect { status ->
                        if (currentViewingItem?.isVideo == true && remotePlayback && nativePlayer != null &&
                            status != com.aladdin.dashcam.model.ConnectionStatus.CONNECTED) {
                            val disconnectedToken = ++previewRequestId
                            previewJob?.cancel()
                            previewJob = null
                            playbackCommandJob?.cancel()
                            nativePlayer?.stop()
                            binding.ivPhotoViewer.setImageDrawable(null)
                            showPlaybackError(disconnectedToken, "記錄器連線已中斷，請重新連線後再試")
                        }
                        if (isDeviceSource && status == com.aladdin.dashcam.model.ConnectionStatus.CONNECTED &&
                            viewModel.albumQueryState.value is AlbumQueryState.Idle) {
                            triggerRemoteListRefresh()
                        }
                    }
                }
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
                        val task = tasks.find { it.id == current.storageKey } ?: return@collect
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
        if (_binding != null && currentViewingItem != null) closeViewer()
        if (_binding != null && isDeviceSource) {
            listRefreshJob?.cancel()
            thumbnailJobs.values.forEach { it.cancel() }
            thumbnailJobs.clear()
            leaveDeviceAlbum()
        }
    }

    override fun onDestroyView() {
        if (_binding != null && currentViewingItem != null) closeViewer()
        playbackCommandJob?.cancel()
        thumbnailJobs.values.forEach { it.cancel() }
        thumbnailJobs.clear()
        super.onDestroyView()
        ++previewRequestId
        previewJob?.cancel()
        previewJob = null
        downloadJob = null
        currentViewingItem = null
        (activity as? MainActivity)?.setAlbumPreviewExpanded(false)
        nativePlayer?.release()
        nativePlayer = null
        releasePreviewProtection()
        _binding = null
    }
}
