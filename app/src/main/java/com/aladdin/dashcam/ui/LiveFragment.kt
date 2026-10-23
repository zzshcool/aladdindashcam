package com.aladdin.dashcam.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.aladdin.dashcam.R
import com.aladdin.dashcam.databinding.FragmentLiveBinding
import com.aladdin.dashcam.model.ConnectionStatus
import com.aladdin.dashcam.model.RecordingStatus
import com.aladdin.dashcam.gps.GpsStatus
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class LiveFragment : Fragment() {
    private var _binding: FragmentLiveBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashCamViewModel by activityViewModels()
    private var player: ExoPlayer? = null
    private var nativePlayer: com.aladdin.dashcam.media.NativeFfmpegPlayer? = null

    private var lastStreamNetwork: android.net.Network? = null
    private var lastStreamHost: String? = null
    private var sdpProxy: com.aladdin.dashcam.network.RtspSdpProxy? = null
    private var streamJob: Job? = null

    private var googleMap: GoogleMap? = null
    private var phoneMarker: Marker? = null
    private var trajectoryPolyline: Polyline? = null
    private var isMapFollowEnabled = true
    private var selectedModeId = R.id.btn_mode_normal
    private var mapLoaded = false
    private var mapRequested = false
    private var hasSavedMapCamera = false
    private var savedMapType = GoogleMap.MAP_TYPE_NORMAL
    private var lastMapStatus: GpsStatus? = null
    private var mapLoadJob: Job? = null

    fun selectMode(modeId: Int) {
        selectedModeId = modeId
        if (_binding != null) {
            binding.toggleLiveMode?.check(modeId)
            applyLiveMode()
        }
    }

    private fun applyLiveMode() {
        if (_binding == null) return
        val mapMode = selectedModeId == R.id.btn_mode_map
        val split = selectedModeId == R.id.btn_mode_split
        binding.previewContainer.visibility = if (mapMode) View.GONE else View.VISIBLE
        binding.mapContainer.visibility = if (mapMode || split) View.VISIBLE else View.GONE
        binding.layoutNormalControls?.visibility = if (selectedModeId == R.id.btn_mode_normal) View.VISIBLE else View.GONE
        binding.layoutRideMode?.visibility = if (selectedModeId == R.id.btn_mode_ride) View.VISIBLE else View.GONE
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            val constraints = ConstraintSet().apply { clone(binding.root) }
            constraints.connect(R.id.preview_container, ConstraintSet.END,
                if (split) R.id.map_split_guideline else R.id.side_controls_container, ConstraintSet.START)
            constraints.connect(R.id.map_container, ConstraintSet.START,
                if (split) R.id.map_split_guideline else ConstraintSet.PARENT_ID, ConstraintSet.START)
            constraints.applyTo(binding.root)
        }
        if (mapMode || split) {
            if (!mapRequested) initMap()
            updateMapLocation(viewModel.gpsStatus.value)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            binding.mapView.onCreate(savedInstanceState)
        } catch (e: Exception) {
            android.util.Log.e("LiveFragment", "Error initializing MapView: ${e.message}")
        }
        initPlayer()
        setupListeners()
        isMapFollowEnabled = savedInstanceState?.getBoolean("map_follow", true) ?: true
        hasSavedMapCamera = savedInstanceState?.getBoolean("map_camera", false) ?: false
        savedMapType = savedInstanceState?.getInt("map_type", GoogleMap.MAP_TYPE_NORMAL) ?: GoogleMap.MAP_TYPE_NORMAL
        selectedModeId = savedInstanceState?.getInt("live_mode", selectedModeId) ?: selectedModeId
        binding.toggleLiveMode?.check(selectedModeId)
        applyLiveMode()
        observeViewModel()
    }

    private fun initMap() {
        try {
            mapRequested = true
            val requestedView = binding.mapView
            requestedView.getMapAsync { map ->
                if (_binding?.mapView !== requestedView) return@getMapAsync
                this.googleMap = map
                map.mapType = savedMapType
                binding.btnMapType.text = if (savedMapType == GoogleMap.MAP_TYPE_HYBRID) "一般地圖" else "衛星地圖"
                try {
                    map.uiSettings.isZoomControlsEnabled = false
                    map.uiSettings.isCompassEnabled = true
                    map.uiSettings.isMyLocationButtonEnabled = false
                    val density = resources.displayMetrics.density
                    val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    map.setPadding(0, ((if (landscape) 8 else 64) * density).toInt(),
                        ((if (landscape) 8 else 64) * density).toInt(), (48 * density).toInt())
                } catch (_: Exception) {}

                val polylineOptions = PolylineOptions()
                    .color(ContextCompat.getColor(requireContext(), R.color.accent_teal))
                    .width(10f)
                    .geodesic(true)
                trajectoryPolyline = map.addPolyline(polylineOptions)
                if (!hasSavedMapCamera) map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(23.7, 121.0), 7f))
                map.setOnMapLoadedCallback {
                    if (_binding != null) {
                        mapLoaded = true
                        binding.tvMapNotice.text = "地圖已載入 · 定位來自手機"
                        android.util.Log.i("MapValidation", "Map tiles rendered")
                    }
                }
                mapLoadJob?.cancel()
                mapLoadJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(12000)
                    if (!mapLoaded && _binding != null) {
                        binding.tvMapNotice.text = "地圖尚未載入，請檢查網路與地圖服務設定"
                    }
                }

                map.setOnCameraMoveStartedListener { reason ->
                    if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    isMapFollowEnabled = false
                    binding.fabMapCenter.contentDescription = "回到目前位置並跟隨"
                    }
                }

                updateMapLocation(viewModel.gpsStatus.value)
            }
        } catch (e: Exception) {
            mapRequested = false
            android.util.Log.e("LiveFragment", "getMapAsync exception: ${e.message}")
        }
    }

    private fun initPlayer() {
        val context = requireContext()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(200, 500, 100, 150)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val newPlayer = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
        binding.playerView.player = newPlayer
        this.player = newPlayer

        if (com.aladdin.dashcam.media.NativeFfmpegPlayer.isSupported) {
            try {
                binding.glPlayerView.visibility = View.VISIBLE
                binding.playerView.visibility = View.GONE
                nativePlayer = com.aladdin.dashcam.media.NativeFfmpegPlayer(
                    glSurfaceView = binding.glPlayerView,
                    onFirstFrameRendered = {
                        binding.streamOverlay.visibility = View.GONE
                        binding.pbStreamLoading.visibility = View.GONE
                        binding.btnRetryStream.visibility = View.GONE
                        android.util.Log.i("LiveFragment", "Native ffmpeg first frame rendered successfully")
                    },
                    onError = { err ->
                        android.util.Log.w("LiveFragment", "Native ffmpeg player error: $err")
                    }
                )
                android.util.Log.i("LiveFragment", "NativeFfmpegPlayer initialized with GLSurfaceView")
            } catch (e: Throwable) {
                android.util.Log.e("LiveFragment", "Failed to initialize NativeFfmpegPlayer", e)
                nativePlayer = null
                binding.glPlayerView.visibility = View.GONE
                binding.playerView.visibility = View.VISIBLE
            }
        } else {
            binding.glPlayerView.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
        }

        newPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                binding.streamOverlay.visibility = View.VISIBLE
                binding.pbStreamLoading.visibility = View.GONE
                binding.btnRetryStream.visibility = View.VISIBLE
                val errName = error.errorCodeName
                binding.tvStreamMessage.text = getString(R.string.stream_connection_interrupted)
                android.util.Log.e("LiveFragment", "PlaybackException: errorCodeName=$errName, message=${error.message}", error)
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                android.util.Log.i("LiveFragment", "onVideoSizeChanged: ${videoSize.width}x${videoSize.height}")
            }

            override fun onRenderedFirstFrame() {
                // LIVE-04: Genuinely confirms first video frame is visible on surface
                binding.streamOverlay.visibility = View.GONE
                binding.pbStreamLoading.visibility = View.GONE
                android.util.Log.i("LiveFragment", "onRenderedFirstFrame: GENUINE LIVE VIDEO FRAME VISIBLE ON SURFACE")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                android.util.Log.i("LiveFragment", "onPlaybackStateChanged: $stateName")
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previousFrames = -1
                var unchangedSamples = 0
                var stalled = false
                var sample = 0
                while (true) {
                    delay(250)
                    val bufferedMs = newPlayer.totalBufferedDuration
                    val currentSpeed = newPlayer.playbackParameters.speed
                    val targetSpeed = when {
                        !newPlayer.isPlaying || bufferedMs <= 600 -> 1.0f
                        bufferedMs <= 1000 -> 1.03f
                        bufferedMs <= 1500 -> 1.06f
                        else -> 1.10f
                    }
                    if (Math.abs(currentSpeed - targetSpeed) >= 0.02f) {
                        newPlayer.playbackParameters = PlaybackParameters(targetSpeed)
                    }
                    if (++sample % 8 != 0) continue
                    val counters = newPlayer.videoDecoderCounters ?: continue
                    counters.ensureUpdated()
                    val frames = counters.renderedOutputBufferCount
                    android.util.Log.d("LiveFragment", "Video progress: rendered=$frames, dropped=${counters.droppedBufferCount}, bufferedMs=$bufferedMs, speed=${newPlayer.playbackParameters.speed}")
                    if (newPlayer.isPlaying && frames > 0 && frames == previousFrames) {
                        unchangedSamples++
                        if (unchangedSamples >= 3) {
                            stalled = true
                            binding.streamOverlay.visibility = View.VISIBLE
                            binding.pbStreamLoading.visibility = View.GONE
                            binding.btnRetryStream.visibility = View.VISIBLE
                            binding.tvStreamMessage.text = getString(R.string.stream_stalled)
                        }
                    } else {
                        unchangedSamples = 0
                        if (stalled && frames > previousFrames) {
                            binding.streamOverlay.visibility = View.GONE
                            stalled = false
                        }
                    }
                    previousFrames = frames
                }
            }
        }

        // Default to waiting overlay until Wi-Fi network is verified and bound
        binding.streamOverlay.visibility = View.VISIBLE
        binding.pbStreamLoading.visibility = View.VISIBLE
        binding.btnRetryStream.visibility = View.GONE
        binding.tvStreamMessage.text = getString(R.string.stream_loading)
    }

    private fun setupMediaStream(network: android.net.Network?) {
        val p = player ?: return
        if (network == null || viewModel.targetIdentity.value.state != com.aladdin.dashcam.network.TargetState.READY) {
            streamJob?.cancel()
            streamJob = null
            p.stop()
            sdpProxy?.stop()
            sdpProxy = null
            lastStreamHost?.let { com.aladdin.dashcam.network.RtspSdpProxy.clearCache(it) }
            lastStreamNetwork = null
            lastStreamHost = null
            binding.streamOverlay.visibility = View.VISIBLE
            binding.pbStreamLoading.visibility = View.GONE
            binding.btnRetryStream.visibility = View.VISIBLE
            binding.tvStreamMessage.text = "記錄器未連線\n手機地圖仍可使用"
            return
        }

        // RTSP Stream endpoint per specification 25.2 (dynamic host support)
        val host = viewModel.deviceHost.value
        // LIVE-06 / L4: Deduplicate redundant prepare calls on identical network and host during buffering or ready
        if (network == lastStreamNetwork && host == lastStreamHost &&
            (streamJob?.isActive == true || p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)) {
            return
        }
        streamJob?.cancel()
        lastStreamNetwork = network
        lastStreamHost = host

        binding.streamOverlay.visibility = View.VISIBLE
        binding.pbStreamLoading.visibility = View.VISIBLE
        binding.btnRetryStream.visibility = View.GONE
        binding.tvStreamMessage.text = getString(R.string.stream_loading)

        streamJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // V2: 依 CamWrapper / Vidure 邏輯核對 rtspFlag
                val currentStatus = viewModel.refreshStatus()
                if (currentStatus == null || currentStatus.lastUpdatedTimestamp == 0L) {
                    binding.pbStreamLoading.visibility = View.GONE
                    binding.btnRetryStream.visibility = View.VISIBLE
                    binding.tvStreamMessage.text = "無法讀取記錄器狀態，請重試連線"
                    return@launch
                }
                if (!currentStatus.rtspFlag) {
                    android.util.Log.w("LiveFragment", "Device reported rtspFlag=false (HTTP streaming mode, pending verification)")
                    binding.streamOverlay.visibility = View.VISIBLE
                    binding.pbStreamLoading.visibility = View.GONE
                    binding.btnRetryStream.visibility = View.VISIBLE
                    binding.tvStreamMessage.text = "設備回報非 RTSP 串流模式"
                    return@launch
                }

                // V4: 原廠啟動順序：先於 8081 控制通道發送 restartStreaming，確認設備就緒後再初始化 8080 播放
                android.util.Log.i("LiveFragment", "Sending restartStreaming trigger to 8081 before media preparation...")
                val restartResult = viewModel.restartStreaming()
                if (restartResult.isFailure) {
                    val err = restartResult.exceptionOrNull()
                    android.util.Log.w("LiveFragment", "restartStreaming pre-trigger failed: ${err?.message}")
                    // 若 8081 推流命令未成功，不盲目向 8080 發起 RTSP，避免設備 lwIP 崩潰與連線競爭
                    binding.streamOverlay.visibility = View.VISIBLE
                    binding.pbStreamLoading.visibility = View.GONE
                    binding.btnRetryStream.visibility = View.VISIBLE
                    binding.tvStreamMessage.text = getString(R.string.device_status_stream_interrupted)
                    return@launch
                }

                // 綁定整個進程至 Wi-Fi 網絡，讓 Native ffmpeg socket 直接走 Wi-Fi
                try {
                    val cm = requireContext().applicationContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    cm.bindProcessToNetwork(network)
                    android.util.Log.i("LiveFragment", "Successfully bound process to Wi-Fi network")
                } catch (e: Exception) {
                    android.util.Log.w("LiveFragment", "Failed to bindProcessToNetwork: ${e.message}")
                }

                if (nativePlayer != null) {
                    // 原廠模式：直連記錄器 8080 RTSP，無中間 Proxy 轉發，零延遲，直接交給 Native ffmpeg 解碼與 GLSurfaceView 渲染
                    sdpProxy?.stop()
                    sdpProxy = null
                    val directRtspUri = "rtsp://$host:8080/?action=stream"
                    android.util.Log.i("LiveFragment", "Starting stream with Native C/C++ ffmpeg direct frame decoding: $directRtspUri")
                    binding.glPlayerView.visibility = View.VISIBLE
                    binding.playerView.visibility = View.GONE
                    nativePlayer?.start(directRtspUri)
                } else {
                    binding.glPlayerView.visibility = View.GONE
                    binding.playerView.visibility = View.VISIBLE
                    sdpProxy?.stop()
                    val devId = "${host}@${viewModel.targetIdentity.value.epoch}"
                    val proxy = com.aladdin.dashcam.network.RtspSdpProxy(host, 8080, network, deviceIdentifier = devId)
                    val localPort = proxy.start()
                    sdpProxy = proxy
                    val rtspUri = "rtsp://127.0.0.1:$localPort/?action=stream"
                    val mediaItem = MediaItem.fromUri(rtspUri)
                    val rtspFactory = RtspMediaSource.Factory()
                        .setForceUseRtpTcp(true) // S1: Prevent Android UDP packet drop on Wi-Fi AP
                    val mediaSource = rtspFactory.createMediaSource(mediaItem)
                    p.setMediaSource(mediaSource)
                    p.prepare()
                    p.playWhenReady = true
                    android.util.Log.i("LiveFragment", "RtspMediaSource connected via RtspSdpProxy on port $localPort after restartStreaming ACK")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.e("LiveFragment", "Error during sequential stream setup: ${e.message}")
                p.stop()
                p.clearMediaItems()
                nativePlayer?.stop()
                sdpProxy?.stop()
                sdpProxy = null
                binding.streamOverlay.visibility = View.VISIBLE
                binding.pbStreamLoading.visibility = View.GONE
                binding.btnRetryStream.visibility = View.VISIBLE
                binding.tvStreamMessage.text = getString(R.string.device_status_stream_interrupted)
            }
        }
    }

    private fun setupListeners() {
        binding.btnToggleRecording.setOnClickListener {
            val isRecording = viewModel.deviceStatus.value.recordingStatus == RecordingStatus.RECORDING
            val targetDesired = !isRecording

            // S5: Direct intent execution without AlertDialog; immediate in-flight feedback
            binding.btnToggleRecording.isEnabled = false
            binding.btnToggleRecording.text = if (isRecording) {
                getString(R.string.btn_stopping_recording)
            } else {
                getString(R.string.btn_starting_recording)
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = viewModel.ensureRecording(targetDesired)
                if (!ok) {
                    Toast.makeText(
                        requireContext(),
                        if (isRecording) "停止錄影未執行或狀態未變更" else "開始錄影未執行",
                        Toast.LENGTH_SHORT
                    ).show()
                    updateRecordingButton(viewModel.deviceStatus.value.recordingStatus)
                }
            }
        }

        binding.btnSnapshot.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val res = viewModel.takeSnapshot()
                res.onSuccess {
                    Toast.makeText(requireContext(), "已成功拍攝照片", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(requireContext(), "拍照未執行：${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnRetryStream.setOnClickListener {
            val network = viewModel.wifiNetwork.value
            val host = viewModel.deviceHost.value
            com.aladdin.dashcam.network.RtspSdpProxy.clearCache(host)
            if (network == null) {
                Toast.makeText(requireContext(), "尚未連接行車記錄器專用 Wi-Fi", Toast.LENGTH_SHORT).show()
                binding.streamOverlay.visibility = View.VISIBLE
                binding.pbStreamLoading.visibility = View.GONE
                binding.btnRetryStream.visibility = View.VISIBLE
                binding.tvStreamMessage.text = getString(R.string.device_status_disconnected)
            } else {
                binding.streamOverlay.visibility = View.VISIBLE
                binding.pbStreamLoading.visibility = View.VISIBLE
                binding.btnRetryStream.visibility = View.GONE
                binding.tvStreamMessage.text = getString(R.string.stream_loading)
                lastStreamNetwork = null // force reset
                setupMediaStream(network)
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.refreshStatus()
                }
            }
        }

        binding.btnMoreOptions.setOnClickListener { view ->
            showMoreMenu(view)
        }

        binding.btnFullscreen.setOnClickListener {
            toggleFullscreen()
        }
        binding.root.findViewById<View>(R.id.btn_live_expand)?.setOnClickListener { toggleFullscreen() }
        binding.root.findViewById<View>(R.id.btn_live_pip)?.setOnClickListener { launchNavigationAndPip() }
        binding.batteryContainer.setOnClickListener {
            Toast.makeText(requireContext(), binding.ivBatteryIcon.contentDescription, Toast.LENGTH_SHORT).show()
        }
        binding.tvSdStatus.setOnClickListener {
            val estimate = viewModel.storageEstimate.value
            val info = if (viewModel.connectionStatus.value != ConnectionStatus.CONNECTED) {
                "記錄器未連線，尚無目前的記憶卡資訊。"
            } else if (estimate.totalBytes > 0 && estimate.freeBytes >= 0) {
                val gb = 1024.0 * 1024.0 * 1024.0
                String.format(Locale.getDefault(), "總容量：%.1f GB\n已用：%.1f GB\n可用：%.1f GB\n\n%s",
                    estimate.totalBytes / gb, estimate.usedBytes / gb, estimate.freeBytes / gb, estimate.statusText)
            } else estimate.statusText
            androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("記憶卡資訊")
                .setMessage(info).setPositiveButton("知道了", null).show()
        }

        binding.hudContainer.setOnClickListener {
            val context = requireContext()
            val fineGranted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarseGranted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!fineGranted && !coarseGranted) {
                (activity as? MainActivity)?.requestLocationPermission()
            }
        }

        // Toggle Live Mode: Normal vs Split vs Map vs Ride
        binding.toggleLiveMode?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                selectedModeId = checkedId
                applyLiveMode()
                if (checkedId == R.id.btn_mode_map || checkedId == R.id.btn_mode_split) {
                    (activity as? MainActivity)?.requestLocationPermission()
                }
            }
        }

        binding.fabMapCenter?.setOnClickListener {
            if (!viewModel.gpsStatus.value.hasPermission) {
                (activity as? MainActivity)?.requestLocationPermission()
                return@setOnClickListener
            }
            isMapFollowEnabled = true
            val gps = viewModel.gpsStatus.value
            if (gps.isLocked && gps.latitude != null && gps.longitude != null) {
                val latLng = LatLng(gps.latitude, gps.longitude)
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
                Toast.makeText(requireContext(), "已回到目前位置並開啟跟隨", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "尚未取得手機定位座標", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnMapClearTrajectory?.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("清除本次軌跡？")
                .setMessage("只清除手機目前顯示的騎乘軌跡，不影響記錄器影片。")
                .setPositiveButton("清除") { _, _ ->
                    viewModel.gpsSpeedometer.resetTrip()
                    trajectoryPolyline?.points = emptyList()
                }.setNegativeButton("取消", null).show()
        }

        binding.btnMapType.setOnClickListener {
            googleMap?.let { map ->
                val satellite = map.mapType != GoogleMap.MAP_TYPE_HYBRID
                map.mapType = if (satellite) GoogleMap.MAP_TYPE_HYBRID else GoogleMap.MAP_TYPE_NORMAL
                savedMapType = map.mapType
                binding.btnMapType.text = if (satellite) "一般地圖" else "衛星地圖"
            }
        }

        binding.fabMapRecord?.setOnClickListener {
            binding.btnToggleRecording.performClick()
        }

        binding.fabMapSnapshot?.setOnClickListener {
            binding.btnSnapshot.performClick()
        }
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, getString(R.string.action_pip_mode))
        val mainAct = activity as? MainActivity
        val isFs = mainAct?.isFullscreen == true
        popup.menu.add(0, 2, 1, if (isFs) getString(R.string.action_exit_fullscreen) else getString(R.string.action_enter_fullscreen))
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    launchNavigationAndPip()
                    true
                }
                2 -> {
                    toggleFullscreen()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    fun toggleFullscreen() {
        val mainAct = activity as? MainActivity ?: return
        val newState = !mainAct.isFullscreen
        mainAct.setFullscreen(newState)
        updateFullscreenLayout(newState)
    }

    fun updateFullscreenLayout(isFullscreen: Boolean) {
        if (_binding == null) return
        val controlsVisibility = if (isFullscreen) View.GONE else View.VISIBLE
        binding.statusStrip.visibility = controlsVisibility
        binding.layoutLiveModeToggle?.visibility = controlsVisibility
        binding.layoutNormalControls?.visibility = if (isFullscreen) View.GONE else (if (binding.toggleLiveMode?.checkedButtonId == R.id.btn_mode_normal) View.VISIBLE else View.GONE)
        binding.layoutRideMode?.visibility = if (isFullscreen) View.GONE else (if (binding.toggleLiveMode?.checkedButtonId == R.id.btn_mode_ride) View.VISIBLE else View.GONE)
        binding.btnFullscreen.visibility = View.VISIBLE
        binding.hudPipStatusContainer.visibility = if (isFullscreen) View.VISIBLE else View.GONE
        updatePreviewGeometry(isFullscreen)
        val iconRes = if (isFullscreen) android.R.drawable.ic_menu_close_clear_cancel else R.drawable.ic_live_fullscreen
        binding.btnFullscreen.setImageResource(iconRes)
        binding.btnFullscreen.contentDescription = if (isFullscreen) {
            getString(R.string.action_exit_fullscreen)
        } else {
            getString(R.string.action_enter_fullscreen)
        }
    }

    private fun updatePreviewGeometry(expanded: Boolean) {
        val actions = binding.root.findViewById<View>(R.id.live_view_actions)
        actions?.visibility = if (expanded) View.GONE else View.VISIBLE

        val sideControls = binding.root.findViewById<View>(R.id.side_controls_container)
        sideControls?.visibility = if (expanded) View.GONE else View.VISIBLE

        val constraints = ConstraintSet()
        constraints.clone(binding.root)
        if (sideControls != null) {
            // 橫向模式 (layout-land): 側邊欄控制
            if (expanded) {
                constraints.connect(R.id.preview_container, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            } else {
                constraints.connect(R.id.preview_container, ConstraintSet.END, R.id.side_controls_container, ConstraintSet.START)
            }
        } else {
            // 直向模式 (layout): 比例與高度約束
            constraints.setDimensionRatio(R.id.preview_container, if (expanded) null else "H,16:10")
            if (expanded) {
                constraints.connect(R.id.preview_container, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            } else {
                constraints.clear(R.id.preview_container, ConstraintSet.BOTTOM)
            }
        }
        constraints.applyTo(binding.root)
    }

    private fun launchNavigationAndPip() {
        try {
            val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
            mapIntent.setPackage("com.google.android.apps.maps")
            mapIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(mapIntent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "未安裝 Google Maps，直接開啟 PiP 浮窗", Toast.LENGTH_SHORT).show()
        }
        enterPictureInPictureMode()
    }

    fun enterPictureInPictureMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            activity?.enterPictureInPictureMode(params)
        } else {
            Toast.makeText(requireContext(), "此 Android 版本不支援 PiP", Toast.LENGTH_SHORT).show()
        }
    }

    fun updatePipLayout(isInPip: Boolean) {
        if (_binding == null) return
        if (!isInPip) {
            updateFullscreenLayout((activity as? MainActivity)?.isFullscreen == true)
            return
        }
        val vis = if (isInPip) View.GONE else View.VISIBLE
        binding.statusStrip.visibility = vis
        binding.layoutLiveModeToggle?.visibility = vis
        binding.layoutNormalControls?.visibility = vis
        binding.layoutRideMode?.visibility = View.GONE
        binding.root.findViewById<View>(R.id.side_controls_container)?.visibility = vis
        binding.btnFullscreen.visibility = vis
        binding.hudPipStatusContainer.visibility = if (isInPip) View.VISIBLE else View.GONE
        updatePreviewGeometry(isInPip || (activity as? MainActivity)?.isFullscreen == true)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recordingTimerDisplay.collect { timer ->
                        updateRecordingTimerUi(timer)
                    }
                }

                launch {
                    viewModel.deviceStatus.collect { status ->
                        // S3 / S4: Battery vector icons and accessibility texts
                        updateBatteryUi(status.batteryLevel, status.isCharging, status.lastUpdatedTimestamp != 0L)

                    }
                }

                launch {
                    kotlinx.coroutines.flow.combine(viewModel.storageEstimate, viewModel.connectionStatus) { estimate, connection ->
                        estimate to connection
                    }.collect { (estimate, connection) ->
                        if (connection != ConnectionStatus.CONNECTED) {
                            binding.tvSdStatus.text = "未連線"
                        } else if (estimate.totalBytes > 0 && estimate.freeBytes >= 0) {
                            val gb = 1024.0 * 1024.0 * 1024.0
                            binding.tvSdStatus.text = String.format(Locale.getDefault(), "SD: 餘 %.1f / 總 %.1f GB",
                                estimate.freeBytes / gb, estimate.totalBytes / gb)
                        } else if (estimate.isNormal) {
                            if (estimate.listedMediaTotalBytes > 0L) {
                                val mb = estimate.listedMediaTotalBytes / (1024.0 * 1024.0)
                                binding.tvSdStatus.text = String.format(Locale.getDefault(), "SD 正常 · 檔案 %.0fMB", mb)
                            } else {
                                binding.tvSdStatus.text = "SD 正常 · 錄影中"
                            }
                        } else if (estimate.hasSdCard) {
                            binding.tvSdStatus.text = "記憶卡異常"
                        } else {
                            binding.tvSdStatus.text = "未偵測到記憶卡"
                        }
                        binding.tvSdStatus.contentDescription = estimate.statusText
                        updateRecordingTimerUi(viewModel.recordingTimerDisplay.value)
                    }
                }

                launch {
                    viewModel.gpsStatus.collect { gps ->
                        (binding.tvHudSpeed.parent as View).visibility = if (gps.speedKmh != null) View.VISIBLE else View.GONE
                        binding.tvHudSpeed.text = if (gps.speedKmh != null) {
                            String.format("%02d", gps.speedKmh)
                        } else {
                            "--"
                        }

                        binding.tvHudGpsStatus.text = if (gps.isLocked) {
                            gps.sourceName
                        } else if (!gps.hasPermission) {
                            getString(R.string.hud_gps_disabled)
                        } else {
                            getString(R.string.hud_gps_searching)
                        }

                        // Ride Mode HUD Updates (safe call for landscape mode)
                        binding.speedGauge?.setSpeed(if (gps.isLocked) gps.speedKmh else null)
                        binding.tvRideAltitude?.text = if (gps.isLocked && gps.altitudeMeters != null) {
                            String.format(java.util.Locale.getDefault(), "%.0f m", gps.altitudeMeters)
                        } else {
                            "-- m"
                        }

                        binding.tvRideAscent?.text = if (gps.isLocked) {
                            String.format(
                                java.util.Locale.getDefault(),
                                "+%.0f m",
                                gps.accumulatedAscentMeters
                            )
                        } else {
                            "-- m"
                        }

                        val bearing = gps.bearingDegrees
                        binding.tvRideBearing?.text = if (gps.isLocked && bearing != null) {
                            val dirIdx = (((bearing + 22.5f) % 360) / 45).toInt()
                            val dirStr = when (dirIdx) {
                                0 -> "北"
                                1 -> "東北"
                                2 -> "東"
                                3 -> "東南"
                                4 -> "南"
                                5 -> "西南"
                                6 -> "西"
                                7 -> "西北"
                                else -> "北"
                            }
                            String.format(java.util.Locale.getDefault(), "%.0f° %s", bearing, dirStr)
                        } else {
                            "--°"
                        }

                        binding.tvRideGforce?.text = if (gps.accelerationG != null) {
                            String.format(java.util.Locale.getDefault(), "%.2f G", gps.accelerationG)
                        } else {
                            "-- G"
                        }

                        binding.tvRideGpsSource?.text = if (gps.isLocked) {
                            "資料來源：${gps.sourceName} (定位正常)"
                        } else if (!gps.hasPermission) {
                            "未授權位置權限，點擊上方 HUD 開啟"
                        } else {
                            "資料來源：${gps.sourceName} (衛星搜尋中...)"
                        }

                        updateMapLocation(gps)
                    }
                }

                launch {
                    kotlinx.coroutines.flow.combine(
                        viewModel.wifiNetwork,
                        viewModel.deviceHost,
                        viewModel.currentStreamOwner,
                        viewModel.targetIdentity
                    ) { net, host, owner, _ -> Triple(net, host, owner) }
                        .collect { (network, _, owner) ->
                            if (owner == DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK) {
                                // 相簿正在回放，暫停預覽並關閉 proxy 與 player 以釋放記錄器 8080 RTSP 端口
                                streamJob?.cancel()
                                streamJob = null
                                nativePlayer?.stop()
                                player?.stop()
                                sdpProxy?.stop()
                                sdpProxy = null
                                lastStreamNetwork = null
                                lastStreamHost = null
                                binding.streamOverlay.visibility = View.VISIBLE
                                binding.pbStreamLoading.visibility = View.GONE
                                binding.btnRetryStream.visibility = View.GONE
                                binding.tvStreamMessage.text = "相簿正在回放影片，即時預覽已暫停"
                            } else if (owner == DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW || owner == DashCamViewModel.MediaStreamOwner.NONE) {
                                setupMediaStream(network)
                            }
                        }
                }
            }
        }
    }

    private fun updateRecordingTimerUi(timer: RecordingTimerDisplay) {
        if (_binding == null) return
        val compact = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val connected = viewModel.connectionStatus.value == ConnectionStatus.CONNECTED
        binding.btnToggleRecording.isEnabled = connected && !timer.isUnknown
        binding.btnSnapshot.isEnabled = connected
        binding.btnToggleRecording.alpha = if (binding.btnToggleRecording.isEnabled) 1f else 0.35f
        binding.btnSnapshot.alpha = if (connected) 1f else 0.35f
        binding.fabMapRecord.isEnabled = connected && !timer.isUnknown
        binding.fabMapSnapshot.isEnabled = connected
        binding.fabMapRecord.alpha = if (binding.fabMapRecord.isEnabled) 1f else 0.35f
        binding.fabMapSnapshot.alpha = if (connected) 1f else 0.35f
        if (!connected) binding.tvSdStatus.text = "未連線"
        when (timer.status) {
            RecordingStatus.RECORDING -> {
                binding.ivRecordDot.visibility = View.VISIBLE
                binding.tvRecordStatus.text = timer.formattedText
                binding.btnToggleRecording.text = getString(if (compact) R.string.btn_stop_recording_compact else R.string.btn_stop_recording)
                binding.btnToggleRecording.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.error_red)

                // HUD / PiP indicator (C10)
                binding.ivHudRecordDot.visibility = View.VISIBLE
                binding.tvHudRecordText.text = timer.formattedText
                binding.tvHudRecordText.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.record_active_red)
                )
                binding.fabMapRecord?.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.error_red)
            }
            RecordingStatus.STOPPED -> {
                binding.ivRecordDot.visibility = View.INVISIBLE
                binding.tvRecordStatus.text = getString(R.string.recording_stopped)
                binding.btnToggleRecording.text = getString(if (compact) R.string.btn_start_recording_compact else R.string.btn_start_recording)
                binding.btnToggleRecording.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.accent_teal)

                // HUD / PiP indicator (C10)
                binding.ivHudRecordDot.visibility = View.INVISIBLE
                binding.tvHudRecordText.text = "已停錄"
                binding.tvHudRecordText.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.text_secondary)
                )
                binding.fabMapRecord?.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.accent_teal)
            }
            RecordingStatus.UNKNOWN -> {
                binding.ivRecordDot.visibility = View.INVISIBLE
                binding.tvRecordStatus.text = getString(R.string.recording_unknown)
                binding.btnToggleRecording.text = getString(if (compact) R.string.btn_start_recording_compact else R.string.btn_start_recording)
                binding.btnToggleRecording.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.accent_teal)

                // HUD / PiP indicator (C10)
                binding.ivHudRecordDot.visibility = View.INVISIBLE
                binding.tvHudRecordText.text = "狀態未知"
                binding.tvHudRecordText.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.warning_yellow)
                )
                binding.fabMapRecord?.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.accent_teal)
            }
        }
    }

    private fun updateRecordingButton(status: RecordingStatus) {
        val currentTimer = viewModel.recordingTimerDisplay.value
        updateRecordingTimerUi(currentTimer)
    }

    private fun updateBatteryUi(rawBatteryLevel: Int, isCharging: Boolean, isStatusValid: Boolean) {
        if (_binding == null) return
        val isValid = isStatusValid && rawBatteryLevel in 0..4
        val iconRes = when {
            !isValid -> if (isCharging) R.drawable.ic_battery_charging_unknown else R.drawable.ic_battery_unknown
            isCharging -> when (rawBatteryLevel) {
                4 -> R.drawable.ic_battery_charging_4
                3 -> R.drawable.ic_battery_charging_3
                2 -> R.drawable.ic_battery_charging_2
                1 -> R.drawable.ic_battery_charging_1
                else -> R.drawable.ic_battery_charging_0
            }
            else -> when (rawBatteryLevel) {
                4 -> R.drawable.ic_battery_4
                3 -> R.drawable.ic_battery_3
                2 -> R.drawable.ic_battery_2
                1 -> R.drawable.ic_battery_1
                else -> R.drawable.ic_battery_0
            }
        }
        val cdText = when {
            !isValid -> if (isCharging) "${getString(R.string.battery_cd_charging_prefix)}${getString(R.string.battery_cd_unknown)}" else getString(R.string.battery_cd_unknown)
            isCharging -> when (rawBatteryLevel) {
                4 -> "${getString(R.string.battery_cd_charging_prefix)}${getString(R.string.battery_cd_level_4)}"
                3 -> "${getString(R.string.battery_cd_charging_prefix)}${getString(R.string.battery_cd_level_3)}"
                2 -> "${getString(R.string.battery_cd_charging_prefix)}${getString(R.string.battery_cd_level_2)}"
                1 -> "${getString(R.string.battery_cd_charging_prefix)}${getString(R.string.battery_cd_level_1)}"
                else -> "${getString(R.string.battery_cd_charging_prefix)}${getString(R.string.battery_cd_level_0)}"
            }
            else -> when (rawBatteryLevel) {
                4 -> getString(R.string.battery_cd_level_4)
                3 -> getString(R.string.battery_cd_level_3)
                2 -> getString(R.string.battery_cd_level_2)
                1 -> getString(R.string.battery_cd_level_1)
                else -> getString(R.string.battery_cd_level_0)
            }
        }

        binding.ivBatteryIcon.setImageResource(iconRes)
        binding.ivBatteryIcon.contentDescription = cdText
        binding.ivHudBatteryIcon.setImageResource(iconRes)
        binding.ivHudBatteryIcon.contentDescription = cdText
    }

    private fun updateMapLocation(gps: GpsStatus) {
        val map = googleMap ?: return
        // Accelerometer updates share this flow; they must not redraw the route or restart camera animations.
        val previous = lastMapStatus
        if (previous != null && previous.lastUpdateElapsedRealtime == gps.lastUpdateElapsedRealtime &&
            previous.isLocked == gps.isLocked && previous.hasPermission == gps.hasPermission &&
            previous.trajectory === gps.trajectory) return
        lastMapStatus = gps
        binding.fabMapCenter.alpha = if (gps.isLocked) 1f else 0.65f
        if (gps.isLocked && gps.latitude != null && gps.longitude != null) {
            val currentPos = LatLng(gps.latitude, gps.longitude)
            if (phoneMarker == null) {
                val markerOptions = MarkerOptions()
                    .position(currentPos)
                    .title("手機目前位置")
                    .snippet("速度: ${gps.speedKmh?.toString() ?: "--"} km/h")
                    .flat(true)
                    .anchor(0.5f, 0.5f)
                phoneMarker = map.addMarker(markerOptions)
                if (isMapFollowEnabled && !hasSavedMapCamera) map.moveCamera(CameraUpdateFactory.newLatLngZoom(currentPos, 16f))
            } else {
                phoneMarker?.position = currentPos
                if (gps.bearingDegrees != null) {
                    phoneMarker?.rotation = gps.bearingDegrees
                }
                phoneMarker?.snippet = "速度: ${gps.speedKmh?.toString() ?: "--"} km/h"
                phoneMarker?.alpha = 1f
            }

            if (isMapFollowEnabled) {
                map.animateCamera(CameraUpdateFactory.newLatLng(currentPos))
            }

            if (gps.trajectory.isNotEmpty()) {
                val pts = gps.trajectory.map { LatLng(it.latitude, it.longitude) }
                trajectoryPolyline?.points = pts
            } else {
                trajectoryPolyline?.points = emptyList()
            }

            binding.tvMapGpsInfo?.text = "● ${gps.sourceName}"
            binding.tvMapSpeedInfo?.text = String.format(
                Locale.getDefault(),
                "%s · ±%.0fm",
                if (gps.speedKmh != null) "${gps.speedKmh} km/h" else "--",
                gps.accuracyMeters
            )
        } else {
            phoneMarker?.alpha = 0.35f
            binding.tvMapGpsInfo?.text = if (!gps.hasPermission) "尚未開啟定位" else "GPS 搜尋中"
            binding.tvMapSpeedInfo?.text = "-- km/h · 精度未知"
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            binding.mapView.onStart()
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        viewModel.gpsSpeedometer.startListening()
        try {
            binding.mapView.onResume()
        } catch (_: Exception) {}
        if (viewModel.currentStreamOwner.value == DashCamViewModel.MediaStreamOwner.NONE) {
            viewModel.requestStreamOwner(DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            binding.mapView.onPause()
        } catch (_: Exception) {}
        if (viewModel.currentStreamOwner.value == DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW) {
            viewModel.releaseStreamOwner(DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW)
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.gpsSpeedometer.stopListening()
        try {
            binding.mapView.onStop()
        } catch (_: Exception) {}
        streamJob?.cancel()
        streamJob = null
        nativePlayer?.stop()
        player?.stop()
        sdpProxy?.stop()
        sdpProxy = null
        lastStreamNetwork = null
        lastStreamHost = null
    }

    override fun onDestroyView() {
        mapLoadJob?.cancel()
        mapLoaded = false
        mapRequested = false
        try {
            binding.mapView.onDestroy()
        } catch (_: Exception) {}
        googleMap = null
        lastMapStatus = null
        phoneMarker = null
        trajectoryPolyline = null
        super.onDestroyView()
        if (viewModel.currentStreamOwner.value == DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW) {
            viewModel.releaseStreamOwner(DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW)
        }
        streamJob?.cancel()
        streamJob = null
        nativePlayer?.release()
        nativePlayer = null
        sdpProxy?.stop()
        sdpProxy = null
        player?.release()
        player = null
        _binding = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            binding.mapView.onLowMemory()
        } catch (_: Exception) {}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("live_mode", selectedModeId)
        outState.putBoolean("map_follow", isMapFollowEnabled)
        outState.putBoolean("map_camera", googleMap != null)
        outState.putInt("map_type", savedMapType)
        try {
            _binding?.mapView?.onSaveInstanceState(outState)
        } catch (_: Exception) {}
    }
}
