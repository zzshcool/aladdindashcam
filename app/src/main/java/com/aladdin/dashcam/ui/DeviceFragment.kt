package com.aladdin.dashcam.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aladdin.dashcam.databinding.FragmentDeviceBinding
import com.aladdin.dashcam.model.ConnectionStatus
import kotlinx.coroutines.launch

class DeviceFragment : Fragment() {
    private var _binding: FragmentDeviceBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashCamViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeviceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeSettings()
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.captureResolutionDisplay.collect { resText ->
                        binding.tvCurrentCaptureRes.text = resText
                    }
                }
                launch {
                    viewModel.captureQualityDisplay.collect { qualityText ->
                        binding.tvCurrentCaptureQuality.text = qualityText
                    }
                }
                launch {
                    viewModel.resolutionDisplay.collect { resText ->
                        binding.tvCurrentResolution.text = resText
                    }
                }
                launch {
                    viewModel.loopDisplay.collect { loopText ->
                        binding.tvCurrentLoop.text = loopText
                    }
                }
                launch {
                    viewModel.exposureDisplay.collect { expText ->
                        binding.tvCurrentExposure.text = expText
                    }
                }
                launch {
                    viewModel.watermarkDisplay.collect { wmText ->
                        binding.tvCurrentWatermark.text = wmText
                    }
                }
                launch {
                    viewModel.audioRecordingDisplay.collect { audioText ->
                        binding.tvCurrentAudio.text = audioText
                        binding.switchAudioRecord.isChecked = (audioText == "開啟")
                    }
                }
                launch {
                    viewModel.firmwareVersionDisplay.collect { fwText ->
                        binding.tvDeviceFirmware.text = fwText
                    }
                }
                launch {
                    viewModel.wifiSsidDisplay.collect { ssidText ->
                        binding.tvWifiName.text = ssidText
                    }
                }
                launch {
                    viewModel.rtcSyncState.collect { rtcState ->
                        binding.tvRtcStatus.text = "校時狀態：${rtcState.message}"
                    }
                }
                launch {
                    viewModel.storageEstimate.collect { est ->
                        binding.tvSdStatusDesc.text = est.statusText
                    }
                }
                launch {
                    viewModel.targetIdentity.collect { identity ->
                        val isSwitching = identity.state == com.aladdin.dashcam.network.TargetState.CONNECTING
                        binding.tvDeviceHost.text = if (isSwitching) {
                            "連線目標切換中... (${identity.requestedHost})"
                        } else {
                            "連線目標：${identity.requestedHost} (點擊可切換 Host)"
                        }
                    }
                }
                launch {
                    viewModel.hostSwitchEvent.collect { event ->
                        when (event) {
                            is HostSwitchEvent.Success -> {
                                Toast.makeText(requireContext(), "已切換連線目標至 ${event.host} (epoch: ${event.epoch})", Toast.LENGTH_SHORT).show()
                            }
                            is HostSwitchEvent.Failure -> {
                                Toast.makeText(requireContext(), "切換失敗: ${event.error}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        // Toggle diagnostics collapsible section (UX-05)
        binding.btnToggleDiagnostics.setOnClickListener {
            val isCurrentlyVisible = binding.layoutDiagnosticsContent.visibility == View.VISIBLE
            if (isCurrentlyVisible) {
                binding.layoutDiagnosticsContent.visibility = View.GONE
                binding.tvDiagnosticsArrow.text = "點擊展開 ▼"
            } else {
                binding.layoutDiagnosticsContent.visibility = View.VISIBLE
                binding.tvDiagnosticsArrow.text = "點擊收合 ▲"
            }
        }

        binding.tvDeviceHost.setOnClickListener {
            val input = android.widget.EditText(requireContext())
            input.setText(viewModel.deviceHost.value)
            AlertDialog.Builder(requireContext())
                .setTitle("進階診斷：切換連線目標 Host")
                .setMessage("【注意】此為進階通訊診斷功能。\n一般新增或切換設備，請至首頁以 Wi-Fi 自動辨識。\n手動指定 IP 將終止舊 Session，並重新對目標進行協定探測：")
                .setView(input)
                .setPositiveButton("確認切換") { _, _ ->
                    val newHost = input.text.toString().trim()
                    if (newHost.isNotEmpty()) {
                        viewModel.setDeviceHost(newHost)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // Photo Resolution dialog
        binding.rowCaptureResolution.setOnClickListener {
            val options = com.aladdin.dashcam.model.DashCamSettingMappings.CAPTURE_RESOLUTIONS
            val itemNames = options.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("圖片解析度")
                .setItems(itemNames) { _, which ->
                    val selected = options[which]
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.setCaptureResolution(selected.rawValue, selected.displayName)
                    }
                }
                .show()
        }

        // Photo Quality dialog
        binding.rowCaptureQuality.setOnClickListener {
            val options = com.aladdin.dashcam.model.DashCamSettingMappings.CAPTURE_QUALITIES
            val itemNames = options.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("圖片畫質")
                .setItems(itemNames) { _, which ->
                    val selected = options[which]
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.setCaptureQuality(selected.rawValue, selected.displayName)
                    }
                }
                .show()
        }

        // Video Resolution dialog
        binding.rowResolution.setOnClickListener {
            val options = com.aladdin.dashcam.model.DashCamSettingMappings.VIDEO_RESOLUTIONS
            val itemNames = options.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("影像解析度")
                .setItems(itemNames) { _, which ->
                    val selected = options[which]
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.setResolution(selected.rawValue, selected.displayName)
                    }
                }
                .show()
        }

        // Loop Duration dialog
        binding.rowLoopRecording.setOnClickListener {
            val options = com.aladdin.dashcam.model.DashCamSettingMappings.LOOP_RECORDINGS
            val itemNames = options.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("影片長度")
                .setItems(itemNames) { _, which ->
                    val selected = options[which]
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.setLoopDuration(selected.rawValue, selected.displayName)
                    }
                }
                .show()
        }

        // Exposure SeekBar (-2.0 ~ +2.0, max 12, progress 6 = 0.0)
        binding.seekExposure.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val expVal = (progress - 6) / 3.0f
                    binding.tvCurrentExposure.text = String.format(java.util.Locale.US, "%+.1f", expVal)
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val progress = seekBar?.progress ?: 6
                val expVal = (progress - 6) / 3.0f
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.setExposure(expVal)
                }
            }
        })

        // Watermark dialog
        binding.rowWatermark.setOnClickListener {
            val options = com.aladdin.dashcam.model.DashCamSettingMappings.WATERMARKS
            val itemNames = options.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("時間浮水印")
                .setItems(itemNames) { _, which ->
                    val selected = options[which]
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.setWatermark(selected.rawValue, selected.displayName)
                    }
                }
                .show()
        }

        // Audio Recording toggle
        val audioToggle = View.OnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.toggleAudioRecording()
            }
        }
        binding.rowAudioRecord.setOnClickListener(audioToggle)
        binding.switchAudioRecord.setOnClickListener(audioToggle)

        // Wi-Fi Password guidance dialog
        binding.rowWifiPassword.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("記錄器 Wi-Fi 密碼")
                .setMessage("預設密碼為 12345678。\n如需修改，完成後請至系統 [Wi-Fi] 設定，重新輸入新密碼以連線。")
                .setPositiveButton("確定", null)
                .show()
        }

        // Manual RTC Sync
        binding.btnSyncRtc.setOnClickListener {
            viewModel.syncRtcManual()
            Toast.makeText(requireContext(), "已發送校時命令", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
