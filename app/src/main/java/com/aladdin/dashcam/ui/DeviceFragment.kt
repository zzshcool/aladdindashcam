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
import com.aladdin.dashcam.R
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.launch

class DeviceFragment : Fragment() {
    private var _binding: FragmentDeviceBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashCamViewModel by activityViewModels()
    private var formatting = false
    private var poweringOff = false

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
        setupAppearance()
        observeSettings()
    }

    private fun setupAppearance() {
        val tags = arrayOf("", "zh-TW", "zh-CN", "en")
        val languages = arrayOf(getString(R.string.appearance_system), getString(R.string.language_traditional_chinese), getString(R.string.language_simplified_chinese), "English")
        val selectedLocale = AppCompatDelegate.getApplicationLocales().get(0)
        val locale = when {
            selectedLocale == null -> ""
            selectedLocale.language == "en" -> "en"
            selectedLocale.country == "TW" || selectedLocale.script == "Hant" -> "zh-TW"
            selectedLocale.language == "zh" -> "zh-CN"
            else -> ""
        }
        binding.tvAppLanguage.text = languages[tags.indexOf(locale).coerceAtLeast(0)]
        binding.rowAppLanguage.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.appearance_language)
                .setSingleChoiceItems(languages, tags.indexOf(locale)) { dialog, selected ->
                    dialog.dismiss()
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[selected]))
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        val modes = intArrayOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)
        val themes = arrayOf(getString(R.string.appearance_system), getString(R.string.appearance_light),
            getString(R.string.appearance_dark))
        val selected = modes.indexOf(AppAppearance.nightMode(requireContext())).coerceAtLeast(0)
        binding.tvAppTheme.text = themes[selected]
        binding.rowAppTheme.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.appearance_theme)
                .setSingleChoiceItems(themes, selected) { dialog, index ->
                    dialog.dismiss()
                    AppAppearance.setNightMode(requireContext(), modes[index])
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isFormattingStorage.collect { busy ->
                        formatting = busy
                        binding.btnFormatSd.isEnabled = !busy
                        binding.btnFormatSd.text = if (busy) getString(R.string.device_formatting_keep_power_connected) else getString(R.string.device_format_recorder_sd_card)
                    }
                }
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
                    viewModel.exposureProgress.collect { progress ->
                        binding.seekExposure.progress = progress
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
                    }
                }
                launch {
                    viewModel.deviceStatus.collect { status ->
                        binding.switchAudioRecord.isChecked = status.audioEnabled
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
                        val message = getString(when {
                            rtcState.lastAttemptTime <= 0L -> R.string.rtc_not_synced
                            !rtcState.isSuccess -> R.string.rtc_failed
                            rtcState.isAuto -> R.string.recorder_time_synced_automatically
                            else -> R.string.recorder_time_synced
                        })
                        binding.tvRtcStatus.text = getString(R.string.device_time_sync_s, message)
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
                            getString(R.string.device_switching_recorder_s, identity.requestedHost)
                        } else {
                            getString(R.string.device_target_s_tap_to_change_host, identity.requestedHost)
                        }
                    }
                }
                launch {
                    viewModel.hostSwitchEvent.collect { event ->
                        when (event) {
                            is HostSwitchEvent.Success -> {
                                Toast.makeText(requireContext(), getString(R.string.device_connected_to_s_epoch_s, event.host, event.epoch), Toast.LENGTH_SHORT).show()
                            }
                            is HostSwitchEvent.Failure -> {
                                Toast.makeText(requireContext(), getString(R.string.device_unable_to_switch_s, event.error), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnPowerOff.setOnClickListener {
            if (poweringOff) return@setOnClickListener
            if (viewModel.connectionStatus.value != ConnectionStatus.CONNECTED) {
                Toast.makeText(requireContext(), getString(R.string.device_connect_to_the_recorder_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val epoch = viewModel.targetIdentity.value.epoch
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.device_power_off_recorder_037))
                .setMessage(getString(R.string.device_recording_will_stop_and_wi_fi))
                .setNegativeButton(getString(R.string.album_cancel), null)
                .setPositiveButton(getString(R.string.device_power_off)) { _, _ ->
                    poweringOff = true
                    binding.btnPowerOff.isEnabled = false
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = viewModel.powerOffRecorder(epoch)
                        if (_binding == null) return@launch
                        poweringOff = false
                        binding.btnPowerOff.isEnabled = true
                        AlertDialog.Builder(requireContext())
                            .setTitle(if (result.isSuccess) getString(R.string.device_power_off_command_accepted) else getString(R.string.device_power_off_state_not_confirmed))
                            .setMessage(if (result.isSuccess) getString(R.string.device_the_recorder_accepted_the_power_off) else
                                getString(R.string.device_the_command_may_have_been_sent, result.exceptionOrNull()?.message.orEmpty()))
                            .setPositiveButton(getString(R.string.device_ok), null).show()
                    }
                }.show()
        }
        binding.btnFormatSd.setOnClickListener {
            if (formatting) return@setOnClickListener
            if (viewModel.connectionStatus.value != ConnectionStatus.CONNECTED) {
                Toast.makeText(requireContext(), getString(R.string.device_connect_to_the_recorder_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val epoch = viewModel.targetIdentity.value.epoch
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.device_format_recorder_sd_card_045))
                .setMessage(getString(R.string.device_all_videos_photos_and_protected_files))
                .setNegativeButton(getString(R.string.album_cancel), null)
                .setPositiveButton(getString(R.string.device_continue)) { _, _ ->
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.device_final_confirmation_erase_recorder_card))
                        .setMessage(getString(R.string.device_target_s_nrecording_pauses_during_formatting, viewModel.deviceHost.value))
                        .setNegativeButton(getString(R.string.album_cancel), null)
                        .setPositiveButton(getString(R.string.device_format)) { _, _ ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                val result = viewModel.formatRecorderStorage(epoch)
                                if (_binding == null) return@launch
                                AlertDialog.Builder(requireContext())
                                    .setTitle(if (result.isSuccess) getString(R.string.device_formatting_complete) else getString(R.string.device_formatting_not_confirmed))
                                    .setMessage(if (result.isSuccess) getString(R.string.device_the_card_was_read_back_and) else result.exceptionOrNull()?.message ?: getString(R.string.device_check_the_recorder))
                                    .setPositiveButton(getString(R.string.device_ok), null).show()
                            }
                        }.show()
                }.show()
        }
        // Toggle diagnostics collapsible section (UX-05)
        binding.btnToggleDiagnostics.setOnClickListener {
            val isCurrentlyVisible = binding.layoutDiagnosticsContent.visibility == View.VISIBLE
            if (isCurrentlyVisible) {
                binding.layoutDiagnosticsContent.visibility = View.GONE
                binding.tvDiagnosticsArrow.text = getString(R.string.device_expand)
            } else {
                binding.layoutDiagnosticsContent.visibility = View.VISIBLE
                binding.tvDiagnosticsArrow.text = getString(R.string.device_collapse)
            }
        }

        binding.tvDeviceHost.setOnClickListener {
            val input = android.widget.EditText(requireContext())
            input.setText(viewModel.deviceHost.value)
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.device_advanced_diagnostics_change_host))
                .setMessage(getString(R.string.device_advanced_connection_diagnostics_nnormally_add_or))
                .setView(input)
                .setPositiveButton(getString(R.string.device_switch)) { _, _ ->
                    val newHost = input.text.toString().trim()
                    if (newHost.isNotEmpty()) {
                        viewModel.setDeviceHost(newHost)
                    }
                }
                .setNegativeButton(getString(R.string.album_cancel), null)
                .show()
        }

        // Photo Resolution dialog
        binding.rowCaptureResolution.setOnClickListener {
            val options = com.aladdin.dashcam.model.DashCamSettingMappings.CAPTURE_RESOLUTIONS
            val itemNames = options.map { it.localizedName(requireContext()) }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.device_photo_resolution))
                .setItems(itemNames) { _, which ->
                    val selected = options[which]
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.setCaptureResolution(selected.rawValue, selected.localizedName(requireContext()))
                    }
                }
                .show()
        }

        // Photo Quality dialog
        binding.rowCaptureQuality.setOnClickListener {
            val options = com.aladdin.dashcam.model.DashCamSettingMappings.CAPTURE_QUALITIES
            val itemNames = options.map { it.localizedName(requireContext()) }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.device_photo_quality))
                .setItems(itemNames) { _, which ->
                    val selected = options[which]
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.setCaptureQuality(selected.rawValue, selected.localizedName(requireContext()))
                    }
                }
                .show()
        }

        // Video Resolution dialog
        binding.rowResolution.setOnClickListener {
            val options = com.aladdin.dashcam.model.DashCamSettingMappings.VIDEO_RESOLUTIONS
            val itemNames = options.map { it.localizedName(requireContext()) }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.device_video_resolution))
                .setItems(itemNames) { _, which ->
                    val selected = options[which]
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.setResolution(selected.rawValue, selected.localizedName(requireContext()))
                    }
                }
                .show()
        }

        // Loop Duration dialog
        binding.rowLoopRecording.setOnClickListener {
            val options = com.aladdin.dashcam.model.DashCamSettingMappings.LOOP_RECORDINGS
            val itemNames = options.map { it.localizedName(requireContext()) }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.device_clip_duration))
                .setItems(itemNames) { _, which ->
                    val selected = options[which]
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.setLoopDuration(selected.rawValue, selected.localizedName(requireContext()))
                    }
                }
                .show()
        }

        // Exposure SeekBar (-2.0 ~ +2.0, max 12, progress 6 = 0.0)
        binding.seekExposure.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val rawVal = com.aladdin.dashcam.model.DashCamSettingMappings.exposureProgressToRaw(progress)
                    binding.tvCurrentExposure.text = com.aladdin.dashcam.model.DashCamSettingMappings.getExposureName(rawVal)
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val progress = seekBar?.progress ?: 6
                val rawVal = com.aladdin.dashcam.model.DashCamSettingMappings.exposureProgressToRaw(progress)
                val displayName = com.aladdin.dashcam.model.DashCamSettingMappings.getExposureName(rawVal)
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.setExposureByRaw(rawVal, displayName, progress)
                }
            }
        })

        // Watermark dialog
        binding.rowWatermark.setOnClickListener {
            val options = com.aladdin.dashcam.model.DashCamSettingMappings.WATERMARKS
            val itemNames = options.map { it.localizedName(requireContext()) }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.device_time_stamp))
                .setItems(itemNames) { _, which ->
                    val selected = options[which]
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.setWatermark(selected.rawValue, selected.localizedName(requireContext()))
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
                .setTitle(getString(R.string.device_recorder_wi_fi_password_059))
                .setMessage(getString(R.string.device_the_default_password_is_nafter_changing))
                .setPositiveButton(getString(R.string.device_ok_061), null)
                .show()
        }

        // Manual RTC Sync
        binding.btnSyncRtc.setOnClickListener {
            viewModel.syncRtcManual()
            Toast.makeText(requireContext(), getString(R.string.device_time_sync_command_sent), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
