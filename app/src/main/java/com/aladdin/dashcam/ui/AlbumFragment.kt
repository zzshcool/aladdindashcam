package com.aladdin.dashcam.ui

import android.os.Bundle
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
import androidx.recyclerview.widget.GridLayoutManager
import com.aladdin.dashcam.R
import com.aladdin.dashcam.databinding.FragmentAlbumBinding
import com.aladdin.dashcam.model.MediaItem
import kotlinx.coroutines.launch

class AlbumFragment : Fragment() {
    private var _binding: FragmentAlbumBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashCamViewModel by activityViewModels()
    private lateinit var adapter: MediaAdapter

    private var isDeviceSource = true
    private var currentFilter = FilterCategory.ALL

    enum class FilterCategory { ALL, VIDEO, PHOTO, EMERGENCY }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlbumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = MediaAdapter { item ->
            if (viewModel.isDemoMode.value) {
                Toast.makeText(requireContext(), "展示模式：範例檔案 ${item.fileName}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "實機檔案傳輸佇列實作中 (M4進行中): ${item.fileName}", Toast.LENGTH_SHORT).show()
            }
        }
        binding.rvAlbum.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvAlbum.adapter = adapter
    }

    private fun setupListeners() {
        binding.toggleAlbumSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isDeviceSource = (checkedId == R.id.btn_source_device)
                applyFilters()
            }
        }

        binding.filterAll.setOnClickListener { selectFilter(FilterCategory.ALL) }
        binding.filterVideo.setOnClickListener { selectFilter(FilterCategory.VIDEO) }
        binding.filterPhoto.setOnClickListener { selectFilter(FilterCategory.PHOTO) }
        binding.filterEmergency.setOnClickListener { selectFilter(FilterCategory.EMERGENCY) }

        binding.btnRefreshAlbum.setOnClickListener {
            binding.layoutLoadingState.visibility = View.VISIBLE
            binding.layoutEmptyState.visibility = View.GONE
            viewLifecycleOwner.lifecycleScope.launch {
                val res = viewModel.fetchRemoteFileCount()
                binding.layoutLoadingState.visibility = View.GONE
                res.onSuccess { count ->
                    Toast.makeText(requireContext(), "記錄器回報遠端檔案總數: $count 筆", Toast.LENGTH_LONG).show()
                    applyFilters()
                }.onFailure { err ->
                    Toast.makeText(requireContext(), "查詢記錄器檔案失敗: ${err.message}", Toast.LENGTH_LONG).show()
                    applyFilters()
                }
            }
        }

        binding.btnToggleDemoMode.setOnClickListener {
            val nextState = !viewModel.isDemoMode.value
            viewModel.setDemoMode(nextState)
            Toast.makeText(
                requireContext(),
                if (nextState) "已開啟展示範例模式" else "已返回真實模式",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun selectFilter(filter: FilterCategory) {
        currentFilter = filter
        val context = requireContext()
        val defaultColor = ContextCompat.getColor(context, R.color.text_secondary)
        val selectedColor = ContextCompat.getColor(context, R.color.accent_teal)

        binding.filterAll.setTextColor(if (filter == FilterCategory.ALL) selectedColor else defaultColor)
        binding.filterVideo.setTextColor(if (filter == FilterCategory.VIDEO) selectedColor else defaultColor)
        binding.filterPhoto.setTextColor(if (filter == FilterCategory.PHOTO) selectedColor else defaultColor)
        binding.filterEmergency.setTextColor(if (filter == FilterCategory.EMERGENCY) selectedColor else defaultColor)

        applyFilters()
    }

    private fun applyFilters() {
        val rawList = if (isDeviceSource) viewModel.deviceMediaList.value else viewModel.localMediaList.value
        val filtered = rawList.filter { item ->
            when (currentFilter) {
                FilterCategory.ALL -> true
                FilterCategory.VIDEO -> item.isVideo
                FilterCategory.PHOTO -> !item.isVideo
                FilterCategory.EMERGENCY -> item.isEmergency
            }
        }

        adapter.submitList(filtered)
        if (filtered.isEmpty()) {
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.rvAlbum.visibility = View.GONE
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.rvAlbum.visibility = View.VISIBLE
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.deviceMediaList.collect {
                        if (isDeviceSource) applyFilters()
                    }
                }
                launch {
                    viewModel.localMediaList.collect {
                        if (!isDeviceSource) applyFilters()
                    }
                }
                launch {
                    viewModel.isDemoMode.collect { isDemo ->
                        binding.tvDemoModeBanner.visibility = if (isDemo) View.VISIBLE else View.GONE
                        binding.btnToggleDemoMode.text = if (isDemo) "退出展示模式（切回實機）" else "切換展示範例模式"
                        applyFilters()
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
