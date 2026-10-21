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
                    viewModel.targetIdentity.collect { identity ->
                        val isSwitching = identity.state == com.aladdin.dashcam.network.TargetState.CONNECTING
                        binding.tvDeviceHost.text = if (isSwitching) {
                            "連線目標切換中... (${identity.requestedHost})"
                        } else {
                            "連線目標：${identity.requestedHost} (點擊可切換)"
                        }
                        binding.rowResolution.isEnabled = !isSwitching
                        binding.rowLoopRecording.isEnabled = !isSwitching
                        binding.btnSyncRtc.isEnabled = !isSwitching
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
        binding.tvDeviceHost.setOnClickListener {
            val input = android.widget.EditText(requireContext())
            input.setText(viewModel.deviceHost.value)
            AlertDialog.Builder(requireContext())
                .setTitle("切換連線目標 Host")
                .setMessage("請輸入記錄器 IP 位址（預設 192.168.25.1）：")
                .setView(input)
                .setPositiveButton("確定") { _, _ ->
                    val newHost = input.text.toString().trim()
                    if (newHost.isNotEmpty()) {
                        viewModel.setDeviceHost(newHost)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.btnSyncRtc.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                Toast.makeText(requireContext(), "正在同步手機時間至記錄器...", Toast.LENGTH_SHORT).show()
                val res = viewModel.syncRtc()
                res.onSuccess {
                    Toast.makeText(requireContext(), "校時命令已接受，時間未驗證", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(requireContext(), "時間同步失敗：${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.rowResolution.setOnClickListener {
            val resolutions = arrayOf(
                "2K 2560×1440 30fps",
                "1080FHD 1920×1080 30fps",
                "1080P 1440×1080 30fps",
                "720P 1280×720 30fps"
            )
            AlertDialog.Builder(requireContext())
                .setTitle("選擇錄影解析度")
                .setItems(resolutions) { _, which ->
                    binding.tvCurrentResolution.text = "設定中..."
                    viewLifecycleOwner.lifecycleScope.launch {
                        val res = viewModel.setResolution(which, resolutions[which])
                        res.onSuccess {
                            Toast.makeText(requireContext(), "指令已送出並獲 ACK，待回讀生效: ${resolutions[which]}", Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            Toast.makeText(requireContext(), "硬體設定失敗: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .show()
        }

        binding.rowLoopRecording.setOnClickListener {
            val loops = arrayOf("1分鐘", "2分鐘", "3分鐘", "5分鐘")
            val minutes = intArrayOf(1, 2, 3, 5)
            AlertDialog.Builder(requireContext())
                .setTitle("選擇循環片長")
                .setItems(loops) { _, which ->
                    binding.tvCurrentLoop.text = "設定中..."
                    viewLifecycleOwner.lifecycleScope.launch {
                        val res = viewModel.setLoopDuration(minutes[which], loops[which])
                        res.onSuccess {
                            Toast.makeText(requireContext(), "指令已送出並獲 ACK，待回讀生效: ${loops[which]}", Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            Toast.makeText(requireContext(), "硬體設定失敗: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .show()
        }

        binding.btnFormatSd.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("格式化記憶卡")
                .setMessage("警告：格式化將永久抹除記憶卡內所有錄影與照片！依安全防護規範，此高風險操作需要進一步授權。")
                .setPositiveButton("確認") { _, _ ->
                    Toast.makeText(requireContext(), "安全限制：已阻擋硬體格式化指令發送", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.btnFactoryReset.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("恢復原廠設定")
                .setMessage("警告：此操作將重置記錄器所有設定！依安全防護規範，此高風險操作需要進一步授權。")
                .setPositiveButton("確認") { _, _ ->
                    Toast.makeText(requireContext(), "安全限制：已阻擋恢復原廠指令發送", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
