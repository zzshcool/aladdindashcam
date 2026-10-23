package com.aladdin.dashcam.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.aladdin.dashcam.R
import com.aladdin.dashcam.databinding.FragmentDownloadsBinding
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch

class DownloadsFragment : Fragment() {
    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashCamViewModel by activityViewModels()
    private lateinit var adapter: DownloadTaskAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = DownloadTaskAdapter { task ->
            when (task.status) {
                DownloadStatus.COMPLETED -> {
                    viewModel.requestOpenMedia(task.mediaItem)
                    activity?.findViewById<BottomNavigationView>(R.id.bottom_nav)?.selectedItemId = R.id.nav_album
                }
                DownloadStatus.DOWNLOADING -> {
                    viewModel.cancelDownload(task)
                    Toast.makeText(requireContext(), getString(R.string.downloads_download_cancelled), Toast.LENGTH_SHORT).show()
                }
                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                    viewModel.retryDownload(task)
                    Toast.makeText(requireContext(), getString(R.string.downloads_downloading_again), Toast.LENGTH_SHORT).show()
                }
                DownloadStatus.PENDING -> {
                    // Waiting in queue
                }
            }
        }
        binding.rvDownloads.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDownloads.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnGoToAlbum.setOnClickListener {
            activity?.findViewById<BottomNavigationView>(R.id.bottom_nav)?.selectedItemId = R.id.nav_album
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.downloadTasks.collect { tasks ->
                    val activeCount = tasks.count {
                        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING
                    }
                    binding.tvActiveTaskCount.text = getString(R.string.downloads_s_active, activeCount)

                    if (tasks.isEmpty()) {
                        binding.layoutEmptyDownloads.visibility = View.VISIBLE
                        binding.rvDownloads.visibility = View.GONE
                    } else {
                        binding.layoutEmptyDownloads.visibility = View.GONE
                        binding.rvDownloads.visibility = View.VISIBLE
                        adapter.submitList(tasks)
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
