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

    private var isDeviceSource = true
    private var currentFilter = FilterCategory.ALL

    // Media viewer & player state
    private var exoPlayer: ExoPlayer? = null
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
            closeViewer()
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
        adapter = MediaAdapter { item ->
            // Enter viewer directly. No automatic permanent download.
            openViewer(item)
        }

        val gridLayoutManager = GridLayoutManager(requireContext(), 2)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == MediaAdapter.TYPE_HEADER) 2 else 1
            }
        }
        binding.rvAlbum.layoutManager = gridLayoutManager
        binding.rvAlbum.adapter = adapter
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
                isDeviceSource = (checkedId == R.id.btn_source_device)
                applyFilters()
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

    private fun selectFilter(filter: FilterCategory) {
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

    private fun applyFilters() {
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

        // Group by recording date into AlbumListItem items
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val itemsWithHeaders = mutableListOf<AlbumListItem>()
        var lastHeader = ""

        for (item in sortedList) {
            val headerText = if (item.timestampMillis > 0L) {
                dateFormat.format(Date(item.timestampMillis))
            } else {
                "時間未知"
            }
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
     * Opens media viewer with proper loading and retry handling.
     * Does NOT download permanently automatically.
     */
    private fun openViewer(item: MediaItem) {
        currentViewingItem = item
        binding.layoutMediaViewer.visibility = View.VISIBLE
        backPressedCallback.isEnabled = true

        // Monotonically increasing request token to invalidate any stale in-flight preview (R2)
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

        // Update independent download button state
        updateViewerDownloadButton(item)

        // Reset player & photo view
        releasePlayer()
        binding.playerView.visibility = View.GONE
        binding.ivPhotoViewer.visibility = View.GONE
        binding.viewerLoadingLayout.visibility = View.GONE
        binding.viewerErrorLayout.visibility = View.GONE

        // Check if file is already on local device
        val localFile = viewModel.getLocalMediaFile(item)
        if (localFile != null && localFile.exists()) {
            displayMediaContent(item, localFile)
            return
        }

        // Check if temporary viewing cache already exists
        val cacheFile = viewModel.getPreviewCacheFile(item)
        if (cacheFile.exists() && (item.sizeBytes <= 0 || cacheFile.length() == item.sizeBytes)) {
            displayMediaContent(item, cacheFile)
            return
        }

        // Remote fetch into viewing cache
        binding.viewerLoadingLayout.visibility = View.VISIBLE
        binding.tvViewerLoadingText.text = "正在準備觀看，需先讀取此檔案..."

        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            val res = viewModel.fetchPreviewCache(item) { bytesRead ->
                if (!isAdded || previewRequestId != currentToken || currentViewingItem?.fileName != item.fileName) return@fetchPreviewCache
                val progress = if (item.sizeBytes > 0) {
                    ((bytesRead * 100) / item.sizeBytes).toInt().coerceIn(0, 100)
                } else 0
                binding.tvViewerLoadingText.text = if (progress > 0) "讀取中: $progress%" else "正在準備觀看..."
            }

            if (!isAdded || previewRequestId != currentToken || currentViewingItem?.fileName != item.fileName) return@launch

            binding.viewerLoadingLayout.visibility = View.GONE
            res.onSuccess { file ->
                if (isAdded && previewRequestId == currentToken && currentViewingItem?.fileName == item.fileName) {
                    displayMediaContent(item, file)
                }
            }.onFailure { err ->
                if (isAdded && previewRequestId == currentToken && currentViewingItem?.fileName == item.fileName) {
                    binding.viewerErrorLayout.visibility = View.VISIBLE
                    binding.tvViewerErrorMsg.text = "載入失敗：${err.message}"
                }
            }
        }
    }

    private fun displayMediaContent(item: MediaItem, file: File) {
        if (item.isVideo) {
            binding.playerView.visibility = View.VISIBLE
            binding.ivPhotoViewer.visibility = View.GONE
            setupExoPlayer(file)
        } else {
            binding.playerView.visibility = View.GONE
            binding.ivPhotoViewer.visibility = View.VISIBLE
            displayPhoto(file)
        }
    }

    private fun setupExoPlayer(file: File) {
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
    }

    private fun releasePlayer() {
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
        ++previewRequestId
        previewJob?.cancel()
        previewJob = null
        downloadJob = null
        currentViewingItem = null
        releasePlayer()
        _binding = null
    }
}
