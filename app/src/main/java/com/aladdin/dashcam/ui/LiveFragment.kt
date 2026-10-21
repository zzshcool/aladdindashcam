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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.aladdin.dashcam.R
import com.aladdin.dashcam.databinding.FragmentLiveBinding
import com.aladdin.dashcam.model.ConnectionStatus
import com.aladdin.dashcam.model.RecordingStatus
import kotlinx.coroutines.launch

class LiveFragment : Fragment() {
    private var _binding: FragmentLiveBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashCamViewModel by activityViewModels()
    private var player: ExoPlayer? = null

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
        val newPlayer = ExoPlayer.Builder(context).build()
        binding.playerView.player = newPlayer
        this.player = newPlayer

        newPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                binding.streamOverlay.visibility = View.VISIBLE
                binding.tvStreamMessage.text = getString(R.string.device_status_stream_interrupted)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    binding.streamOverlay.visibility = View.GONE
                }
            }
        })

        // VR-03: Default to waiting overlay until Wi-Fi network is verified and bound
        binding.streamOverlay.visibility = View.VISIBLE
        binding.tvStreamMessage.text = "等待行車記錄器 Wi-Fi 連線..."
    }

    private fun setupMediaStream(network: android.net.Network?) {
        val p = player ?: return
        if (network == null) {
            p.stop()
            binding.streamOverlay.visibility = View.VISIBLE
            binding.tvStreamMessage.text = "等待行車記錄器 Wi-Fi 連線..."
            return
        }

        // RTSP Stream endpoint per specification 25.2 (dynamic host support)
        val host = viewModel.deviceHost.value
        val rtspUri = "rtsp://$host:8080/?action=stream"
        try {
            val mediaItem = MediaItem.fromUri(rtspUri)
            val rtspFactory = RtspMediaSource.Factory()
            try {
                rtspFactory.setSocketFactory(network.socketFactory)
                android.util.Log.i("LiveFragment", "RtspMediaSource bound to Wi-Fi network: $network")
            } catch (e: Exception) {
                // VR-03 / R2-07: Bind failure must fail explicitly and must NOT fall back silently to default network!
                android.util.Log.e("LiveFragment", "Fatal: Failed to bind Wi-Fi socketFactory: ${e.message}")
                p.stop()
                p.clearMediaItems()
                binding.streamOverlay.visibility = View.VISIBLE
                binding.tvStreamMessage.text = "Wi-Fi 網路綁定失敗，已停止播放 (點擊重試)"
                return
            }
            val mediaSource = rtspFactory.createMediaSource(mediaItem)
            p.setMediaSource(mediaSource)
            p.prepare()
            p.playWhenReady = true
        } catch (e: Exception) {
            p.stop()
            p.clearMediaItems()
            binding.streamOverlay.visibility = View.VISIBLE
            binding.tvStreamMessage.text = getString(R.string.device_status_stream_interrupted)
        }
    }

    private fun setupListeners() {
        binding.btnToggleRecording.setOnClickListener {
            val isRecording = viewModel.deviceStatus.value.recordingStatus == RecordingStatus.RECORDING
            if (isRecording) {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("停止錄影確認")
                    .setMessage("確定要停止錄影嗎？騎乘中停止錄影可能遺失關鍵路況證據。")
                    .setPositiveButton("確定停止") { _, _ ->
                        performToggleRecording()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                performToggleRecording()
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
            if (network == null) {
                Toast.makeText(requireContext(), "尚未連接行車記錄器專用 Wi-Fi", Toast.LENGTH_SHORT).show()
                binding.streamOverlay.visibility = View.VISIBLE
                binding.tvStreamMessage.text = "等待行車記錄器 Wi-Fi 連線..."
            } else {
                binding.streamOverlay.visibility = View.GONE
                setupMediaStream(network)
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.refreshStatus()
                }
            }
        }

        binding.btnPipNavigation.setOnClickListener {
            launchNavigationAndPip()
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

    private fun launchNavigationAndPip() {
        // Attempt to launch external navigation (Google Maps) per Chapter 5.5
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

    private fun performToggleRecording() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = viewModel.toggleRecording()
            if (!ok) {
                Toast.makeText(requireContext(), "錄影切換未執行或狀態不明，已啟動安全保護", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun updatePipLayout(isInPip: Boolean) {
        if (_binding == null) return
        if (isInPip) {
            binding.headerBar.visibility = View.GONE
            binding.statusStrip.visibility = View.GONE
            binding.actionButtonsContainer.visibility = View.GONE
        } else {
            binding.headerBar.visibility = View.VISIBLE
            binding.statusStrip.visibility = View.VISIBLE
            binding.actionButtonsContainer.visibility = View.VISIBLE
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionStatus.collect { conn ->
                        val (text, colorRes) = when (conn) {
                            ConnectionStatus.CONNECTED -> getString(R.string.device_status_connected) to R.color.status_green
                            ConnectionStatus.CONNECTING -> getString(R.string.device_status_connecting) to R.color.warning_yellow
                            ConnectionStatus.STREAM_INTERRUPTED -> getString(R.string.device_status_stream_interrupted) to R.color.warning_yellow
                            ConnectionStatus.DISCONNECTED -> getString(R.string.device_status_disconnected) to R.color.text_secondary
                        }
                        binding.tvTopConnectionStatus.text = text
                        binding.tvTopConnectionStatus.setTextColor(
                            ContextCompat.getColor(requireContext(), colorRes)
                        )
                        if (conn == ConnectionStatus.DISCONNECTED || conn == ConnectionStatus.STREAM_INTERRUPTED) {
                            binding.streamOverlay.visibility = View.VISIBLE
                            binding.tvStreamMessage.text = if (conn == ConnectionStatus.DISCONNECTED) {
                                getString(R.string.device_status_disconnected)
                            } else {
                                getString(R.string.device_status_stream_interrupted)
                            }
                        }
                    }
                }
                launch {
                    viewModel.deviceStatus.collect { status ->
                        // Recording status
                        when (status.recordingStatus) {
                            RecordingStatus.RECORDING -> {
                                binding.ivRecordDot.visibility = View.VISIBLE
                                binding.tvRecordStatus.text = getString(R.string.recording_active)
                                binding.btnToggleRecording.text = getString(R.string.btn_stop_recording)
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
                                binding.btnToggleRecording.text = getString(R.string.btn_start_recording)
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
                                binding.btnToggleRecording.text = getString(R.string.btn_start_recording)

                                // HUD / PiP indicator (C10)
                                binding.ivHudRecordDot.visibility = View.INVISIBLE
                                binding.tvHudRecordText.text = "狀態未知"
                                binding.tvHudRecordText.setTextColor(
                                    ContextCompat.getColor(requireContext(), R.color.warning_yellow)
                                )
                            }
                        }

                        // Battery: Semantic level (0..4) and independent charging flag (UX-03 / F4)
                        val rawBatteryLevel = status.batteryLevel
                        val isBatteryValid = status.lastUpdatedTimestamp != 0L && rawBatteryLevel in 0..4
                        val baseBatteryText = when {
                            !isBatteryValid -> getString(R.string.battery_unknown)
                            rawBatteryLevel == 4 -> getString(R.string.battery_level_4)
                            rawBatteryLevel == 3 -> getString(R.string.battery_level_3)
                            rawBatteryLevel == 2 -> getString(R.string.battery_level_2)
                            rawBatteryLevel == 1 -> getString(R.string.battery_level_1)
                            rawBatteryLevel == 0 -> getString(R.string.battery_level_0)
                            else -> getString(R.string.battery_unknown)
                        }
                        val batteryStr = if (status.isCharging && isBatteryValid) {
                            getString(R.string.battery_charging_prefix) + baseBatteryText
                        } else if (status.isCharging) {
                            getString(R.string.battery_charging_prefix) + getString(R.string.battery_unknown)
                        } else {
                            baseBatteryText
                        }
                        binding.tvBatteryLevel.text = batteryStr

                        // HUD / PiP simplified battery indicator (F4: strictly display unknown when out of range)
                        binding.tvHudBatteryText.text = when {
                            !isBatteryValid -> "電量--"
                            status.isCharging -> "⚡$rawBatteryLevel/4"
                            else -> "$rawBatteryLevel/4"
                        }

                        // SD remaining
                        val minutes = status.remainingRecordingSeconds / 60
                        binding.tvSdStatus.text = if (status.lastUpdatedTimestamp == 0L) "剩餘: --" else "剩餘: ${minutes}分"
                    }
                }

                launch {
                    viewModel.gpsStatus.collect { gps ->
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
                    viewModel.wifiNetwork.collect { network ->
                        setupMediaStream(network)
                    }
                }

                launch {
                    viewModel.deviceHost.collect { _ ->
                        val network = viewModel.wifiNetwork.value
                        if (network != null) {
                            setupMediaStream(network)
                        }
                    }
                }

                launch {
                    viewModel.connectionStatus.collect { conn ->
                        if (conn == ConnectionStatus.DISCONNECTED) {
                            binding.streamOverlay.visibility = View.VISIBLE
                            binding.tvStreamMessage.text = getString(R.string.device_status_disconnected)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
        _binding = null
    }
}
