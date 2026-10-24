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
    private val viewModel: DashCamViewModel by lazy { (requireActivity().application as com.aladdin.dashcam.AladdinApp).recorder }

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
        // D07: Explicit local album routing
        binding.btnHomeRides.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), RideHistoryActivity::class.java))
        }
        binding.btnHomeLocalAlbum.setOnClickListener {
            (activity as? MainActivity)?.openLocalAlbum()
        }
        binding.btnHomeDownloads.setOnClickListener {
            (activity as? MainActivity)?.openDownloads()
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
        // Observe Registered Devices & Real Connection Status (D08: Decoupled from stream player)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.registeredDevices,
                    viewModel.connectionStatus
                ) { devices, status ->
                    val selected = devices.firstOrNull { it.isSelected } ?: devices.firstOrNull()
                    Pair(selected, status)
                }.collect { (device, status) ->
                    val isConnected = status == ConnectionStatus.CONNECTED && device != null

                    if (device != null) {
                        binding.layoutEmptyGuidance.visibility = View.GONE
                        binding.cardHomeDevice.visibility = View.VISIBLE
                        binding.tvHomeDevice.text = device.name

                        // Connection status semantics (D08: Truthful device status, no misleading stream loading)
                        binding.tvHomeConnection.text = when {
                            isConnected -> getString(R.string.home_connected_recorder_ready)
                            status == ConnectionStatus.CONNECTING -> getString(R.string.device_status_connecting)
                            status == ConnectionStatus.STREAM_INTERRUPTED -> getString(R.string.device_status_stream_interrupted)
                            else -> getString(R.string.home_offline_check_recorder_wi_fi)
                        }
                        binding.tvHomeConnection.setTextColor(
                            ContextCompat.getColor(
                                requireContext(),
                                if (isConnected) R.color.status_green else R.color.text_secondary
                            )
                        )

                        // Card outline focus highlight
                        binding.cardHomeDevice.strokeColor = ContextCompat.getColor(
                            requireContext(),
                            if (isConnected) R.color.card_stroke_focused else R.color.border_subtle
                        )

                        // Main Action CTA
                        binding.btnHomeConnect.text = if (isConnected) {
                            getString(R.string.home_live_short)
                        } else {
                            getString(R.string.home_connect_short)
                        }
                    } else {
                        // Empty device state: show guidance
                        binding.layoutEmptyGuidance.visibility = View.VISIBLE
                        binding.cardHomeDevice.visibility = View.GONE
                    }
                }
            }
        }

        // Observe Telemetry data for selected device (D09: Truthful Battery & SD Capacity)
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

                        // Battery Level & Charging Icon
                        val batteryText = if (devStatus.isCharging) {
                            "${getString(R.string.battery_charging_prefix)}${devStatus.batteryLevel}/4"
                        } else {
                            getString(R.string.home_battery_s, devStatus.batteryLevel)
                        }
                        binding.tvHomeBattery.text = batteryText
                        val batteryDrawable = if (devStatus.isCharging) {
                            when (devStatus.batteryLevel) {
                                4 -> R.drawable.ic_battery_charging_4
                                3 -> R.drawable.ic_battery_charging_3
                                2 -> R.drawable.ic_battery_charging_2
                                1 -> R.drawable.ic_battery_charging_1
                                else -> R.drawable.ic_battery_charging_0
                            }
                        } else {
                            when (devStatus.batteryLevel) {
                                4 -> R.drawable.ic_battery_4
                                3 -> R.drawable.ic_battery_3
                                2 -> R.drawable.ic_battery_2
                                1 -> R.drawable.ic_battery_1
                                else -> R.drawable.ic_battery_0
                            }
                        }
                        binding.ivTelemetryBattery.setImageResource(batteryDrawable)
                        binding.tvHomeBattery.contentDescription = if (devStatus.isCharging) {
                            getString(R.string.home_s_s_bars, getString(R.string.battery_cd_charging_prefix), devStatus.batteryLevel)
                        } else {
                            getString(R.string.home_battery_s_bars, devStatus.batteryLevel)
                        }

                        // SD Storage & Remaining Recording Duration
                        val mins = storageEst.minutesUntilOverwrite
                        val capacity = if (storageEst.freeBytes >= 0 && storageEst.totalBytes > 0) {
                            val freeGb = storageEst.freeBytes / (1024.0 * 1024.0 * 1024.0)
                            val totalGb = storageEst.totalBytes / (1024.0 * 1024.0 * 1024.0)
                            String.format(java.util.Locale.US, getString(R.string.home_free_f_f_gb), freeGb, totalGb)
                        } else {
                            storageEst.statusText
                        }
                        val estimate = if (mins >= 0) "\n${getString(R.string.sd_remaining_format, mins.toInt())}" else ""
                        binding.tvHomeSd.text = "SD：$capacity$estimate"
                    } else {
                        // When offline, show graceful offline placeholder rather than blank
                        binding.layoutDeviceTelemetry.visibility = View.VISIBLE
                        binding.tvHomeBattery.text = getString(R.string.home_battery_unknown)
                        binding.ivTelemetryBattery.setImageResource(R.drawable.ic_battery_unknown)
                        binding.tvHomeSd.text = getString(R.string.home_sd_offline)
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
