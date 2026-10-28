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
    private companion object {
        const val REMOTE_STOP_GRACE_MS = 300L
    }
    private var _binding: FragmentAlbumBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashCamViewModel by lazy { (requireActivity().application as com.aladdin.dashcam.AladdinApp).recorder }
    private lateinit var adapter: MediaAdapter
    private lateinit var viewerStripAdapter: ViewerStripAdapter
    private var segmentsExpanded = false

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
    private var sortOrder = 0
    private var currentFilteredMediaList: List<MediaItem> = emptyList()
    private var selectedTimelineDateStr: String? = null
    private var timelineItems: List<MediaItem> = emptyList()
    private var timelineSelectedItemId: String? = null

    // Media viewer & player state
    private var nativePlayer: com.aladdin.dashcam.media.NativeFfmpegPlayer? = null
    private var remotePlayback = false
    private var videoDurationMs = 0L
    private var videoPositionMs = 0L
    private var seekingVideo = false
    private var currentViewingItem: MediaItem? = null
    private var previewJob: Job? = null
    private var playbackCommandJob: Job? = null
    private var previewRequestId = 0L
    private var downloadJob: Job? = null
    private var reportJob: Job? = null
    private var reportButton: android.widget.Button? = null
    private var listRefreshJob: Job? = null
    private var albumEnterJob: Deferred<Result<Unit>>? = null
    private var albumExitJob: Job? = null
    private var controlsHideJob: Job? = null
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
        binding.layoutFilterControls.orientation = LinearLayout.HORIZONTAL
        binding.scrollFilterChips.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
        binding.toggleViewMode.layoutParams = LinearLayout.LayoutParams(
            (96 * resources.displayMetrics.density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun setViewerControlsVisible(visible: Boolean) {
        if (_binding == null) return
        binding.viewerTopBar.visibility = if (visible) View.VISIBLE else View.GONE
        binding.layoutViewerControls.visibility = if (visible && currentViewingItem?.isVideo == true) View.VISIBLE else View.GONE
        val video = binding.glVideoView.layoutParams as android.widget.FrameLayout.LayoutParams
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            video.topMargin = 0; video.bottomMargin = 0
            binding.glVideoView.layoutParams = video
        }
    }

    private fun scheduleControlsHide() {
        controlsHideJob?.cancel()
        val accessibility = context?.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if (accessibility?.isTouchExplorationEnabled == true) return
        controlsHideJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(3500)
            if (_binding != null && currentViewingItem?.isVideo == true && nativePlayer?.isPlaying == true &&
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) setViewerControlsVisible(false)
        }
    }

    private fun updateViewerOrientation(config: Configuration) {
        scheduleControlsHide()
        val viewingVideo = currentViewingItem?.isVideo == true
        (activity as? MainActivity)?.setAlbumPreviewExpanded(
            currentViewingItem != null && !isHidden
        )
        binding.layoutViewerStripContainer.visibility = if (viewingVideo && segmentsExpanded) View.VISIBLE else View.GONE
        binding.btnViewerSegments.visibility = View.GONE
        binding.btnViewerSegments.isSelected = segmentsExpanded
        binding.viewerTopBar.visibility = View.VISIBLE
        reportButton?.visibility = if (viewingVideo) View.VISIBLE else View.GONE
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
                video.topMargin = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) 0 else binding.viewerTopBar.height
                video.bottomMargin = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) 0 else stripHeight + binding.layoutViewerControls.height
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
                binding.tvBatchSelectedCount.text = getString(R.string.album_s_selected_s_mb, count, sizeMb)
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
            binding.tvAlbumRecordingPaused.text = getString(R.string.album_opening_recorder_album_recording_will_pause)
            binding.tvAlbumRecordingPaused.visibility = View.VISIBLE
            val enterJob = albumEnterJob ?: viewModel.viewModelScope.async {
                viewModel.enterRemoteAlbumBrowsing()
            }.also { albumEnterJob = it }
            val enterResult = enterJob.await()
            if (enterResult.isFailure) {
                albumEnterJob = null
                binding.tvAlbumRecordingPaused.visibility = View.GONE
                binding.layoutLoadingState.visibility = View.GONE
                Toast.makeText(requireContext(), getString(R.string.album_unable_to_open_recorder_album_s, enterResult.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                return@launch
            }
            updateAlbumRecordingStatus()
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
            val res = viewModel.fetchRemoteFileList()
            binding.layoutLoadingState.visibility = View.GONE
            res.onSuccess { list ->
                Toast.makeText(requireContext(), getString(R.string.album_loaded_s_files, list.size), Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(requireContext(), getString(R.string.album_unable_to_load_videos_s, err.message), Toast.LENGTH_LONG).show()
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
            viewModel.connectionStatus.value != ConnectionStatus.CONNECTED -> getString(R.string.album_recorder_offline_recording_status_unknown)
            albumEnterJob?.isActive == true -> getString(R.string.album_opening_recorder_album_recording_will_pause)
            status.lastUpdatedTimestamp <= 0L || ageMs !in 0L..10_000L -> getString(R.string.album_recording_status_unknown_waiting_for_recorder)
            status.mode == DeviceMode.RECORD && status.recordingStatus == RecordingStatus.RECORDING ->
                getString(R.string.album_recorder_is_recording_album_mode_interrupted)
            status.mode == DeviceMode.PLAYBACK -> getString(R.string.album_recorder_is_in_playback_mode_recording)
            status.recordingStatus == RecordingStatus.STOPPED -> getString(R.string.album_recorder_is_not_recording)
            else -> getString(R.string.album_recording_status_unknown_waiting_for_recorder)
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
                Toast.makeText(context, getString(R.string.album_recording_resume_not_confirmed_s, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initVideoPlayer() {
        binding.btnViewerSegments.setOnClickListener {
            segmentsExpanded = !segmentsExpanded
            updateViewerOrientation(resources.configuration)
        }
        nativePlayer = com.aladdin.dashcam.media.NativeFfmpegPlayer(
            binding.glVideoView,
            onFirstFrameRendered = {
                if (_binding != null && currentViewingItem?.isVideo == true && playbackStopJob?.isActive != true) {
                    if (remotePlayback) viewModel.playbackSession.rendered()
                    binding.viewerLoadingLayout.visibility = View.GONE
                    binding.btnVideoPlayPause.isEnabled = true
                    binding.btnVideoPlayPause.text = getString(R.string.album_pause)
                    scheduleControlsHide()
                    android.util.Log.i("AlbumFragment", "FFmpeg file frame index=${currentViewingItem?.deviceIndex}, elapsedMs=${android.os.SystemClock.elapsedRealtime() - previewStartedAt}")
                }
            },
            onError = { error -> if (_binding != null && currentViewingItem != null && playbackStopJob?.isActive != true) showPlaybackError(previewRequestId, error) },
            onProgress = { position, duration ->
                videoPositionMs = position
                if (_binding != null && currentViewingItem?.isVideo == true && playbackStopJob?.isActive != true) {
                    videoDurationMs = duration
                    val positionText = formatVideoTime(position)
                    val progressText = if (remotePlayback) {
                        getString(R.string.album_s_recorder_playback_download_to_seek, positionText)
                    } else {
                        getString(R.string.album_s_s, positionText, if (duration > 0L) formatVideoTime(duration) else getString(R.string.album_duration_unknown))
                    }
                    if (binding.tvVideoPosition.text.toString() != progressText) {
                        binding.tvVideoPosition.text = progressText
                    }
                    binding.seekVideo.isEnabled = !remotePlayback && duration > 0L
                    if (!seekingVideo && duration > 0L) {
                        binding.seekVideo.progress = (position * 1000L / duration).toInt().coerceIn(0, 1000)
                    }
                }
            },
            onEnded = {
                if (_binding != null && currentViewingItem != null && playbackStopJob?.isActive != true) {
                    controlsHideJob?.cancel()
                    setViewerControlsVisible(true)
                    if (remotePlayback) viewModel.playbackSession.ended()
                    binding.btnVideoPlayPause.text = getString(R.string.album_replay)
                    binding.btnVideoPlayPause.isEnabled = true
                }
            }
        )
        binding.glVideoView.setOnClickListener {
            val visible = binding.viewerTopBar.visibility != View.VISIBLE
            setViewerControlsVisible(visible)
            if (visible) scheduleControlsHide()
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
                Toast.makeText(context, getString(R.string.album_the_recorder_ended_the_paused_stream), Toast.LENGTH_LONG).show()
                openViewer(item)
                return@setOnClickListener
            }
            binding.btnVideoPlayPause.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    if (remotePlayback) viewModel.toggleRemotePlaybackPause(item).getOrThrow()
                    if (previewRequestId != token) return@launch
                    native.setPaused(pause, controlSource = !remotePlayback)
                    if (remotePlayback) viewModel.playbackSession.paused(pause)
                    if (pause) controlsHideJob?.cancel() else scheduleControlsHide()
                    binding.btnVideoPlayPause.text = if (pause) getString(R.string.album_play) else getString(R.string.album_pause)
                } catch (error: Exception) {
                    if (previewRequestId == token) showPlaybackError(token, error.message ?: getString(R.string.album_pause_state_unknown_reopen_the_video))
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
                        Toast.makeText(context, getString(R.string.album_unable_to_seek_this_video), Toast.LENGTH_SHORT).show()
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
        sortOrder = requireContext().getSharedPreferences("album_display", 0).getInt("sort", 0).coerceIn(0, 3)
        binding.btnAlbumSort.setOnClickListener { anchor ->
            val labels = resources.getStringArray(R.array.album_sort_options)
            android.widget.PopupMenu(requireContext(), anchor).apply {
                labels.forEachIndexed { index, label -> menu.add(0, index, index, label).apply {
                    isCheckable = true; isChecked = index == sortOrder
                } }
                menu.setGroupCheckable(0, true, true)
                setOnMenuItemClickListener { item ->
                    sortOrder = item.itemId
                    requireContext().getSharedPreferences("album_display", 0).edit().putInt("sort", sortOrder).apply()
                    binding.btnAlbumSort.contentDescription = labels[sortOrder]
                    applyFilters(); true
                }
                show()
            }
        }
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
            Toast.makeText(requireContext(), getString(R.string.album_downloading_s_files, selected.size), Toast.LENGTH_SHORT).show()
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
                .setTitle(getString(R.string.album_delete_files))
                .setMessage(getString(R.string.album_delete_s_selected_files_from_s, if (isDeviceSource) getString(R.string.album_recorder_sd_card) else getString(R.string.album_phone_storage), selected.size))
                .setPositiveButton(getString(R.string.album_delete)) { _, _ ->
                    executeBatchDelete(selected)
                }
                .setNegativeButton(getString(R.string.album_cancel), null)
                .show()
        }

        // Timeline Seek Listeners
        binding.timelineRuler.onTimeSeekListener = ::updateTimelineSelection
        binding.btnTimelinePlay.setOnClickListener {
            updateTimelineSelection(binding.timelineRuler.currentCursorMillis, true)
        }
        binding.btnTimelineJumpNext.setOnClickListener {
            binding.timelineRuler.findNextItem()?.let { binding.timelineRuler.seekToTime(it.timestampMillis) }
        }
        binding.listTimelineSegments.setOnItemClickListener { _, _, position, _ ->
            timelineItems.getOrNull(position)?.let { binding.timelineRuler.seekToTime(it.timestampMillis) }
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
        reportButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "檢舉"
            contentDescription = "擷取照片／製作檢舉資料"
            isAllCaps = false
            minWidth = 0
            setPadding(12, 0, 12, 0)
            setOnClickListener { currentViewingItem?.let { beginReport(it) } }
        }.also { button ->
            (binding.btnViewerMore.parent as ViewGroup).addView(button,
                (binding.btnViewerMore.parent as ViewGroup).indexOfChild(binding.btnViewerMore),
                ViewGroup.LayoutParams((64 * resources.displayMetrics.density).toInt(),
                    (48 * resources.displayMetrics.density).toInt()))
        }
        binding.btnViewerBack.setOnClickListener {
            closeViewer()
        }
        binding.btnViewerMore.setOnClickListener { anchor ->
            val item = currentViewingItem ?: return@setOnClickListener
            val menu = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
            if (item.isVideo) {
                menu.menu.add(0, 1, 0, getString(R.string.bookmark_add))
                menu.menu.add(0, 2, 1, "擷取照片／檢舉片段")
                menu.menu.add(0, 4, 3, getString(R.string.viewer_segments))
                if (isDeviceSource) menu.menu.add(0, 5, 4, getString(R.string.event_download_neighbors))
            }
            menu.menu.add(0, 3, 2, getString(R.string.video_share))
            menu.setOnMenuItemClickListener { action ->
                if (action.itemId == 2) { beginReport(item); return@setOnMenuItemClickListener true }
                if (action.itemId == 5) {
                    val event = item.timestampMillis + videoPositionMs
                    closeViewer()
                    val count = viewModel.downloadEventWindow(item, event)
                    Toast.makeText(requireContext(), getString(R.string.event_queued, count), Toast.LENGTH_LONG).show()
                    return@setOnMenuItemClickListener true
                }
                if (action.itemId == 4) { binding.btnViewerSegments.performClick(); return@setOnMenuItemClickListener true }
                if (action.itemId == 1) {
                    if (remotePlayback) {
                        Toast.makeText(requireContext(), R.string.video_download_first, Toast.LENGTH_LONG).show()
                        return@setOnMenuItemClickListener true
                    }
                    com.aladdin.dashcam.media.VideoBookmarks(requireContext()).add(item.sourceDeviceId + ":" + item.fileName, videoPositionMs)
                    Toast.makeText(requireContext(), R.string.bookmark_saved, Toast.LENGTH_SHORT).show()
                } else {
                    val file = viewModel.getLocalMediaFile(item)?.takeIf { it.isFile }
                    if (file == null) Toast.makeText(requireContext(), R.string.video_download_first, Toast.LENGTH_LONG).show()
                    else com.aladdin.dashcam.media.MediaFiles.share(requireContext(), file, if (item.isVideo) "video/*" else "image/jpeg")
                }
                true
            }
            val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
            val actions = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(24, 12, 24, 24)
            }
            for (index in 0 until menu.menu.size()) {
                val entry = menu.menu.getItem(index)
                actions.addView(android.widget.Button(requireContext(), null, android.R.attr.borderlessButtonStyle).apply {
                    text = entry.title; isAllCaps = false
                    minHeight = (48 * resources.displayMetrics.density).toInt()
                    setOnClickListener { sheet.dismiss(); menu.menu.performIdentifierAction(entry.itemId, 0) }
                })
            }
            sheet.setContentView(actions)
            sheet.show()
        }

        binding.btnViewerRetry.setOnClickListener {
            val item = currentViewingItem ?: return@setOnClickListener
            val local = viewModel.getLocalMediaFile(item)?.isFile == true || viewModel.getPreviewCacheFile(item).let {
                it.isFile && (item.sizeBytes <= 0L || it.length() == item.sizeBytes)
            }
            if (isDeviceSource && !local && viewModel.targetIdentity.value.state != com.aladdin.dashcam.network.TargetState.READY) {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            } else openViewer(item)
        }

        binding.btnViewerDownload.setOnClickListener {
            val item = currentViewingItem ?: return@setOnClickListener
            if (item.isDownloaded) return@setOnClickListener
            closeViewer()
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
            Toast.makeText(requireContext(), getString(R.string.album_deleted_s_of_s_files, successCount, items.size), Toast.LENGTH_SHORT).show()
            exitBatchSelectionMode()
            if (isDeviceSource) {
                triggerRemoteListRefresh()
            } else {
                applyFilters()
            }
        }
    }

    private fun updateViewModeLayout() {
        binding.btnAlbumSort.visibility = if (isTimelineMode) View.GONE else View.VISIBLE
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
        if (timestamp <= 0L) return getString(R.string.album_time_unknown)
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
            0 -> getString(R.string.album_today_s, ymd)
            1 -> getString(R.string.album_yesterday_s, ymd)
            else -> ymd
        }
    }

    private fun updateTimelineSelection(targetMillis: Long, isFinished: Boolean) {
        val hitItem = binding.timelineRuler.findItemAtCursor()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(targetMillis))
        binding.tvTimelineCursorInfo.text = if (hitItem != null) {
            getString(R.string.timeline_selected_clip, timeStr, hitItem.fileName)
        } else getString(R.string.album_no_recordings_at_this_time_s, timeStr)
        binding.btnTimelinePlay.isEnabled = hitItem != null
        binding.btnTimelineJumpNext.visibility = if (hitItem == null && binding.timelineRuler.findNextItem() != null) View.VISIBLE else View.GONE
        if (timelineSelectedItemId != hitItem?.id) {
            timelineSelectedItemId = hitItem?.id
            binding.listTimelineSegments.clearChoices()
            val index = timelineItems.indexOfFirst { it.id == timelineSelectedItemId }
            if (index >= 0) binding.listTimelineSegments.setItemChecked(index, true)
            (binding.listTimelineSegments.adapter as? android.widget.ArrayAdapter<*>)?.notifyDataSetChanged()
        }
        if (isFinished && hitItem != null) {
            val offset = if (hitItem.durationSeconds > 0) {
                (targetMillis - hitItem.timestampMillis).coerceIn(0L, hitItem.durationSeconds * 1000L)
            } else 0L
            openViewer(hitItem, startOffsetMs = offset)
        }
    }

    private fun applyTimelineData() {
        val rawList = if (isDeviceSource) viewModel.deviceMediaList.value else viewModel.localMediaList.value
        val videoList = rawList.filter { it.isVideo && it.timestampMillis > 0L }
        if (videoList.isEmpty()) {
            timelineItems = emptyList()
            timelineSelectedItemId = null
            binding.listTimelineSegments.adapter = null
            binding.timelineRuler.setDayMedia(System.currentTimeMillis(), emptyList())
            binding.containerTimelineDates.removeAllViews()
            binding.tvTimelineCursorInfo.text = getString(R.string.album_no_video_segments_available)
            binding.tvTimelineSegmentCount.text = ""
            binding.btnTimelinePlay.isEnabled = false
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
                    applyTimelineData() // refresh chip selection
                }
            }
            binding.containerTimelineDates.addView(chip)
        }

        val itemsForDate = dateMap[currentSelectedDate].orEmpty().sortedBy { it.timestampMillis }
        timelineItems = itemsForDate
        timelineSelectedItemId = null
        binding.tvTimelineSegmentCount.text = getString(R.string.timeline_segment_count, itemsForDate.size)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        binding.listTimelineSegments.adapter = object : android.widget.ArrayAdapter<MediaItem>(requireContext(), android.R.layout.simple_list_item_2, android.R.id.text1, itemsForDate) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = super.getView(position, convertView, parent)
                val item = getItem(position) ?: return row
                row.findViewById<android.widget.TextView>(android.R.id.text1).apply {
                    text = "${timeFormat.format(Date(item.timestampMillis))}  ·  ${getString(if (item.isEmergency) R.string.album_protected_events else R.string.album_loop_recordings)}"
                    val selected = timelineSelectedItemId == item.id
                    setTextColor(ContextCompat.getColor(context, if (selected) R.color.accent_teal else R.color.text_primary))
                    textSize = 14f
                }
                row.findViewById<android.widget.TextView>(android.R.id.text2).apply {
                    text = item.fileName
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                }
                return row
            }
        }
        val targetTimestamp = itemsForDate.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        binding.timelineRuler.setDayMedia(targetTimestamp, itemsForDate)
        updateTimelineSelection(binding.timelineRuler.currentCursorMillis, false)
    }

    private fun applyFilters() {
        val rawList = if (isDeviceSource) viewModel.deviceMediaList.value else viewModel.localMediaList.value
        if (isTimelineMode) {
            applyTimelineData()
            updateAlbumEmptyState(rawList, rawList.none { it.isVideo && it.timestampMillis > 0L })
            return
        }

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
            val comparison = when (sortOrder) {
                2 -> b.sizeBytes.compareTo(a.sizeBytes)
                3 -> a.sizeBytes.compareTo(b.sizeBytes)
                else -> when {
                    a.timestampMillis <= 0 && b.timestampMillis > 0 -> 1
                    b.timestampMillis <= 0 && a.timestampMillis > 0 -> -1
                    sortOrder == 1 -> a.timestampMillis.compareTo(b.timestampMillis)
                    else -> b.timestampMillis.compareTo(a.timestampMillis)
                }
            }
            if (comparison != 0) comparison else b.deviceIndex.compareTo(a.deviceIndex)
        }
        currentFilteredMediaList = sortedList

        // Group by recording date into AlbumListItem items (Today / Yesterday / YYYY-MM-DD / Unknown)
        val itemsWithHeaders = mutableListOf<AlbumListItem>()
        var lastHeader = ""

        for (item in sortedList) {
            val headerText = formatGroupHeader(item.timestampMillis)
            if (sortOrder < 2 && headerText != lastHeader) {
                itemsWithHeaders.add(AlbumListItem.Header(headerText))
                lastHeader = headerText
            }
            itemsWithHeaders.add(AlbumListItem.Media(item))
        }

        adapter.submitList(itemsWithHeaders)
        updateAlbumEmptyState(rawList, itemsWithHeaders.isEmpty())
    }

    private fun updateAlbumEmptyState(rawList: List<MediaItem>, empty: Boolean) {
        // Accurate state separation: unqueried vs empty vs filter-no-result
        if (empty) {
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.rvAlbum.visibility = View.GONE

            if (isDeviceSource) {
                when (viewModel.albumQueryState.value) {
                    is AlbumQueryState.Success -> {
                        if (rawList.isEmpty()) {
                            binding.tvAlbumEmpty.text = getString(R.string.album_no_files_on_the_recorder_memory)
                            binding.btnRefreshAlbum.text = getString(R.string.album_refresh)
                            binding.btnRefreshAlbum.visibility = View.VISIBLE
                            binding.btnRefreshAlbum.setOnClickListener { triggerRemoteListRefresh() }
                        } else {
                            binding.tvAlbumEmpty.text = getString(R.string.album_no_media_in_this_category)
                            binding.btnRefreshAlbum.text = getString(if (isTimelineMode) R.string.album_view_grid else R.string.album_show_all_media)
                            binding.btnRefreshAlbum.visibility = View.VISIBLE
                            binding.btnRefreshAlbum.setOnClickListener {
                                if (isTimelineMode) binding.toggleViewMode.check(R.id.btn_mode_grid) else selectFilter(FilterCategory.ALL)
                            }
                        }
                    }
                    is AlbumQueryState.Error -> {
                        binding.tvAlbumEmpty.text = getString(R.string.album_unable_to_read_recorder_files)
                        binding.btnRefreshAlbum.text = getString(R.string.album_retry_063)
                        binding.btnRefreshAlbum.visibility = View.VISIBLE
                        binding.btnRefreshAlbum.setOnClickListener { triggerRemoteListRefresh() }
                    }
                    else -> {
                        binding.tvAlbumEmpty.text = getString(R.string.album_recorder_connected_load_the_video_list)
                        binding.btnRefreshAlbum.text = getString(R.string.album_load_recorder_videos)
                        binding.btnRefreshAlbum.visibility = View.VISIBLE
                        binding.btnRefreshAlbum.setOnClickListener { triggerRemoteListRefresh() }
                    }
                }
            } else {
                if (rawList.isEmpty()) {
                    binding.tvAlbumEmpty.text = getString(R.string.album_no_videos_downloaded_yet)
                    binding.btnRefreshAlbum.text = getString(R.string.album_open_recorder_album)
                    binding.btnRefreshAlbum.visibility = View.VISIBLE
                    binding.btnRefreshAlbum.setOnClickListener {
                        binding.toggleAlbumSource.check(R.id.btn_source_device)
                    }
                } else {
                    binding.tvAlbumEmpty.text = getString(R.string.album_no_media_in_this_category)
                    binding.btnRefreshAlbum.text = getString(if (isTimelineMode) R.string.album_view_grid else R.string.album_show_all_media)
                    binding.btnRefreshAlbum.visibility = View.VISIBLE
                    binding.btnRefreshAlbum.setOnClickListener {
                        if (isTimelineMode) binding.toggleViewMode.check(R.id.btn_mode_grid) else selectFilter(FilterCategory.ALL)
                    }
                }
            }
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.rvAlbum.visibility = if (isTimelineMode) View.GONE else View.VISIBLE
        }
    }

    /**
     * Opens media viewer with fast RTSP playback on remote dashcam and offline local playback.
     */
    private fun openViewer(item: MediaItem, startOffsetMs: Long = 0L) {
        playbackCommandJob?.cancel()
        val wasRemotePlayback = remotePlayback
        if (wasRemotePlayback) stopRemotePlaybackBeforeDecoderRelease()
        remotePlayback = false
        videoDurationMs = 0L
        videoPositionMs = startOffsetMs
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
            getString(R.string.album_time_unknown)
        }
        binding.tvViewerTime.text = timeStr
        binding.tvViewerTime.contentDescription = if (item.isVideo) getString(R.string.album_recorded_s, timeStr) else getString(R.string.album_captured_s, timeStr)

        updateViewerDownloadButton(item)

        // Reset player & photo view
        if (!wasRemotePlayback && playbackStopJob?.isActive != true) releasePlayer()
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
            binding.tvViewerLoadingText.text = getString(R.string.album_opening_recording_recording_pauses_during_playback)
            binding.glVideoView.visibility = View.VISIBLE
            binding.layoutViewerControls.visibility = View.VISIBLE
            binding.seekVideo.isEnabled = false
            binding.btnVideoPlayPause.isEnabled = false
            if (startOffsetMs > 0L) Toast.makeText(context, getString(R.string.album_recorder_playback_starts_at_the_beginning), Toast.LENGTH_LONG).show()
            previewJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    playbackStopJob?.join()
                    val decoder = nativePlayer ?: error(getString(R.string.player_unavailable))
                    viewModel.playbackSession.open(item, decoder)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (_binding != null && previewRequestId == currentToken) {
                        nativePlayer?.stop()
                        showPlaybackError(currentToken, error.message ?: getString(R.string.album_video_transfer_interrupted))
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
        binding.tvViewerLoadingText.text = getString(R.string.album_reading_file)

        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            val res = viewModel.fetchPreviewCache(item) { bytesRead ->
                if (!isAdded || previewRequestId != currentToken || currentViewingItem?.fileName != item.fileName) return@fetchPreviewCache
                val progress = if (item.sizeBytes > 0) {
                    ((bytesRead * 100) / item.sizeBytes).toInt().coerceIn(0, 100)
                } else 0
                binding.tvViewerLoadingText.text = if (progress > 0) getString(R.string.album_loading_s, progress) else getString(R.string.album_loading_075)
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
                    binding.tvViewerErrorMsg.text = getString(R.string.album_unable_to_load_s, err.message)
                }
            }
        }
    }

    private fun showPlaybackError(token: Long, message: String) {
        if (_binding == null || previewRequestId != token) return
        if (remotePlayback) {
            viewModel.playbackSession.interrupted()
            stopRemotePlaybackBeforeDecoderRelease()
        }
        binding.viewerLoadingLayout.visibility = View.GONE
        if (isDeviceSource && currentViewingItem?.isVideo == true) {
            binding.ivPhotoViewer.setImageDrawable(null)
            binding.ivPhotoViewer.visibility = View.GONE
        }
        nativePlayer?.stop()
        binding.glVideoView.visibility = View.GONE
        binding.btnVideoPlayPause.text = getString(R.string.album_retry_077)
        binding.btnVideoPlayPause.isEnabled = true
        binding.viewerErrorLayout.visibility = View.VISIBLE
        val offline = remotePlayback && viewModel.targetIdentity.value.state != com.aladdin.dashcam.network.TargetState.READY
        binding.btnViewerRetry.setText(if (offline) R.string.recovery_wifi_settings else R.string.album_retry_077)
        binding.tvViewerErrorMsg.text = if (offline) getString(R.string.recovery_playback_offline)
            else getString(R.string.recovery_playback_failed, message)
        binding.tvViewerErrorMsg.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
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
        binding.tvViewerLoadingText.text = getString(R.string.album_opening_local_video)
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
                binding.tvViewerErrorMsg.text = getString(R.string.album_unable_to_decode_photo)
            }
        } catch (e: Exception) {
            binding.viewerErrorLayout.visibility = View.VISIBLE
            binding.tvViewerErrorMsg.text = getString(R.string.album_unable_to_load_photo_s, e.message)
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

    private fun beginReport(item: MediaItem) {
        if (!item.isVideo || reportJob?.isActive == true) return
        val position = videoPositionMs
        fun openTools(file: File) {
            startActivity(android.content.Intent(requireContext(), MediaToolsActivity::class.java)
                .putExtra("path", file.absolutePath).putExtra("key", item.sourceDeviceId + ":" + item.fileName)
                .putExtra("time", item.timestampMillis).putExtra("position", position))
        }
        viewModel.getLocalMediaFile(item)?.let { openTools(it); return }
        if (viewModel.connectionStatus.value != ConnectionStatus.CONNECTED) {
            Toast.makeText(requireContext(), "請先連線記錄器，才能下載影片製作檢舉資料", Toast.LENGTH_LONG).show()
            return
        }
        closeViewer()
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("準備檢舉影片")
            .setMessage("正在下載原始影片，完成後會自動開啟檢舉工具。")
            .setNegativeButton("稍後處理") { _, _ -> reportJob?.cancel() }
            .create()
        dialog.setOnCancelListener { reportJob?.cancel() }
        dialog.show()
        reportJob = viewLifecycleOwner.lifecycleScope.launch {
            var progress: Job? = null
            try {
                playbackStopJob?.join()
                val download = viewModel.startPermanentDownload(item)
                progress = launch {
                    viewModel.downloadTasks.collect { tasks ->
                        val task = tasks.find { it.id == item.storageKey } ?: return@collect
                        val percent = if (task.totalBytes > 0) (task.bytesRead * 100 / task.totalBytes).coerceIn(0, 100) else 0
                        dialog.setMessage(if (task.status == DownloadStatus.PENDING) "等待下載，請保持記錄器連線。\n完成後會自動開啟檢舉工具。"
                            else "下載中 $percent%\n完成後會自動開啟檢舉工具。")
                    }
                }
                download.join()
                val file = viewModel.getLocalMediaFile(item)
                if (file != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !isHidden) openTools(file)
                else if (file == null) Toast.makeText(context, "影片下載未完成，請在下載頁查看狀態後重試檢舉。", Toast.LENGTH_LONG).show()
            } finally {
                progress?.cancel()
                dialog.dismiss()
            }
        }
    }

    /**
     * Starts user-confirmed permanent download to local storage.
     * Delegates to DashCamViewModel's unified startPermanentDownload (F1 / Single Ownership).
     */
    private fun startPermanentDownload(item: MediaItem) {
        if (downloadJob?.isActive == true) return

        showViewerDownloadProgress(getString(R.string.album_downloading))

        downloadJob = viewModel.startPermanentDownload(item)
    }

    private fun updateViewerDownloadButton(item: MediaItem) {
        binding.btnViewerDownload.isEnabled = !item.isDownloaded
        binding.btnViewerDownload.alpha = if (item.isDownloaded) 0.45f else 1f
        binding.btnViewerDownload.text = ""
        binding.btnViewerDownload.setIconResource(R.drawable.ic_tab_downloads)
        val description = getString(if (item.isDownloaded) R.string.item_media_card_downloaded else R.string.album_download)
        binding.btnViewerDownload.contentDescription = description
        binding.btnViewerDownload.tooltipText = description
    }

    private fun showViewerDownloadProgress(description: String, progress: Int? = null) {
        binding.btnViewerDownload.isEnabled = false
        binding.btnViewerDownload.icon = null
        binding.btnViewerDownload.text = progress?.let { "$it%" } ?: "…"
        binding.btnViewerDownload.contentDescription = description
        binding.btnViewerDownload.tooltipText = description
    }

    private fun closeViewer() {
        controlsHideJob?.cancel()
        val wasRemotePlayback = remotePlayback
        playbackCommandJob?.cancel()
        ++previewRequestId // Invalidate any in-flight preview requests (R2)
        previewJob?.cancel()
        previewJob = null
        downloadJob = null
        if (wasRemotePlayback) stopRemotePlaybackBeforeDecoderRelease()
        else if (playbackStopJob?.isActive != true) releasePlayer()
        remotePlayback = false
        releasePreviewProtection()
        currentViewingItem = null
        (activity as? MainActivity)?.setAlbumPreviewExpanded(false)
        binding.layoutMediaViewer.visibility = View.GONE
        backPressedCallback.isEnabled = false

        if (!wasRemotePlayback && playbackStopJob?.isActive != true) {
            viewModel.releaseStreamOwner(DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK)
        }
    }

    private fun stopRemotePlaybackBeforeDecoderRelease() {
        val previousStop = playbackStopJob
        playbackStopJob = viewModel.viewModelScope.launch {
            previousStop?.join()
            val result = viewModel.playbackSession.close()
            if (result.isFailure && isAdded) Toast.makeText(context,
                getString(R.string.album_playback_closed_but_recording_resume_not, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
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
                            showPlaybackError(disconnectedToken, getString(R.string.album_recorder_disconnected_reconnect_and_retry))
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
                                updateViewerDownloadButton(current)
                            }
                            DownloadStatus.DOWNLOADING -> {
                                val progress = if (task.totalBytes > 0) {
                                    ((task.bytesRead * 100) / task.totalBytes).toInt().coerceIn(0, 100)
                                } else 0
                                showViewerDownloadProgress(getString(R.string.album_downloading_s, progress), progress.takeIf { it > 0 })
                            }
                            DownloadStatus.PENDING -> {
                                showViewerDownloadProgress(getString(R.string.album_waiting_to_download))
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
        reportJob?.cancel()
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
        val player = nativePlayer
        val pendingStop = playbackStopJob
        if (pendingStop?.isActive == true) {
            viewModel.viewModelScope.launch { pendingStop.join(); player?.release() }
        } else player?.release()
        nativePlayer = null
        releasePreviewProtection()
        _binding = null
        reportButton = null
    }
}
