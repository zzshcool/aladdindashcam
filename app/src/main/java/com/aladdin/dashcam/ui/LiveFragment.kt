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
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aladdin.dashcam.R
import com.aladdin.dashcam.databinding.FragmentLiveBinding
import com.aladdin.dashcam.model.ConnectionStatus
import com.aladdin.dashcam.model.DeviceMode
import com.aladdin.dashcam.model.RecordingStatus
import com.aladdin.dashcam.gps.GpsStatus
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class LiveFragment : Fragment() {
    private var _binding: FragmentLiveBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashCamViewModel by lazy { (requireActivity().application as com.aladdin.dashcam.AladdinApp).recorder }
    private var nativePlayer: com.aladdin.dashcam.media.NativeFfmpegPlayer? = null

    private var lastStreamNetwork: android.net.Network? = null
    private var lastStreamHost: String? = null
    private var streamJob: Job? = null

    private var googleMap: GoogleMap? = null
    private var phoneMarker: Marker? = null
    private var trajectoryPolyline: Polyline? = null
    private var isMapFollowEnabled = true
    private var selectedModeId = R.id.btn_mode_normal
    private var mapLoaded = false
    private var mapRequested = false
    private var hasSavedMapCamera = false
    private var savedMapType = GoogleMap.MAP_TYPE_NORMAL
    private var lastMapStatus: GpsStatus? = null
    private var mapLoadJob: Job? = null
    private var takingPhoto = false

    fun selectMode(modeId: Int) {
        selectedModeId = modeId
        if (_binding != null) {
            binding.toggleLiveMode?.check(modeId)
            applyLiveMode()
        }
    }

    private fun applyLiveMode() {
        if (_binding == null) return
        val expanded = (activity as? MainActivity)?.isFullscreen == true || activity?.isInPictureInPictureMode == true
        if (expanded) {
            updatePreviewGeometry(true)
            return
        }
        val mapMode = selectedModeId == R.id.btn_mode_map
        val split = selectedModeId == R.id.btn_mode_split
        val ride = selectedModeId == R.id.btn_mode_ride
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        binding.previewContainer.visibility = if (mapMode) View.GONE else View.VISIBLE
        binding.mapContainer.visibility = if (mapMode || split) View.VISIBLE else View.GONE
        binding.layoutNormalControls.visibility = View.VISIBLE
        binding.statusStrip.visibility = if (mapMode) View.GONE else View.VISIBLE
        binding.layoutRideMode.visibility = if (ride) View.VISIBLE else View.GONE
        val constraints = ConstraintSet().apply { clone(binding.root) }
        val parent = ConstraintSet.PARENT_ID
        fun connect(id: Int, side: Int, target: Int, targetSide: Int) =
            constraints.connect(id, side, target, targetSide)
        val start = ConstraintSet.START
        val end = ConstraintSet.END
        val top = ConstraintSet.TOP
        val bottom = ConstraintSet.BOTTOM
        connect(R.id.layout_live_mode_toggle, start, parent, start)
        connect(R.id.layout_live_mode_toggle, end, if (landscape) R.id.map_split_guideline else parent, if (landscape) start else end)
        connect(R.id.layout_normal_controls, start, if (landscape) R.id.map_split_guideline else parent, start)
        connect(R.id.layout_normal_controls, end, parent, end)
        constraints.clear(R.id.layout_normal_controls, top)
        constraints.clear(R.id.layout_normal_controls, bottom)
        connect(R.id.layout_normal_controls, bottom, parent, bottom)
        constraints.clear(R.id.status_strip, top)
        constraints.clear(R.id.status_strip, bottom)
        connect(R.id.status_strip, bottom, R.id.preview_container, bottom)
        connect(R.id.status_strip, start, R.id.preview_container, start)
        connect(R.id.status_strip, end, R.id.preview_container, end)
        connect(R.id.preview_container, start, parent, start)
        connect(R.id.preview_container, end, if (landscape && (split || ride)) R.id.map_split_guideline else parent,
            if (landscape && (split || ride)) start else end)
        connect(R.id.preview_container, top, R.id.layout_live_mode_toggle, bottom)
        constraints.clear(R.id.preview_container, bottom)
        constraints.constrainHeight(R.id.preview_container, ConstraintSet.MATCH_CONSTRAINT)
        constraints.setDimensionRatio(R.id.preview_container, if (!landscape && (split || ride)) "H,16:9" else null)
        if (landscape || !(split || ride)) {
            connect(R.id.preview_container, bottom, R.id.layout_normal_controls, top)
        }
        for (id in intArrayOf(R.id.map_container, R.id.layout_ride_mode)) {
            connect(id, start, if (landscape && !mapMode) R.id.map_split_guideline else parent, start)
            connect(id, end, parent, end)
            connect(id, top, if (landscape || mapMode) R.id.layout_live_mode_toggle else R.id.preview_container, bottom)
            connect(id, bottom, R.id.layout_normal_controls, top)
        }
        constraints.applyTo(binding.root)
        if (mapMode || split) {
            if (!mapRequested) initMap()
            updateMapLocation(viewModel.gpsStatus.value)
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            viewModel.currentStreamOwner.value != DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK) {
            setupMediaStream(viewModel.networkMonitor.wifiNetwork.value)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding == null) return
        if (activity?.isInPictureInPictureMode == true) updatePipLayout(true)
        else updateFullscreenLayout((activity as? MainActivity)?.isFullscreen == true)
    }

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
        try {
            binding.mapView.onCreate(savedInstanceState)
        } catch (e: Exception) {
            android.util.Log.e("LiveFragment", "Error initializing MapView: ${e.message}")
        }
        initPlayer()
        setupListeners()
        isMapFollowEnabled = savedInstanceState?.getBoolean("map_follow", true) ?: true
        hasSavedMapCamera = savedInstanceState?.getBoolean("map_camera", false) ?: false
        savedMapType = savedInstanceState?.getInt("map_type", GoogleMap.MAP_TYPE_NORMAL) ?: GoogleMap.MAP_TYPE_NORMAL
        selectedModeId = savedInstanceState?.getInt("live_mode", selectedModeId) ?: selectedModeId
        binding.toggleLiveMode?.check(selectedModeId)
        applyLiveMode()
        observeViewModel()
    }

    private fun initMap() {
        try {
            mapRequested = true
            val requestedView = binding.mapView
            requestedView.getMapAsync { map ->
                if (_binding?.mapView !== requestedView) return@getMapAsync
                this.googleMap = map
                map.mapType = savedMapType
                binding.btnMapType.text = if (savedMapType == GoogleMap.MAP_TYPE_HYBRID) getString(R.string.live_standard_map) else getString(R.string.live_satellite)
                try {
                    map.uiSettings.isZoomControlsEnabled = false
                    map.uiSettings.isCompassEnabled = true
                    map.uiSettings.isMyLocationButtonEnabled = false
                    val density = resources.displayMetrics.density
                    val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    map.setPadding(0, ((if (landscape) 8 else 64) * density).toInt(),
                        ((if (landscape) 8 else 64) * density).toInt(), (48 * density).toInt())
                } catch (_: Exception) {}

                val polylineOptions = PolylineOptions()
                    .color(ContextCompat.getColor(requireContext(), R.color.accent_teal))
                    .width(10f)
                    .geodesic(true)
                trajectoryPolyline = map.addPolyline(polylineOptions)
                if (!hasSavedMapCamera) map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(23.7, 121.0), 7f))
                map.setOnMapLoadedCallback {
                    if (_binding != null) {
                        mapLoaded = true
                        binding.tvMapNotice.text = getString(R.string.live_map_loaded_phone_gps)
                        android.util.Log.i("MapValidation", "Map tiles rendered")
                    }
                }
                mapLoadJob?.cancel()
                mapLoadJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(12000)
                    if (!mapLoaded && _binding != null) {
                        binding.tvMapNotice.text = getString(R.string.live_map_unavailable_check_your_internet_connection)
                    }
                }

                map.setOnCameraMoveStartedListener { reason ->
                    if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    isMapFollowEnabled = false
                    binding.fabMapCenter.contentDescription = getString(R.string.live_follow_my_location)
                    }
                }

                updateMapLocation(viewModel.gpsStatus.value)
            }
        } catch (e: Exception) {
            mapRequested = false
            android.util.Log.e("LiveFragment", "getMapAsync exception: ${e.message}")
        }
    }

    private fun initPlayer() {
        nativePlayer = com.aladdin.dashcam.media.NativeFfmpegPlayer(
            glSurfaceView = binding.glPlayerView,
            onFirstFrameRendered = {
                if (_binding != null) {
                    binding.streamOverlay.visibility = View.GONE
                    binding.pbStreamLoading.visibility = View.GONE
                    binding.btnRetryStream.visibility = View.GONE
                    android.util.Log.i("LiveFragment", "FFmpeg first decoded frame drawn")
                }
            },
            onError = { error -> showNativeStreamError(error) },
            onEnded = { showNativeStreamError(getString(R.string.stream_connection_interrupted)) }
        )
        binding.streamOverlay.visibility = View.VISIBLE
        binding.pbStreamLoading.visibility = View.VISIBLE
        binding.btnRetryStream.visibility = View.GONE
        binding.tvStreamMessage.text = getString(R.string.stream_loading)
    }

    private fun showNativeStreamError(message: String) {
        if (_binding == null) return
        lastStreamNetwork = null
        lastStreamHost = null
        binding.streamOverlay.visibility = View.VISIBLE
        binding.pbStreamLoading.visibility = View.GONE
        binding.btnRetryStream.visibility = View.VISIBLE
        binding.tvStreamMessage.text = message
    }
    private fun setupMediaStream(network: android.net.Network?) {
        val native = nativePlayer ?: return
        if (viewModel.deviceStatus.value.mode == DeviceMode.PLAYBACK ||
            viewModel.currentStreamOwner.value == DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK ||
            viewModel.currentStreamOwner.value == DashCamViewModel.MediaStreamOwner.DOWNLOAD) return
        if (network == null || viewModel.targetIdentity.value.state != com.aladdin.dashcam.network.TargetState.READY) {
            streamJob?.cancel()
            streamJob = null
            native.stop()
            lastStreamNetwork = null
            lastStreamHost = null
            showNativeStreamError(getString(R.string.live_recorder_offline_nphone_maps_are_still))
            return
        }

        val host = viewModel.deviceHost.value
        if (selectedModeId == R.id.btn_mode_map && activity?.isInPictureInPictureMode != true &&
            (activity as? MainActivity)?.isFullscreen != true) {
            native.setPaused(true, controlSource = false)
            return
        }
        native.setPaused(false, controlSource = false)
        if (network == lastStreamNetwork && host == lastStreamHost &&
            (streamJob?.isActive == true || native.hasSource)) return
        streamJob?.cancel()
        native.stop()
        lastStreamNetwork = network
        lastStreamHost = host
        binding.streamOverlay.visibility = View.VISIBLE
        binding.pbStreamLoading.visibility = View.VISIBLE
        binding.btnRetryStream.visibility = View.GONE
        binding.tvStreamMessage.text = getString(R.string.stream_loading)

        streamJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val status = viewModel.refreshStatus()
                check(status != null && status.lastUpdatedTimestamp > 0L) { getString(R.string.live_unable_to_read_recorder_status_reconnect) }
                check(status.rtspFlag) { getString(R.string.live_recorder_is_not_in_rtsp_mode) }
                viewModel.restartStreaming().getOrThrow()
                native.start("rtsp://$host:8080/?action=stream", targetNetwork = network)
                if (selectedModeId == R.id.btn_mode_map && activity?.isInPictureInPictureMode != true &&
                    (activity as? MainActivity)?.isFullscreen != true) native.setPaused(true, controlSource = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                native.stop()
                showNativeStreamError(error.message ?: getString(R.string.device_status_stream_interrupted))
            }
        }
    }
    private fun setupListeners() {
        binding.btnToggleRecording.setOnClickListener {
            val isRecording = viewModel.deviceStatus.value.recordingStatus == RecordingStatus.RECORDING
            val targetDesired = !isRecording

            // S5: Direct intent execution without AlertDialog; immediate in-flight feedback
            binding.btnToggleRecording.isEnabled = false
            binding.btnToggleRecording.text = if (isRecording) {
                getString(R.string.btn_stopping_recording)
            } else {
                getString(R.string.btn_starting_recording)
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = viewModel.ensureRecording(targetDesired)
                if (!ok) {
                    Toast.makeText(
                        requireContext(),
                        if (isRecording) getString(R.string.live_recording_did_not_stop_or_the) else getString(R.string.live_recording_did_not_start),
                        Toast.LENGTH_SHORT
                    ).show()
                    updateRecordingButton(viewModel.deviceStatus.value.recordingStatus)
                }
            }
        }

        binding.btnSnapshot.setOnClickListener {
            if (takingPhoto) return@setOnClickListener
            takingPhoto = true
            updateRecordingTimerUi(viewModel.recordingTimerDisplay.value)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val res = viewModel.takeSnapshot()
                    res.onSuccess {
                        Toast.makeText(requireContext(), getString(R.string.live_photo_captured), Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        Toast.makeText(requireContext(), getString(R.string.live_photo_failed_s, err.message), Toast.LENGTH_LONG).show()
                    }
                } finally {
                    takingPhoto = false
                    updateRecordingTimerUi(viewModel.recordingTimerDisplay.value)
                }
            }
        }

        binding.btnRetryStream.setOnClickListener {
            val network = viewModel.wifiNetwork.value
            if (network == null) {
                Toast.makeText(requireContext(), getString(R.string.live_connect_to_recorder_wi_fi_first), Toast.LENGTH_SHORT).show()
                binding.streamOverlay.visibility = View.VISIBLE
                binding.pbStreamLoading.visibility = View.GONE
                binding.btnRetryStream.visibility = View.VISIBLE
                binding.tvStreamMessage.text = getString(R.string.device_status_disconnected)
            } else {
                binding.streamOverlay.visibility = View.VISIBLE
                binding.pbStreamLoading.visibility = View.VISIBLE
                binding.btnRetryStream.visibility = View.GONE
                binding.tvStreamMessage.text = getString(R.string.stream_loading)
                lastStreamNetwork = null // force reset
                setupMediaStream(network)
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.refreshStatus()
                }
            }
        }

        binding.btnMoreOptions.setOnClickListener { view ->
            showMoreMenu(view)
        }

        val expandGesture = android.view.GestureDetector(requireContext(), object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: android.view.MotionEvent) = true
            override fun onDoubleTap(event: android.view.MotionEvent): Boolean { toggleFullscreen(); return true }
        })
        binding.glPlayerView.setOnTouchListener { _, event -> expandGesture.onTouchEvent(event) }
        binding.btnFullscreen.setOnClickListener {
            toggleFullscreen()
        }
        binding.root.findViewById<View>(R.id.btn_live_expand)?.setOnClickListener { toggleFullscreen() }
        binding.root.findViewById<View>(R.id.btn_live_pip)?.setOnClickListener { launchNavigationAndPip() }
        binding.batteryContainer.setOnClickListener {
            Toast.makeText(requireContext(), binding.ivBatteryIcon.contentDescription, Toast.LENGTH_SHORT).show()
        }
        binding.tvSdStatus.setOnClickListener {
            val estimate = viewModel.storageEstimate.value
            val info = if (viewModel.connectionStatus.value != ConnectionStatus.CONNECTED) {
                getString(R.string.live_recorder_offline_memory_card_details_unavailable)
            } else if (estimate.totalBytes > 0 && estimate.freeBytes >= 0) {
                val gb = 1024.0 * 1024.0 * 1024.0
                String.format(Locale.getDefault(), getString(R.string.live_total_f_gb_nused_f_gb),
                    estimate.totalBytes / gb, estimate.usedBytes / gb, estimate.freeBytes / gb, estimate.statusText)
            } else estimate.statusText
            androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(getString(R.string.live_memory_card))
                .setMessage(info).setPositiveButton(getString(R.string.device_ok), null).show()
        }

        binding.hudContainer.setOnClickListener {
            val context = requireContext()
            val fineGranted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarseGranted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!fineGranted && !coarseGranted) {
                (activity as? MainActivity)?.requestLocationPermission()
            }
        }

        // Toggle Live Mode: Normal vs Split vs Map vs Ride
        binding.toggleLiveMode?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                selectedModeId = checkedId
                applyLiveMode()
                if (checkedId == R.id.btn_mode_map || checkedId == R.id.btn_mode_split) {
                    (activity as? MainActivity)?.requestLocationPermission()
                }
            }
        }

        binding.fabMapCenter?.setOnClickListener {
            if (!viewModel.gpsStatus.value.hasPermission) {
                (activity as? MainActivity)?.requestLocationPermission()
                return@setOnClickListener
            }
            isMapFollowEnabled = true
            val gps = viewModel.gpsStatus.value
            if (gps.isLocked && gps.latitude != null && gps.longitude != null) {
                val latLng = LatLng(gps.latitude, gps.longitude)
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
                Toast.makeText(requireContext(), getString(R.string.live_following_your_current_location), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), getString(R.string.live_phone_location_unavailable), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnMapClearTrajectory?.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.live_clear_this_route))
                .setMessage(getString(R.string.live_only_the_route_shown_on_this))
                .setPositiveButton(getString(R.string.live_clear)) { _, _ ->
                    viewModel.gpsSpeedometer.resetTrip()
                    trajectoryPolyline?.points = emptyList()
                }.setNegativeButton(getString(R.string.album_cancel), null).show()
        }

        binding.btnMapType.setOnClickListener {
            googleMap?.let { map ->
                val satellite = map.mapType != GoogleMap.MAP_TYPE_HYBRID
                map.mapType = if (satellite) GoogleMap.MAP_TYPE_HYBRID else GoogleMap.MAP_TYPE_NORMAL
                savedMapType = map.mapType
                binding.btnMapType.text = if (satellite) getString(R.string.live_standard_map) else getString(R.string.live_satellite)
            }
        }

        binding.fabMapRecord?.setOnClickListener {
            binding.btnToggleRecording.performClick()
        }

        binding.fabMapSnapshot?.setOnClickListener {
            binding.btnSnapshot.performClick()
        }
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, getString(R.string.action_pip_mode))
        val mainAct = activity as? MainActivity
        val isFs = mainAct?.isFullscreen == true
        popup.menu.add(0, 2, 1, if (isFs) getString(R.string.action_exit_fullscreen) else getString(R.string.action_enter_fullscreen))
        popup.menu.add(0, 3, 2, getString(R.string.live_clear_route))
        popup.menu.add(0, 5, 4, getString(R.string.live_snapshot_phone))
        popup.menu.add(0, 6, 5, getString(R.string.ride_title))
        val rides = com.aladdin.dashcam.gps.RideRepository.get(requireContext())
        popup.menu.add(0, 7, 6, getString(if (rides.activeId.value == null) R.string.ride_start else R.string.ride_stop))
        if (selectedModeId == R.id.btn_mode_map || selectedModeId == R.id.btn_mode_split) {
            popup.menu.add(0, 4, 3, binding.btnMapType.text)
        }
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    launchNavigationAndPip()
                    true
                }
                2 -> {
                    toggleFullscreen()
                    true
                }
                3 -> {
                    binding.btnMapClearTrajectory.performClick()
                    true
                }
                4 -> {
                    binding.btnMapType.performClick()
                    true
                }
                5 -> {
                    savePhoneSnapshot()
                    true
                }
                6 -> {
                    startActivity(Intent(requireContext(), RideHistoryActivity::class.java))
                    true
                }
                7 -> {
                    val intent = Intent(requireContext(), com.aladdin.dashcam.gps.RideRecordingService::class.java)
                    if (rides.activeId.value != null) requireContext().startService(intent.setAction("stop"))
                    else if (viewModel.gpsStatus.value.hasPermission) {
                        try { ContextCompat.startForegroundService(requireContext(), intent) }
                        catch (error: Exception) { Toast.makeText(requireContext(), error.message, Toast.LENGTH_LONG).show() }
                    } else startActivity(Intent(requireContext(), RideHistoryActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun savePhoneSnapshot() {
        if (takingPhoto) return
        takingPhoto = true
        updateRecordingTimerUi(viewModel.recordingTimerDisplay.value)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val context = requireContext().applicationContext
                val name = java.text.SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(java.util.Date()) +
                    "_SNAP_${java.util.UUID.randomUUID().toString().take(8)}.jpg"
                val file = java.io.File(com.aladdin.dashcam.media.MediaFiles.moviesDir(context), name)
                val result = nativePlayer?.saveSnapshot(file) ?: Result.failure(IllegalStateException(getString(R.string.live_snapshot_no_frame)))
                result.getOrThrow()
                val published = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { com.aladdin.dashcam.media.MediaFiles.publish(context, file, false) }.isSuccess
                }
                viewModel.loadLocalMedia()
                Toast.makeText(requireContext(), getString(if (published) R.string.live_snapshot_saved else R.string.live_snapshot_app_only), Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Toast.makeText(requireContext(), getString(R.string.live_photo_failed_s, error.message), Toast.LENGTH_LONG).show() }
            finally { takingPhoto = false; updateRecordingTimerUi(viewModel.recordingTimerDisplay.value) }
        }
    }

    fun toggleFullscreen() {
        val mainAct = activity as? MainActivity ?: return
        val newState = !mainAct.isFullscreen
        mainAct.setFullscreen(newState)
        updateFullscreenLayout(newState)
    }

    fun updateFullscreenLayout(isFullscreen: Boolean) {
        if (_binding == null) return
        val controlsVisibility = if (isFullscreen) View.GONE else View.VISIBLE
        binding.statusStrip.visibility = View.VISIBLE
        binding.layoutLiveModeToggle?.visibility = controlsVisibility
        binding.layoutNormalControls?.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        binding.layoutRideMode?.visibility = if (isFullscreen) View.GONE else (if (binding.toggleLiveMode?.checkedButtonId == R.id.btn_mode_ride) View.VISIBLE else View.GONE)
        binding.btnFullscreen.visibility = View.VISIBLE
        binding.hudPipStatusContainer.visibility = View.GONE
        updatePreviewGeometry(isFullscreen)
        if (isFullscreen && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            setupMediaStream(viewModel.networkMonitor.wifiNetwork.value)
        }
        val iconRes = if (isFullscreen) android.R.drawable.ic_menu_close_clear_cancel else R.drawable.ic_live_fullscreen
        binding.btnFullscreen.setImageResource(iconRes)
        binding.btnFullscreen.contentDescription = if (isFullscreen) {
            getString(R.string.action_exit_fullscreen)
        } else {
            getString(R.string.action_enter_fullscreen)
        }
    }

    private fun updatePreviewGeometry(expanded: Boolean) {
        binding.liveViewActions.visibility = View.GONE
        if (!expanded) {
            applyLiveMode()
            return
        }
        binding.previewContainer.visibility = View.VISIBLE
        binding.mapContainer.visibility = View.GONE
        binding.layoutRideMode.visibility = View.GONE
        val constraints = ConstraintSet().apply { clone(binding.root) }
        constraints.setDimensionRatio(R.id.preview_container, null)
        constraints.constrainHeight(R.id.preview_container, ConstraintSet.MATCH_CONSTRAINT)
        constraints.connect(R.id.preview_container, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        constraints.connect(R.id.preview_container, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        constraints.connect(R.id.preview_container, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        constraints.connect(R.id.preview_container, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        constraints.applyTo(binding.root)
    }

    private fun launchNavigationAndPip() {
        try {
            val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
            mapIntent.setPackage("com.google.android.apps.maps")
            mapIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(mapIntent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.live_google_maps_is_not_installed_opening), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), getString(R.string.live_pip_is_not_supported_on_this), Toast.LENGTH_SHORT).show()
        }
    }

    fun updatePipLayout(isInPip: Boolean) {
        if (_binding == null) return
        if (!isInPip) {
            updateFullscreenLayout((activity as? MainActivity)?.isFullscreen == true)
            return
        }
        val vis = if (isInPip) View.GONE else View.VISIBLE
        binding.statusStrip.visibility = vis
        binding.layoutLiveModeToggle?.visibility = vis
        binding.layoutNormalControls?.visibility = vis
        binding.layoutRideMode?.visibility = View.GONE
        binding.btnFullscreen.visibility = vis
        binding.hudPipStatusContainer.visibility = if (isInPip) View.VISIBLE else View.GONE
        updatePreviewGeometry(isInPip || (activity as? MainActivity)?.isFullscreen == true)
        if (isInPip) setupMediaStream(viewModel.networkMonitor.wifiNetwork.value)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recordingTimerDisplay.collect { timer ->
                        updateRecordingTimerUi(timer)
                    }
                }

                launch {
                    viewModel.deviceStatus.collect { status ->
                        // S3 / S4: Battery vector icons and accessibility texts
                        updateBatteryUi(status.batteryLevel, status.isCharging, status.lastUpdatedTimestamp != 0L)

                    }
                }

                launch {
                    kotlinx.coroutines.flow.combine(viewModel.storageEstimate, viewModel.connectionStatus) { estimate, connection ->
                        estimate to connection
                    }.collect { (estimate, connection) ->
                        if (connection != ConnectionStatus.CONNECTED) {
                            binding.tvSdStatus.text = getString(R.string.device_status_disconnected)
                        } else if (estimate.totalBytes > 0 && estimate.freeBytes >= 0) {
                            val gb = 1024.0 * 1024.0 * 1024.0
                            binding.tvSdStatus.text = String.format(Locale.getDefault(), getString(R.string.live_sd_f_f_gb_free),
                                estimate.freeBytes / gb, estimate.totalBytes / gb)
                        } else if (estimate.isNormal) {
                            if (estimate.listedMediaTotalBytes > 0L) {
                                val mb = estimate.listedMediaTotalBytes / (1024.0 * 1024.0)
                                binding.tvSdStatus.text = String.format(Locale.getDefault(), getString(R.string.live_sd_ready_files_f_mb), mb)
                            } else {
                                binding.tvSdStatus.text = getString(R.string.live_sd_ready_recording)
                            }
                        } else if (estimate.hasSdCard) {
                            binding.tvSdStatus.text = getString(R.string.live_memory_card_error)
                        } else {
                            binding.tvSdStatus.text = getString(R.string.live_no_memory_card_detected)
                        }
                        binding.tvSdStatus.contentDescription = estimate.statusText
                        updateRecordingTimerUi(viewModel.recordingTimerDisplay.value)
                    }
                }

                launch {
                    viewModel.gpsStatus.collect { gps ->
                        (binding.tvHudSpeed.parent as View).visibility = if (gps.speedKmh != null) View.VISIBLE else View.GONE
                        binding.tvHudSpeed.text = if (gps.speedKmh != null) {
                            String.format("%02d", gps.speedKmh)
                        } else {
                            "--"
                        }

                        binding.tvHudGpsStatus.text = if (gps.isLocked) {
                            gpsSourceName(gps)
                        } else if (!gps.hasPermission) {
                            getString(R.string.hud_gps_disabled)
                        } else {
                            getString(R.string.hud_gps_searching)
                        }

                        // Ride Mode HUD Updates (safe call for landscape mode)
                        binding.speedGauge?.setSpeed(if (gps.isLocked) gps.speedKmh else null)
                        binding.tvRideAltitude?.text = if (gps.isLocked && gps.altitudeMeters != null) {
                            String.format(java.util.Locale.getDefault(), "%.0f m", gps.altitudeMeters)
                        } else {
                            "-- m"
                        }

                        binding.tvRideAscent?.text = if (gps.isLocked) {
                            String.format(
                                java.util.Locale.getDefault(),
                                "+%.0f m",
                                gps.accumulatedAscentMeters
                            )
                        } else {
                            "-- m"
                        }

                        val bearing = gps.bearingDegrees
                        binding.tvRideBearing?.text = if (gps.isLocked && bearing != null) {
                            val dirIdx = (((bearing + 22.5f) % 360) / 45).toInt()
                            val dirStr = when (dirIdx) {
                                0 -> getString(R.string.live_n)
                                1 -> getString(R.string.live_ne)
                                2 -> getString(R.string.live_e)
                                3 -> getString(R.string.live_se)
                                4 -> getString(R.string.live_s)
                                5 -> getString(R.string.live_sw)
                                6 -> getString(R.string.live_w)
                                7 -> getString(R.string.live_nw)
                                else -> getString(R.string.live_n)
                            }
                            String.format(java.util.Locale.getDefault(), "%.0f° %s", bearing, dirStr)
                        } else {
                            "--°"
                        }

                        binding.tvRideGforce?.text = if (gps.accelerationG != null) {
                            String.format(java.util.Locale.getDefault(), "%.2f G", gps.accelerationG)
                        } else {
                            "-- G"
                        }

                        binding.tvRideGpsSource?.text = if (gps.isLocked) {
                            getString(R.string.live_source_s_gps_locked, gpsSourceName(gps))
                        } else if (!gps.hasPermission) {
                            getString(R.string.live_location_permission_required_tap_the_speed)
                        } else {
                            getString(R.string.live_source_s_searching_for_satellites, gpsSourceName(gps))
                        }

                        updateMapLocation(gps)
                    }
                }

                launch {
                    kotlinx.coroutines.flow.combine(
                        viewModel.wifiNetwork,
                        viewModel.deviceHost,
                        viewModel.currentStreamOwner,
                        viewModel.targetIdentity,
                        viewModel.deviceStatus.map { it.mode }.distinctUntilChanged()
                    ) { net, host, owner, _, mode -> Triple(net, host, owner) to mode }
                        .collect { (stream, mode) ->
                            val (network, _, owner) = stream
                            if (owner == DashCamViewModel.MediaStreamOwner.ALBUM_PLAYBACK || owner == DashCamViewModel.MediaStreamOwner.DOWNLOAD || mode == DeviceMode.PLAYBACK) {
                                // 相簿正在回放，停止 FFmpeg 預覽以釋放記錄器 8080 RTSP 端口
                                streamJob?.cancel()
                                streamJob = null
                                nativePlayer?.stop()
                                lastStreamNetwork = null
                                lastStreamHost = null
                                binding.streamOverlay.visibility = View.VISIBLE
                                binding.pbStreamLoading.visibility = View.GONE
                                binding.btnRetryStream.visibility = View.GONE
                                binding.tvStreamMessage.text = getString(R.string.live_album_is_playing_a_video_live)
                            } else if (owner == DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW || owner == DashCamViewModel.MediaStreamOwner.NONE) {
                                setupMediaStream(network)
                            }
                        }
                }
            }
        }
    }

    private fun updateRecordingTimerUi(timer: RecordingTimerDisplay) {
        if (_binding == null) return
        val connected = viewModel.connectionStatus.value == ConnectionStatus.CONNECTED
        val mode = viewModel.deviceStatus.value.mode
        val canTakePhoto = connected && !takingPhoto && (mode == DeviceMode.RECORD || mode == DeviceMode.CAPTURE)
        val seconds = timer.displaySeconds.coerceAtLeast(0L)
        val recordingText = getString(R.string.recording_elapsed, seconds / 3600, seconds / 60 % 60, seconds % 60)
        binding.btnToggleRecording.isEnabled = connected && !timer.isUnknown && !takingPhoto
        binding.btnSnapshot.isEnabled = canTakePhoto
        binding.btnSnapshot.text = getString(if (takingPhoto) R.string.live_photo_in_progress else R.string.btn_take_photo)
        binding.btnToggleRecording.alpha = if (binding.btnToggleRecording.isEnabled) 1f else 0.35f
        binding.btnSnapshot.alpha = if (binding.btnSnapshot.isEnabled) 1f else 0.35f
        binding.fabMapRecord.isEnabled = connected && !timer.isUnknown && !takingPhoto
        binding.fabMapSnapshot.isEnabled = canTakePhoto
        binding.fabMapRecord.alpha = if (binding.fabMapRecord.isEnabled) 1f else 0.35f
        binding.fabMapSnapshot.alpha = if (binding.fabMapSnapshot.isEnabled) 1f else 0.35f
        if (!connected) binding.tvSdStatus.text = getString(R.string.device_status_disconnected)
        when (timer.status) {
            RecordingStatus.RECORDING -> {
                binding.ivRecordDot.visibility = View.VISIBLE
                binding.tvRecordStatus.text = recordingText
                binding.btnToggleRecording.text = getString(R.string.btn_stop_recording_compact)
                binding.btnToggleRecording.contentDescription = getString(R.string.btn_stop_recording)
                binding.btnToggleRecording.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))

                // HUD / PiP indicator (C10)
                binding.ivHudRecordDot.visibility = View.VISIBLE
                binding.tvHudRecordText.text = recordingText
                binding.tvHudRecordText.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.record_active_red)
                )
                binding.fabMapRecord?.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.error_red)
            }
            RecordingStatus.STOPPED -> {
                binding.ivRecordDot.visibility = View.INVISIBLE
                binding.tvRecordStatus.text = getString(R.string.recording_stopped)
                binding.btnToggleRecording.text = getString(R.string.btn_start_recording_compact)
                binding.btnToggleRecording.contentDescription = getString(R.string.btn_start_recording)
                binding.btnToggleRecording.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

                // HUD / PiP indicator (C10)
                binding.ivHudRecordDot.visibility = View.INVISIBLE
                binding.tvHudRecordText.text = getString(R.string.live_stopped)
                binding.tvHudRecordText.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.text_secondary)
                )
                binding.fabMapRecord?.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.accent_teal)
            }
            RecordingStatus.UNKNOWN -> {
                binding.ivRecordDot.visibility = View.INVISIBLE
                binding.tvRecordStatus.text = getString(R.string.recording_unknown)
                binding.btnToggleRecording.text = getString(R.string.btn_start_recording_compact)
                binding.btnToggleRecording.contentDescription = getString(R.string.btn_start_recording)
                binding.btnToggleRecording.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

                // HUD / PiP indicator (C10)
                binding.ivHudRecordDot.visibility = View.INVISIBLE
                binding.tvHudRecordText.text = getString(R.string.live_unknown)
                binding.tvHudRecordText.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.warning_yellow)
                )
                binding.fabMapRecord?.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.accent_teal)
            }
        }
    }

    private fun updateRecordingButton(status: RecordingStatus) {
        val currentTimer = viewModel.recordingTimerDisplay.value
        updateRecordingTimerUi(currentTimer)
    }

    private fun updateBatteryUi(rawBatteryLevel: Int, isCharging: Boolean, isStatusValid: Boolean) {
        if (_binding == null) return
        val isValid = isStatusValid && rawBatteryLevel in 0..4
        val iconRes = when {
            !isValid -> if (isCharging) R.drawable.ic_battery_charging_unknown else R.drawable.ic_battery_unknown
            isCharging -> when (rawBatteryLevel) {
                4 -> R.drawable.ic_battery_charging_4
                3 -> R.drawable.ic_battery_charging_3
                2 -> R.drawable.ic_battery_charging_2
                1 -> R.drawable.ic_battery_charging_1
                else -> R.drawable.ic_battery_charging_0
            }
            else -> when (rawBatteryLevel) {
                4 -> R.drawable.ic_battery_4
                3 -> R.drawable.ic_battery_3
                2 -> R.drawable.ic_battery_2
                1 -> R.drawable.ic_battery_1
                else -> R.drawable.ic_battery_0
            }
        }
        val cdText = when {
            !isValid -> if (isCharging) "${getString(R.string.battery_cd_charging_prefix)}${getString(R.string.battery_cd_unknown)}" else getString(R.string.battery_cd_unknown)
            isCharging -> when (rawBatteryLevel) {
                4 -> "${getString(R.string.battery_cd_charging_prefix)}${getString(R.string.battery_cd_level_4)}"
                3 -> "${getString(R.string.battery_cd_charging_prefix)}${getString(R.string.battery_cd_level_3)}"
                2 -> "${getString(R.string.battery_cd_charging_prefix)}${getString(R.string.battery_cd_level_2)}"
                1 -> "${getString(R.string.battery_cd_charging_prefix)}${getString(R.string.battery_cd_level_1)}"
                else -> "${getString(R.string.battery_cd_charging_prefix)}${getString(R.string.battery_cd_level_0)}"
            }
            else -> when (rawBatteryLevel) {
                4 -> getString(R.string.battery_cd_level_4)
                3 -> getString(R.string.battery_cd_level_3)
                2 -> getString(R.string.battery_cd_level_2)
                1 -> getString(R.string.battery_cd_level_1)
                else -> getString(R.string.battery_cd_level_0)
            }
        }

        binding.ivBatteryIcon.setImageResource(iconRes)
        binding.ivBatteryIcon.contentDescription = cdText
        binding.ivHudBatteryIcon.setImageResource(iconRes)
        binding.ivHudBatteryIcon.contentDescription = cdText
    }

    private fun gpsSourceName(gps: GpsStatus): String = getString(
        if (gps.sourceName == "手機網路定位") R.string.gps_network_source else R.string.gps_phone_source)

    private fun updateMapLocation(gps: GpsStatus) {
        val map = googleMap ?: return
        // Accelerometer updates share this flow; they must not redraw the route or restart camera animations.
        val previous = lastMapStatus
        if (previous != null && previous.lastUpdateElapsedRealtime == gps.lastUpdateElapsedRealtime &&
            previous.isLocked == gps.isLocked && previous.hasPermission == gps.hasPermission &&
            previous.trajectory === gps.trajectory) return
        lastMapStatus = gps
        binding.fabMapCenter.alpha = if (gps.isLocked) 1f else 0.65f
        if (gps.isLocked && gps.latitude != null && gps.longitude != null) {
            val currentPos = LatLng(gps.latitude, gps.longitude)
            if (phoneMarker == null) {
                val markerOptions = MarkerOptions()
                    .position(currentPos)
                    .title(getString(R.string.live_phone_location))
                    .snippet(getString(R.string.live_speed_s_km_h, gps.speedKmh?.toString() ?: "--"))
                    .flat(true)
                    .anchor(0.5f, 0.5f)
                phoneMarker = map.addMarker(markerOptions)
                if (isMapFollowEnabled && !hasSavedMapCamera) map.moveCamera(CameraUpdateFactory.newLatLngZoom(currentPos, 16f))
            } else {
                phoneMarker?.position = currentPos
                if (gps.bearingDegrees != null) {
                    phoneMarker?.rotation = gps.bearingDegrees
                }
                phoneMarker?.snippet = getString(R.string.live_speed_s_km_h, gps.speedKmh?.toString() ?: "--")
                phoneMarker?.alpha = 1f
            }

            if (isMapFollowEnabled) {
                map.animateCamera(CameraUpdateFactory.newLatLng(currentPos))
            }

            if (gps.trajectory.isNotEmpty()) {
                val pts = gps.trajectory.map { LatLng(it.latitude, it.longitude) }
                trajectoryPolyline?.points = pts
            } else {
                trajectoryPolyline?.points = emptyList()
            }

            binding.tvMapGpsInfo?.text = "● ${gpsSourceName(gps)}"
            binding.tvMapSpeedInfo?.text = String.format(
                Locale.getDefault(),
                "%s · ±%.0fm",
                if (gps.speedKmh != null) "${gps.speedKmh} km/h" else "--",
                gps.accuracyMeters
            )
        } else {
            phoneMarker?.alpha = 0.35f
            binding.tvMapGpsInfo?.text = if (!gps.hasPermission) getString(R.string.live_location_disabled) else getString(R.string.live_searching_for_gps)
            binding.tvMapSpeedInfo?.text = getString(R.string.live_km_h_accuracy_unknown)
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            binding.mapView.onStart()
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        viewModel.gpsSpeedometer.startListening()
        try {
            binding.mapView.onResume()
        } catch (_: Exception) {}
        if (viewModel.currentStreamOwner.value == DashCamViewModel.MediaStreamOwner.NONE) {
            viewModel.requestStreamOwner(DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            binding.mapView.onPause()
        } catch (_: Exception) {}
        if (viewModel.currentStreamOwner.value == DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW) {
            viewModel.releaseStreamOwner(DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW)
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.gpsSpeedometer.stopListening()
        try {
            binding.mapView.onStop()
        } catch (_: Exception) {}
        streamJob?.cancel()
        streamJob = null
        nativePlayer?.stop()
        lastStreamNetwork = null
        lastStreamHost = null
    }

    override fun onDestroyView() {
        mapLoadJob?.cancel()
        mapLoaded = false
        mapRequested = false
        try {
            binding.mapView.onDestroy()
        } catch (_: Exception) {}
        googleMap = null
        lastMapStatus = null
        phoneMarker = null
        trajectoryPolyline = null
        super.onDestroyView()
        if (viewModel.currentStreamOwner.value == DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW) {
            viewModel.releaseStreamOwner(DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW)
        }
        streamJob?.cancel()
        streamJob = null
        nativePlayer?.release()
        nativePlayer = null
        _binding = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            binding.mapView.onLowMemory()
        } catch (_: Exception) {}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("live_mode", selectedModeId)
        outState.putBoolean("map_follow", isMapFollowEnabled)
        outState.putBoolean("map_camera", googleMap != null)
        outState.putInt("map_type", savedMapType)
        try {
            _binding?.mapView?.onSaveInstanceState(outState)
        } catch (_: Exception) {}
    }
}
