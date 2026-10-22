package com.aladdin.dashcam.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aladdin.dashcam.R
import com.aladdin.dashcam.databinding.FragmentHomeBinding
import com.aladdin.dashcam.model.ConnectionStatus
import com.aladdin.dashcam.model.DashCamDeviceStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashCamViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        setupClickListeners()
        observeDashboardState()
    }

    private fun setupClickListeners() {
        binding.btnManageRecorders.setOnClickListener {
            (activity as? MainActivity)?.showMyDashCamDialog()
        }
        binding.btnAddRecorder.setOnClickListener {
            (activity as? MainActivity)?.promptAddDevice()
        }
        binding.btnHomeMap.setOnClickListener {
            (activity as? MainActivity)?.openLive(R.id.btn_mode_map)
        }
        binding.btnHomeLocalAlbum.setOnClickListener {
            (activity as? MainActivity)?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
                ?.selectedItemId = R.id.nav_album
        }
        binding.btnHomeDownloads.setOnClickListener {
            (activity as? MainActivity)?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
                ?.selectedItemId = R.id.nav_downloads
        }
        binding.btnHomeConnect.setOnClickListener {
            if (viewModel.connectionStatus.value == ConnectionStatus.CONNECTED) {
                (activity as? MainActivity)?.openLive(R.id.btn_mode_normal)
            } else {
                (activity as? MainActivity)?.promptAddDevice()
            }
        }
    }

    private fun observeDashboardState() {
        // Observe Devices, Connection Status, and Device Telemetry
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.registeredDevices,
                    viewModel.connectionStatus,
                    viewModel.deviceStatus
                ) { devices: List<DashCamDeviceRecord>, status: ConnectionStatus, devStatus: DashCamDeviceStatus ->
                    val selectedDevice = devices.firstOrNull { it.isSelected } ?: devices.firstOrNull()
                    Triple(selectedDevice, status, devStatus)
                }.collect { (device, status, devStatus) ->
                    val isConnected = status == ConnectionStatus.CONNECTED && device != null

                    // Device name display
                    binding.tvHomeDevice.text = device?.name ?: getString(R.string.home_device_none)

                    // Connection status semantics (UX01: Never overclaim stream readiness without first frame / rtsp confirmation)
                    binding.tvHomeConnection.text = when {
                        isConnected -> getString(R.string.home_status_connected)
                        status == ConnectionStatus.CONNECTING -> getString(R.string.device_status_connecting)
                        status == ConnectionStatus.STREAM_INTERRUPTED -> getString(R.string.device_status_stream_interrupted)
                        device != null -> getString(R.string.home_status_disconnected)
                        else -> getString(R.string.home_device_none_desc)
                    }

                    // Stream badge on card
                    if (isConnected) {
                        binding.tvHomeStreamBadge.visibility = View.VISIBLE
                        if (devStatus.rtspFlag) {
                            binding.tvHomeStreamBadge.text = getString(R.string.home_status_stream_ready)
                            binding.tvHomeStreamBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_teal))
                        } else {
                            binding.tvHomeStreamBadge.text = getString(R.string.home_status_stream_loading)
                            binding.tvHomeStreamBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                        }
                    } else {
                        binding.tvHomeStreamBadge.visibility = View.GONE
                    }

                    // Main Action CTA
                    binding.btnHomeConnect.text = when {
                        isConnected -> getString(R.string.home_action_open_live)
                        device != null -> getString(R.string.home_action_connect)
                        else -> getString(R.string.home_action_add)
                    }

                    // Hero layout responsiveness (UX02: Prioritize selected device card when devices exist)
                    if (device != null) {
                        binding.layoutHeroBanner.visibility = View.GONE
                        binding.cardHomeDevice.strokeColor = if (isConnected) {
                            ContextCompat.getColor(requireContext(), R.color.accent_teal)
                        } else {
                            ContextCompat.getColor(requireContext(), R.color.border_subtle)
                        }
                    } else {
                        binding.layoutHeroBanner.visibility = View.VISIBLE
                        binding.cardHomeDevice.strokeColor = ContextCompat.getColor(requireContext(), R.color.border_subtle)
                    }
                }
            }
        }

        // Observe telemetry data for selected device (Battery & SD, Review 3.3.2)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.deviceStatus,
                    viewModel.storageEstimate,
                    viewModel.connectionStatus
                ) { devStatus, storageEst, connStatus ->
                    Triple(devStatus, storageEst, connStatus)
                }.collect { (devStatus, storageEst, connStatus) ->
                    val isConnected = connStatus == ConnectionStatus.CONNECTED
                    if (isConnected) {
                        binding.layoutDeviceTelemetry.visibility = View.VISIBLE
                        // Battery semantics with accessibility
                        val batteryText = if (devStatus.isCharging) {
                            "${getString(R.string.battery_charging_prefix)}${devStatus.batteryLevel}/4"
                        } else {
                            "電量：${devStatus.batteryLevel}/4"
                        }
                        binding.tvHomeBattery.text = batteryText
                        binding.tvHomeBattery.contentDescription = if (devStatus.isCharging) {
                            "${getString(R.string.battery_cd_charging_prefix)}${devStatus.batteryLevel}格"
                        } else {
                            "電量${devStatus.batteryLevel}格"
                        }

                        // SD status / remaining recording time
                        val mins = storageEst.minutesUntilOverwrite
                        binding.tvHomeSd.text = if (mins > 0) {
                            getString(R.string.sd_remaining_format, mins.toInt())
                        } else {
                            storageEst.statusText
                        }
                    } else {
                        binding.layoutDeviceTelemetry.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
