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
            if (item.isDownloaded) {
                Toast.makeText(requireContext(), "本機檔案已就緒: ${item.fileName}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "開始從記錄器下載: ${item.fileName}", Toast.LENGTH_SHORT).show()
                viewLifecycleOwner.lifecycleScope.launch {
                    val res = viewModel.downloadDeviceMedia(item)
                    res.onSuccess { file ->
                        Toast.makeText(requireContext(), "下載完成，已保存至本機: ${file.name}", Toast.LENGTH_LONG).show()
                        applyFilters()
                    }.onFailure { err ->
                        Toast.makeText(requireContext(), "下載失敗: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
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
            if (!isDeviceSource) {
                binding.toggleAlbumSource.check(R.id.btn_source_device)
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val res = viewModel.fetchRemoteFileList()
                res.onSuccess { list ->
                    Toast.makeText(requireContext(), "成功取得記錄器清單: ${list.size} 筆", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(requireContext(), "取得影片清單失敗: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
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
            if (isDeviceSource) {
                if (viewModel.albumQueryState.value is AlbumQueryState.Success) {
                    binding.tvAlbumEmpty.text = "記錄器記憶卡內無檔案"
                    binding.btnRefreshAlbum.text = "重新整理"
                } else {
                    binding.tvAlbumEmpty.text = "已連接記錄器，尚未讀取影片清單"
                    binding.btnRefreshAlbum.text = "讀取記錄器影片清單"
                }
            } else {
                binding.tvAlbumEmpty.text = "本機尚未下載任何影片"
                binding.btnRefreshAlbum.text = "切換至記錄器相簿"
            }
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
                    viewModel.albumQueryState.collect { state ->
                        when (state) {
                            is AlbumQueryState.Idle -> {
                                binding.layoutLoadingState.visibility = View.GONE
                                if (isDeviceSource && viewModel.deviceMediaList.value.isEmpty()) {
                                    binding.layoutEmptyState.visibility = View.VISIBLE
                                    binding.tvAlbumEmpty.text = "已連接記錄器，尚未讀取影片清單"
                                    binding.btnRefreshAlbum.text = "讀取記錄器影片清單"
                                    binding.rvAlbum.visibility = View.GONE
                                }
                            }
                            is AlbumQueryState.Loading -> {
                                binding.layoutLoadingState.visibility = View.VISIBLE
                                binding.layoutEmptyState.visibility = View.GONE
                                binding.rvAlbum.visibility = View.GONE
                            }
                            is AlbumQueryState.Success -> {
                                binding.layoutLoadingState.visibility = View.GONE
                                applyFilters()
                            }
                            is AlbumQueryState.Error -> {
                                binding.layoutLoadingState.visibility = View.GONE
                                binding.layoutEmptyState.visibility = View.VISIBLE
                                binding.tvAlbumEmpty.text = "讀取記錄器清單失敗：${state.message}"
                                binding.btnRefreshAlbum.text = "重試讀取"
                                binding.rvAlbum.visibility = View.GONE
                            }
                        }
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
