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

    private var lastStreamNetwork: android.net.Network? = null
    private var lastStreamHost: String? = null
    private var sdpProxy: com.aladdin.dashcam.network.RtspSdpProxy? = null
    private var streamJob: Job? = null

    private var googleMap: GoogleMap? = null
    private var phoneMarker: Marker? = null
    private var trajectoryPolyline: Polyline? = null
    private var isMapFollowEnabled = true

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
        initMap()
        setupListeners()
        observeViewModel()
    }

    private fun initMap() {
        try {
            binding.mapView.getMapAsync { map ->
                this.googleMap = map
                try {
                    map.uiSettings.isZoomControlsEnabled = true
                    map.uiSettings.isCompassEnabled = true
                    map.uiSettings.isMyLocationButtonEnabled = false
                } catch (_: Exception) {}

                val polylineOptions = PolylineOptions()
                    .color(ContextCompat.getColor(requireContext(), R.color.accent_teal))
                    .width(10f)
                    .geodesic(true)
                trajectoryPolyline = map.addPolyline(polylineOptions)

                map.setOnCameraMoveStartedListener { reason ->
                    if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                        isMapFollowEnabled = false
                    }
                }

                updateMapLocation(viewModel.gpsStatus.value)
            }
        } catch (e: Exception) {
            android.util.Log.e("LiveFragment", "getMapAsync exception: ${e.message}")
        }
    }

    private fun initPlayer() {
        val context = requireContext()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(300, 1000, 150, 300)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val newPlayer = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
        binding.playerView.player = newPlayer
        this.player = newPlayer

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
                    delay(1000)
                    val bufferedMs = newPlayer.totalBufferedDuration
                    val speed = when {
                        !newPlayer.isPlaying || bufferedMs <= 800 -> 1f
                        bufferedMs > 1100 -> 1.05f
                        else -> newPlayer.playbackParameters.speed
                    }
                    if (newPlayer.playbackParameters.speed != speed) {
                        newPlayer.playbackParameters = PlaybackParameters(speed)
                    }
                    if (++sample % 5 != 0) continue
                    val counters = newPlayer.videoDecoderCounters ?: continue
                    counters.ensureUpdated()
                    val frames = counters.renderedOutputBufferCount
                    android.util.Log.d("LiveFragment", "Video progress: rendered=$frames, dropped=${counters.droppedBufferCount}, bufferedMs=$bufferedMs, speed=$speed")
                    if (newPlayer.isPlaying && frames > 0 && frames == previousFrames) {
                        unchangedSamples++
                        if (unchangedSamples >= 2) {
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
        if (network == null) {
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
            binding.tvStreamMessage.text = getString(R.string.device_status_disconnected)
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

                // 8081 命令確認成功後，給予設備管線 350ms 穩定時間，才啟動本機 Proxy 與 ExoPlayer prepare
                kotlinx.coroutines.delay(350)
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
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.e("LiveFragment", "Error during sequential stream setup: ${e.message}")
                p.stop()
                p.clearMediaItems()
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
                when (checkedId) {
                    R.id.btn_mode_normal -> {
                        binding.previewContainer.visibility = View.VISIBLE
                        binding.layoutNormalControls?.visibility = View.VISIBLE
                        binding.mapContainer?.visibility = View.GONE
                        binding.layoutRideMode?.visibility = View.GONE
                    }
                    R.id.btn_mode_split -> {
                        binding.previewContainer.visibility = View.VISIBLE
                        binding.layoutNormalControls?.visibility = View.GONE
                        binding.mapContainer?.visibility = View.VISIBLE
                        binding.layoutRideMode?.visibility = View.GONE
                    }
                    R.id.btn_mode_map -> {
                        binding.previewContainer.visibility = View.GONE
                        binding.layoutNormalControls?.visibility = View.GONE
                        binding.mapContainer?.visibility = View.VISIBLE
                        binding.layoutRideMode?.visibility = View.GONE
                    }
                    R.id.btn_mode_ride -> {
                        binding.previewContainer.visibility = View.VISIBLE
                        binding.layoutNormalControls?.visibility = View.GONE
                        binding.mapContainer?.visibility = View.GONE
                        binding.layoutRideMode?.visibility = View.VISIBLE
                    }
                }
            }
        }

        binding.fabMapCenter?.setOnClickListener {
            isMapFollowEnabled = true
            val gps = viewModel.gpsStatus.value
            if (gps.latitude != null && gps.longitude != null) {
                val latLng = LatLng(gps.latitude, gps.longitude)
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
                Toast.makeText(requireContext(), "已回到目前位置並開啟跟隨", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "尚未取得手機定位座標", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnMapClearTrajectory?.setOnClickListener {
            viewModel.gpsSpeedometer.resetTrip()
            trajectoryPolyline?.points = emptyList()
            Toast.makeText(requireContext(), "已清除行駛軌跡", Toast.LENGTH_SHORT).show()
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

                        val estimate = viewModel.storageEstimate.value
                        // SD Card status: 總容量 / 已用 / 剩餘可用容量 (滿足驗收需求，嚴格區分缺欄位與無卡)
                        if (estimate != null && estimate.totalBytes > 0) {
                            val totalGb = estimate.totalBytes / (1024.0 * 1024.0 * 1024.0)
                            val freeGb = estimate.freeBytes / (1024.0 * 1024.0 * 1024.0)
                            val usedGb = estimate.usedBytes / (1024.0 * 1024.0 * 1024.0)
                            val overwriteStr = if (estimate.minutesUntilOverwrite > 0) " (約剩餘 ${estimate.minutesUntilOverwrite} 分)" else ""
                            binding.tvSdStatus.text = String.format(Locale.getDefault(), "SD: 餘 %.1fG/用 %.1fG/總 %.1fG%s", freeGb, usedGb, totalGb, overwriteStr)
                        } else if (estimate != null && estimate.isCapacityFieldMissing) {
                            if (estimate.usedBytes > 0L) {
                                val usedMb = estimate.usedBytes / (1024.0 * 1024.0)
                                binding.tvSdStatus.text = String.format(Locale.getDefault(), "SD: 正常在位 (已載相簿 %.1fM, 總量韌體未報)", usedMb)
                            } else {
                                binding.tvSdStatus.text = "SD: 正常在位 (容量 bytes 韌體未提供)"
                            }
                        } else if (status.sdTotalBytes > 0) {
                            val totalGb = status.sdTotalBytes / (1024.0 * 1024.0 * 1024.0)
                            val freeGb = status.sdFreeBytes / (1024.0 * 1024.0 * 1024.0)
                            val usedGb = (status.sdTotalBytes - status.sdFreeBytes).coerceAtLeast(0L) / (1024.0 * 1024.0 * 1024.0)
                            binding.tvSdStatus.text = String.format(Locale.getDefault(), "SD: 餘 %.1fG/用 %.1fG/總 %.1fG", freeGb, usedGb, totalGb)
                        } else {
                            binding.tvSdStatus.text = estimate?.statusText ?: getString(R.string.sd_remaining_unknown)
                        }
                    }
                }

                launch {
                    viewModel.storageEstimate.collect { estimate ->
                        if (estimate.totalBytes > 0) {
                            val totalGb = estimate.totalBytes / (1024.0 * 1024.0 * 1024.0)
                            val freeGb = estimate.freeBytes / (1024.0 * 1024.0 * 1024.0)
                            val usedGb = estimate.usedBytes / (1024.0 * 1024.0 * 1024.0)
                            val overwriteStr = if (estimate.minutesUntilOverwrite > 0) " (約剩餘 ${estimate.minutesUntilOverwrite} 分)" else ""
                            binding.tvSdStatus.text = String.format(Locale.getDefault(), "SD: 餘 %.1fG/用 %.1fG/總 %.1fG%s", freeGb, usedGb, totalGb, overwriteStr)
                        } else if (estimate.isCapacityFieldMissing) {
                            if (estimate.usedBytes > 0L) {
                                val usedMb = estimate.usedBytes / (1024.0 * 1024.0)
                                binding.tvSdStatus.text = String.format(Locale.getDefault(), "SD: 正常在位 (已載相簿 %.1fM, 總量韌體未報)", usedMb)
                            } else {
                                binding.tvSdStatus.text = "SD: 正常在位 (容量 bytes 韌體未提供)"
                            }
                        } else {
                            binding.tvSdStatus.text = estimate.statusText
                        }
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
                            getString(R.string.hud_gps_locked)
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
                        viewModel.currentStreamOwner
                    ) { net, host, owner -> Triple(net, host, owner) }
                        .collect { (network, _, owner) ->
                            if (owner == DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK) {
                                // 相簿正在回放，暫停預覽並關閉 proxy 與 player 以釋放記錄器 8080 RTSP 端口
                                streamJob?.cancel()
                                streamJob = null
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
        binding.btnToggleRecording.isEnabled = true
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
        if (gps.latitude != null && gps.longitude != null) {
            val currentPos = LatLng(gps.latitude, gps.longitude)
            if (phoneMarker == null) {
                val markerOptions = MarkerOptions()
                    .position(currentPos)
                    .title("手機目前位置")
                    .snippet("速度: ${gps.speedKmh ?: 0} km/h")
                    .flat(true)
                    .anchor(0.5f, 0.5f)
                phoneMarker = map.addMarker(markerOptions)
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(currentPos, 16f))
            } else {
                phoneMarker?.position = currentPos
                if (gps.bearingDegrees != null) {
                    phoneMarker?.rotation = gps.bearingDegrees
                }
                phoneMarker?.snippet = "速度: ${gps.speedKmh ?: 0} km/h"
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

            binding.tvMapGpsInfo?.text = "手機定位 (與影像非毫秒級對齊)"
            binding.tvMapSpeedInfo?.text = String.format(
                Locale.getDefault(),
                "速度: %s · 精度: ±%.0fm",
                if (gps.speedKmh != null) "${gps.speedKmh} km/h" else "--",
                gps.accuracyMeters
            )
        } else {
            binding.tvMapGpsInfo?.text = if (!gps.hasPermission) "未取得定位權限 (即時錄影正常)" else "手機 GPS 搜尋中..."
            binding.tvMapSpeedInfo?.text = "速度: -- km/h · 精度: 未知"
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
        try {
            binding.mapView.onStop()
        } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        try {
            binding.mapView.onDestroy()
        } catch (_: Exception) {}
        googleMap = null
        phoneMarker = null
        trajectoryPolyline = null
        super.onDestroyView()
        if (viewModel.currentStreamOwner.value == DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW) {
            viewModel.releaseStreamOwner(DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW)
        }
        streamJob?.cancel()
        streamJob = null
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
        try {
            _binding?.mapView?.onSaveInstanceState(outState)
        } catch (_: Exception) {}
    }
}
