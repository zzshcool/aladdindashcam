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

        // RTSP Stream endpoint per specification 25.2
        val rtspUri = "rtsp://192.168.25.1:8080/?action=stream"
        try {
            val mediaItem = MediaItem.fromUri(rtspUri)
            val rtspFactory = RtspMediaSource.Factory()
            try {
                rtspFactory.setSocketFactory(network.socketFactory)
                android.util.Log.i("LiveFragment", "RtspMediaSource bound to Wi-Fi network: $network")
            } catch (e: Exception) {
                android.util.Log.w("LiveFragment", "Could not bind Wi-Fi socketFactory: ${e.message}")
            }
            val mediaSource = rtspFactory.createMediaSource(mediaItem)
            p.setMediaSource(mediaSource)
            p.prepare()
            p.playWhenReady = true
        } catch (e: Exception) {
            binding.streamOverlay.visibility = View.VISIBLE
            binding.tvStreamMessage.text = getString(R.string.device_status_stream_interrupted)
        }
    }

    private fun setupListeners() {
        binding.btnToggleRecording.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = viewModel.toggleRecording()
                if (!ok) {
                    Toast.makeText(requireContext(), "錄影切換未執行或狀態不明，已啟動安全保護", Toast.LENGTH_SHORT).show()
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

    fun updatePipLayout(isInPip: Boolean) {
        if (_binding == null) return
        if (isInPip) {
            binding.statusStrip.visibility = View.GONE
            binding.actionButtonsContainer.visibility = View.GONE
        } else {
            binding.statusStrip.visibility = View.VISIBLE
            binding.actionButtonsContainer.visibility = View.VISIBLE
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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

                        // Battery
                        val batteryStr = if (status.lastUpdatedTimestamp == 0L) {
                            "電量: 未知"
                        } else {
                            "電量: 等級${status.batteryLevel}"
                        }
                        binding.tvBatteryLevel.text = batteryStr
                        binding.tvHudBatteryText.text = if (status.lastUpdatedTimestamp == 0L) "電量--" else "等級${status.batteryLevel}"

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
