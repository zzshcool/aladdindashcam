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
        initPlayer()
        setupListeners()
        observeViewModel()
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

                // 8081 命令確認成功後，才啟動本機 Proxy 與 ExoPlayer prepare
                sdpProxy?.stop()
                val proxy = com.aladdin.dashcam.network.RtspSdpProxy(host, 8080, network)
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
        binding.root.findViewById<View>(R.id.action_buttons_container)?.visibility = controlsVisibility
        binding.root.findViewById<View>(R.id.side_controls_container)?.visibility = controlsVisibility
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
        val actions = binding.root.findViewById<View>(R.id.live_view_actions) ?: return
        actions.visibility = if (expanded) View.GONE else View.VISIBLE
        val constraints = ConstraintSet()
        constraints.clone(binding.root)
        constraints.setDimensionRatio(R.id.preview_container, if (expanded) null else "H,16:10")
        if (expanded) {
            constraints.connect(R.id.preview_container, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        } else {
            constraints.clear(R.id.preview_container, ConstraintSet.BOTTOM)
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
        binding.root.findViewById<View>(R.id.action_buttons_container)?.visibility = vis
        binding.root.findViewById<View>(R.id.side_controls_container)?.visibility = vis
        binding.btnFullscreen.visibility = vis
        binding.hudPipStatusContainer.visibility = if (isInPip) View.VISIBLE else View.GONE
        updatePreviewGeometry(isInPip || (activity as? MainActivity)?.isFullscreen == true)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.deviceStatus.collect { status ->
                        // S5: Recording status and button state
                        updateRecordingButton(status.recordingStatus)
                        if (status.recordingStatus == RecordingStatus.RECORDING) {
                            val seconds = status.rawRecordSeconds
                            binding.tvRecordStatus.text = getString(
                                R.string.recording_elapsed,
                                seconds / 3600, (seconds / 60) % 60, seconds % 60
                            )
                        }

                        // S3 / S4: Battery vector icons and accessibility texts
                        updateBatteryUi(status.batteryLevel, status.isCharging, status.lastUpdatedTimestamp != 0L)

                        // V1 / S2: 原廠 APK 證實 bytes 5~8 語意為錄影時間 (times = rawRecordSeconds * 1000)，非剩餘容量。在無可信容量來源時以未知呈現，不自行估算
                        binding.tvSdStatus.text = getString(R.string.sd_remaining_unknown)
                    }
                }

                launch {
                    viewModel.gpsStatus.collect { gps ->
                        (binding.tvHudSpeed.parent as View).visibility = if (gps.speedKmh != null) View.VISIBLE else View.GONE
                        // C08: Display "--" when speed is unknown, formatted speed only when locked
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
                    }
                }

                launch {
                    kotlinx.coroutines.flow.combine(
                        viewModel.wifiNetwork,
                        viewModel.deviceHost
                    ) { net, host -> net to host }
                        .collect { (network, _) ->
                            setupMediaStream(network)
                        }
                }
            }
        }
    }

    private fun updateRecordingButton(status: RecordingStatus) {
        if (_binding == null) return
        val compact = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        binding.btnToggleRecording.isEnabled = true
        when (status) {
            RecordingStatus.RECORDING -> {
                binding.ivRecordDot.visibility = View.VISIBLE
                binding.tvRecordStatus.text = getString(R.string.recording_active)
                binding.btnToggleRecording.text = getString(if (compact) R.string.btn_stop_recording_compact else R.string.btn_stop_recording)
                binding.btnToggleRecording.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.error_red)

                // HUD / PiP indicator (C10)
                binding.ivHudRecordDot.visibility = View.VISIBLE
                binding.tvHudRecordText.text = "錄影中"
                binding.tvHudRecordText.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.record_active_red)
                )
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
            }
        }
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

    override fun onDestroyView() {
        super.onDestroyView()
        streamJob?.cancel()
        streamJob = null
        sdpProxy?.stop()
        sdpProxy = null
        player?.release()
        player = null
        _binding = null
    }
}
