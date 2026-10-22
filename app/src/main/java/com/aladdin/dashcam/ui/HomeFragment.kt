package com.aladdin.dashcam.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aladdin.dashcam.R
import com.aladdin.dashcam.databinding.FragmentHomeBinding
import com.aladdin.dashcam.model.ConnectionStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val viewModel: DashCamViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val binding = FragmentHomeBinding.inflate(inflater, container, false)
        _binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val binding = _binding ?: return
        binding.btnManageRecorders.setOnClickListener { (activity as? MainActivity)?.showMyDashCamDialog() }
        binding.btnAddRecorder.setOnClickListener { (activity as? MainActivity)?.promptAddDevice() }
        binding.btnHomeMap.setOnClickListener { (activity as? MainActivity)?.openLive(R.id.btn_mode_map) }
        binding.btnHomeConnect.setOnClickListener {
            if (viewModel.connectionStatus.value == ConnectionStatus.CONNECTED) {
                (activity as? MainActivity)?.openLive(R.id.btn_mode_normal)
            } else (activity as? MainActivity)?.promptAddDevice()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.registeredDevices, viewModel.connectionStatus) { devices, status ->
                    devices.firstOrNull { it.isSelected } to status
                }.collect { (device, status) ->
                    val connected = status == ConnectionStatus.CONNECTED && device != null
                    binding.tvHomeDevice.text = device?.name ?: "尚未新增記錄器"
                    binding.tvHomeConnection.text = when {
                        connected -> "● 已連線 · 即時影像準備就緒"
                        device != null -> "○ 未連線 · 請開啟記錄器 Wi-Fi"
                        else -> "新增後即可查看設備與連線狀態"
                    }
                    binding.btnHomeConnect.text = when {
                        connected -> "開啟即時影像"
                        device != null -> "連接記錄器"
                        else -> "新增記錄器"
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
