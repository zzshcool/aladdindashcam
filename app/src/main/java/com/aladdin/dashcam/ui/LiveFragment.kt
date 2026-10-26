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
import kotlinx.coroutines.flow.first
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
    private var pendingModeId: Int? = null
    private var mapLoaded = false
    private var mapRequested = false
    private var hasSavedMapCamera = false
    private var savedMapType = GoogleMap.MAP_TYPE_NORMAL
    private var lastMapStatus: GpsStatus? = null
    private var mapLoadJob: Job? = null
    private var takingPhoto = false
    private var navigationDialog: androidx.appcompat.app.AlertDialog? = null
    private var pendingDestination: String? = null
    private var pendingArrivalTarget: android.location.Address? = null
    private var destinationLookupJob: Job? = null
    private var selectingArrivalDestination: String? = null
    private var navigationWindowBounds: android.graphics.Rect? = null
    private val navigationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val destination = pendingDestination
        pendingDestination = null
        if (destination != null && grants.values.any { it }) resolveArrivalDestination(destination)
        else Toast.makeText(context, R.string.ride_permission, Toast.LENGTH_LONG).show()
    }

    fun selectMode(modeId: Int) {
        selectedModeId = modeId
        pendingModeId = if (_binding == null) modeId else null
        if (_binding != null) {
            binding.toggleLiveMode?.check(modeId)
            applyLiveMode()
        }
    }

    private fun applyLiveMode() {
        if (_binding == null) return
        if (isSystemNavigationWindow()) {
            binding.layoutLiveModeToggle.visibility = View.GONE
            binding.hudContainer.visibility = View.GONE
            binding.btnFullscreen.visibility = View.GONE
            binding.statusStrip.visibility = View.VISIBLE
            binding.layoutNormalControls.visibility = View.VISIBLE
            updatePreviewGeometry(true)
            return
        }
        val expanded = (activity as? MainActivity)?.isFullscreen == true || activity?.isInPictureInPictureMode == true
        if (expanded) {
            updatePreviewGeometry(true)
            return
        }
        val mapMode = selectedModeId == R.id.btn_mode_map
        val split = selectedModeId == R.id.btn_mode_split
        val ride = selectedModeId == R.id.btn_mode_ride
        for (id in intArrayOf(R.id.btn_mode_normal, R.id.btn_mode_split, R.id.btn_mode_map, R.id.btn_mode_ride)) {
            binding.root.findViewById<android.widget.TextView>(id).setTextColor(ContextCompat.getColor(requireContext(),
                if (id == selectedModeId) R.color.accent_blue else R.color.text_secondary))
        }
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
        constraints.clear(R.id.layout_normal_controls, start)
        connect(R.id.layout_normal_controls, end, parent, end)
        constraints.clear(R.id.layout_normal_controls, top)
        constraints.clear(R.id.layout_normal_controls, bottom)
        connect(R.id.layout_normal_controls, bottom, parent, bottom)
        val density = resources.displayMetrics.density
        constraints.setMargin(R.id.layout_normal_controls, end, (8 * density).toInt())
        constraints.setMargin(R.id.layout_normal_controls, bottom, ((if (mapMode || split || ride) 8 else 40) * density).toInt())
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
            connect(R.id.preview_container, bottom, parent, bottom)
        }
        for (id in intArrayOf(R.id.map_container, R.id.layout_ride_mode)) {
            connect(id, start, if (landscape && !mapMode) R.id.map_split_guideline else parent, start)
            connect(id, end, parent, end)
            connect(id, top, if (landscape || mapMode) R.id.layout_live_mode_toggle else R.id.preview_container, bottom)
            connect(id, bottom, parent, bottom)
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

    private fun isSystemNavigationWindow(): Boolean {
        val host = activity ?: return false
        val original = navigationWindowBounds ?: return false
        if (host.isInPictureInPictureMode || Build.VERSION.SDK_INT < 32) return false
        if (host.isInMultiWindowMode) return true
        val current = host.windowManager.currentWindowMetrics.bounds
        return current.width() <= original.width() && current.height() <= original.height() &&
            (current.width() < original.width() || current.height() < original.height())
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
        pendingDestination = savedInstanceState?.getString("navigation_destination")
        @Suppress("DEPRECATION")
        val restoredBounds = savedInstanceState?.getParcelable<android.graphics.Rect>("navigation_window")
        navigationWindowBounds = restoredBounds
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
        selectedModeId = pendingModeId ?: savedInstanceState?.getInt("live_mode", selectedModeId) ?: selectedModeId
        binding.toggleLiveMode?.check(selectedModeId)
        applyLiveMode()
        observeViewModel()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        pendingModeId?.let { mode ->
            pendingModeId = null
            selectMode(mode)
        }
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
                        binding.tvMapNotice.visibility = if (selectingArrivalDestination == null) View.GONE else View.VISIBLE
                        if (selectingArrivalDestination != null) binding.tvMapNotice.setText(R.string.arrival_map_hint)
                        android.util.Log.i("MapValidation", "Map tiles rendered")
                    }
                }
                map.setOnMapLongClickListener { point -> confirmArrivalMapPoint(point) }
                mapLoadJob?.cancel()
                mapLoadJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(12000)
                    if (!mapLoaded && _binding != null) {
                        binding.tvMapNotice.visibility = View.VISIBLE
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
        binding.btnRetryStream.visibility = if (activity?.isInPictureInPictureMode == true) View.GONE else View.VISIBLE
        binding.tvStreamMessage.text = if (viewModel.targetIdentity.value.state != com.aladdin.dashcam.network.TargetState.READY)
            getString(R.string.live_recorder_offline_nphone_maps_are_still) else getString(R.string.recovery_live_failed, message)
        binding.tvStreamMessage.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        binding.btnRetryStream.setText(if (viewModel.targetIdentity.value.state == com.aladdin.dashcam.network.TargetState.READY)
            R.string.btn_retry_connect else R.string.recovery_wifi_settings)
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
                native.start("rtsp://$host:8080/?action=stream", streaming = true, targetNetwork = network)
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
        binding.btnLiveHome.setOnClickListener { activity?.onBackPressedDispatcher?.onBackPressed() }
        binding.btnSnapshot.tooltipText = getString(R.string.btn_take_photo)
        binding.btnMoreOptions.tooltipText = getString(R.string.btn_more_options)
        binding.btnToggleRecording.setOnClickListener {
            val isRecording = viewModel.deviceStatus.value.recordingStatus == RecordingStatus.RECORDING
            val targetDesired = !isRecording

            // S5: Direct intent execution without AlertDialog; immediate in-flight feedback
            binding.btnToggleRecording.isEnabled = false
            binding.btnToggleRecording.contentDescription = if (isRecording) {
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
            if (network == null || viewModel.targetIdentity.value.state != com.aladdin.dashcam.network.TargetState.READY) {
                startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            } else {
                binding.streamOverlay.visibility = View.VISIBLE
                binding.pbStreamLoading.visibility = View.VISIBLE
                binding.btnRetryStream.visibility = View.GONE
                binding.tvStreamMessage.text = getString(R.string.stream_loading)
                lastStreamNetwork = null // force reset
                setupMediaStream(network)
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
        binding.btnMapDestination.setOnClickListener {
            if (com.aladdin.dashcam.gps.RideRepository.get(requireContext()).activeId.value != null) {
                startActivity(Intent(requireContext(), RideHistoryActivity::class.java).putExtra("finish_ride", true))
            } else showNavigationDialog()
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
        popup.menu.add(0, 1, 0, getString(R.string.navigation_title))
        popup.menu.add(0, 9, 8, getString(R.string.action_pip_mode))
        popup.menu.add(0, 10, 9, getString(R.string.rain_title))
        if (com.aladdin.dashcam.gps.RideRepository.get(requireContext()).activeId.value != null) {
            popup.menu.add(0, 11, 10, getString(R.string.navigation_finish))
        }
        val mainAct = activity as? MainActivity
        val isFs = mainAct?.isFullscreen == true
        popup.menu.add(0, 2, 1, if (isFs) getString(R.string.action_exit_fullscreen) else getString(R.string.action_enter_fullscreen))
        popup.menu.add(0, 3, 2, getString(R.string.live_clear_route))
        popup.menu.add(0, 5, 4, getString(R.string.live_snapshot_phone))
        popup.menu.add(0, 6, 5, getString(R.string.ride_title))
        val rides = com.aladdin.dashcam.gps.RideRepository.get(requireContext())
        popup.menu.add(0, 7, 6, getString(if (rides.activeId.value == null) R.string.ride_start else R.string.ride_stop))
        if (rides.activeId.value != null) popup.menu.add(0, 8, 7, getString(R.string.navigation_finish))
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
                8 -> {
                    startActivity(Intent(requireContext(), RideHistoryActivity::class.java).putExtra("finish_ride", true))
                    true
                }
                9 -> { enterPictureInPictureMode(); true }
                10 -> { startActivity(Intent(requireContext(), RainModeActivity::class.java)); true }
                11 -> {
                    startActivity(Intent(requireContext(), RideHistoryActivity::class.java).putExtra("finish_ride", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                        android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle())
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
        binding.hudContainer.visibility = View.VISIBLE
        binding.tvStreamMessage.textSize = 14f
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
        showNavigationDialog()
    }

    private fun showNavigationDialog() {
        if (navigationDialog?.isShowing == true) return
        val context = requireContext()
        val prefs = context.getSharedPreferences("ride_navigation", android.content.Context.MODE_PRIVATE)
        val content = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val input = android.widget.EditText(context).apply {
            hint = getString(R.string.navigation_destination_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            setSingleLine(true)
            setText(prefs.getString("last_destination", ""))
        }
        content.addView(input)
        content.addView(android.widget.CheckBox(context).apply {
            setText(R.string.arrival_auto_return)
            isChecked = prefs.getBoolean("arrival_auto_return", true)
            setOnCheckedChangeListener { _, value -> prefs.edit().putBoolean("arrival_auto_return", value).apply() }
        })
        content.addView(android.widget.TextView(context).apply {
            text = getString(R.string.arrival_rule, com.aladdin.dashcam.gps.RideArrivalDetector.RADIUS_METERS.toInt(),
                (com.aladdin.dashcam.gps.RideArrivalDetector.DWELL_MS / 1000).toInt())
            textSize = 12f
        })
        content.addView(android.widget.TextView(context).apply {
            setText(R.string.navigation_help)
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        val places = android.widget.LinearLayout(context)
        content.addView(places)
        for ((key, label) in listOf("company" to R.string.navigation_company, "home" to R.string.navigation_home)) {
            places.addView(android.widget.Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                setText(label)
                isAllCaps = false
                setOnClickListener {
                    val saved = prefs.getString(key, "").orEmpty()
                    if (saved.isNotBlank()) input.setText(saved)
                    else {
                        input.requestFocus()
                        input.error = getString(R.string.navigation_save_hint)
                    }
                }
            }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        }
        content.addView(android.widget.Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.navigation_save)
            isAllCaps = false
            setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isEmpty()) input.error = getString(R.string.navigation_destination_hint)
                else androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(R.string.navigation_save)
                    .setItems(arrayOf(getString(R.string.navigation_company), getString(R.string.navigation_home))) { _, which ->
                        val key = if (which == 0) "company" else "home"
                        val label = if (which == 0) R.string.navigation_company else R.string.navigation_home
                        prefs.edit().putString(key, value).apply()
                        Toast.makeText(context, getString(R.string.navigation_saved, getString(label)), Toast.LENGTH_SHORT).show()
                    }.show()
            }
        })
        navigationDialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.navigation_title).setView(android.widget.ScrollView(context).apply { addView(content) })
            .setNegativeButton(R.string.album_cancel, null)
            .setPositiveButton(R.string.navigation_start, null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                        val destination = input.text.toString().trim()
                        if (destination.isEmpty()) {
                            input.error = getString(R.string.navigation_destination_hint)
                            return@setOnClickListener
                        }
                        prefs.edit().putString("last_destination", destination).apply()
                        dialog.dismiss()
                        val permissions = arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (permissions.any { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                            resolveArrivalDestination(destination)
                        } else {
                            pendingDestination = destination
                            navigationPermission.launch(permissions)
                        }
                    }
                }
                dialog.setOnDismissListener { navigationDialog = null }
                dialog.show()
            }
    }

    private fun resolveArrivalDestination(destination: String) {
        val context = requireContext()
        pendingArrivalTarget = null
        if (!context.getSharedPreferences("ride_navigation", android.content.Context.MODE_PRIVATE)
                .getBoolean("arrival_auto_return", true)) {
            startRideNavigation(destination)
            return
        }
        fun unavailable() {
            if (_binding == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            androidx.appcompat.app.AlertDialog.Builder(context).setTitle(R.string.arrival_unavailable)
                .setMessage(R.string.arrival_unavailable_help)
                .setNegativeButton(R.string.album_cancel, null)
                .setNeutralButton(R.string.arrival_navigation_only) { _, _ -> startRideNavigation(destination) }
                .setPositiveButton(R.string.arrival_choose_on_map) { _, _ ->
                    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(context, R.string.arrival_unavailable_help, Toast.LENGTH_LONG).show()
                    } else {
                        selectingArrivalDestination = destination
                        selectMode(R.id.btn_mode_map)
                        binding.tvMapNotice.setText(R.string.arrival_map_hint)
                        binding.tvMapNotice.visibility = View.VISIBLE
                    }
                }.show()
        }
        if (!android.location.Geocoder.isPresent() || ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            unavailable()
            return
        }
        destinationLookupJob?.cancel()
        Toast.makeText(context, R.string.arrival_resolving, Toast.LENGTH_SHORT).show()
        destinationLookupJob = viewLifecycleOwner.lifecycleScope.launch {
            val candidates = try {
                kotlinx.coroutines.withTimeout(10000L) {
                    val geocoder = android.location.Geocoder(context, Locale.getDefault())
                    if (Build.VERSION.SDK_INT >= 33) {
                        kotlinx.coroutines.suspendCancellableCoroutine<List<android.location.Address>> { continuation ->
                            geocoder.getFromLocationName(destination, 5, object : android.location.Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                    if (continuation.isActive) continuation.resumeWith(Result.success(addresses))
                                }
                                override fun onError(errorMessage: String?) {
                                    if (continuation.isActive) continuation.resumeWith(Result.success(emptyList()))
                                }
                            })
                        }
                    } else withContext(kotlinx.coroutines.Dispatchers.IO) {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocationName(destination, 5).orEmpty()
                    }
                }.filter { it.hasLatitude() && it.hasLongitude() && it.latitude.isFinite() &&
                    it.latitude in -90.0..90.0 && it.longitude.isFinite() && it.longitude in -180.0..180.0 }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) { emptyList() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.w("LiveFragment", "Destination lookup unavailable", error); emptyList() }
            if (_binding == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            if (candidates.isEmpty()) { unavailable(); return@launch }
            androidx.appcompat.app.AlertDialog.Builder(context).setTitle(R.string.arrival_destination_confirm)
                .setSingleChoiceItems(candidates.map { it.getAddressLine(0) ?: it.featureName ?: destination }.toTypedArray(), 0, null)
                .setNegativeButton(R.string.album_cancel, null)
                .setPositiveButton(R.string.navigation_start) { dialog, _ ->
                    val index = (dialog as androidx.appcompat.app.AlertDialog).listView.checkedItemPosition
                    pendingArrivalTarget = candidates.getOrNull(index) ?: return@setPositiveButton
                    startRideNavigation(destination)
                }.show()
        }
    }

    private fun confirmArrivalMapPoint(point: LatLng) {
        val destination = selectingArrivalDestination ?: return
        val marker = googleMap?.addMarker(MarkerOptions().position(point).title(destination))
        marker?.showInfoWindow()
        androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(R.string.arrival_destination_confirm)
            .setMessage(getString(R.string.arrival_map_confirm, destination))
            .setNegativeButton(R.string.album_cancel, null)
            .setPositiveButton(R.string.navigation_start) { _, _ ->
                selectingArrivalDestination = null
                binding.tvMapNotice.visibility = if (mapLoaded) View.GONE else View.VISIBLE
                pendingArrivalTarget = android.location.Address(Locale.getDefault()).apply {
                    latitude = point.latitude; longitude = point.longitude
                    setAddressLine(0, destination)
                }
                startRideNavigation(destination)
            }.setOnDismissListener { marker?.remove() }.show()
    }

    private fun startRideNavigation(destination: String) {
        val context = requireContext()
        selectingArrivalDestination = null
        if (mapLoaded) binding.tvMapNotice.visibility = View.GONE
        val arrivalTarget = pendingArrivalTarget
        pendingArrivalTarget = null
        val uri = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api", "1")
            .appendQueryParameter("destination", arrivalTarget?.let { "${it.latitude},${it.longitude}" } ?: destination)
            .appendQueryParameter("dir_action", "navigate").build()
        val mapIntent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val systemSplit = Build.VERSION.SDK_INT >= 32
        if (systemSplit) mapIntent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        if (mapIntent.resolveActivity(context.packageManager) == null) {
            Toast.makeText(context, R.string.navigation_maps_missing, Toast.LENGTH_LONG).show()
            return
        }
        val rides = com.aladdin.dashcam.gps.RideRepository.get(context)
        val alreadyRecording = rides.activeId.value != null
        val service = Intent(context, com.aladdin.dashcam.gps.RideRecordingService::class.java)
        if (arrivalTarget != null) {
            service.putExtra(com.aladdin.dashcam.gps.RideRecordingService.DESTINATION_LATITUDE, arrivalTarget.latitude)
                .putExtra(com.aladdin.dashcam.gps.RideRecordingService.DESTINATION_LONGITUDE, arrivalTarget.longitude)
                .putExtra(com.aladdin.dashcam.gps.RideRecordingService.DESTINATION_LABEL, arrivalTarget.getAddressLine(0) ?: destination)
        } else service.putExtra("clear_arrival", true)
        try {
            ContextCompat.startForegroundService(context, service)
        } catch (error: Exception) {
            Toast.makeText(context, R.string.navigation_recording_failed, Toast.LENGTH_LONG).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                kotlinx.coroutines.withTimeout(8000L) { rides.activeId.first { it != null } }
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                selectMode(if (systemSplit) R.id.btn_mode_normal else R.id.btn_mode_split)
                if (!systemSplit && !enterPictureInPictureMode()) {
                    if (!alreadyRecording) context.stopService(service)
                    return@launch
                }
                if (systemSplit) navigationWindowBounds = android.graphics.Rect(requireActivity().windowManager.currentWindowMetrics.bounds)
                startActivity(mapIntent)
                if (systemSplit) {
                    delay(1200L)
                    if (!isSystemNavigationWindow()) {
                        navigationWindowBounds = null
                        Toast.makeText(context, R.string.navigation_split_unavailable, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    updateFullscreenLayout(false)
                }
            } catch (error: android.content.ActivityNotFoundException) {
                if (!alreadyRecording) context.stopService(service)
                Toast.makeText(context, R.string.navigation_maps_missing, Toast.LENGTH_LONG).show()
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                if (!alreadyRecording) context.stopService(service)
                Toast.makeText(context, R.string.navigation_recording_failed, Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!alreadyRecording) context.stopService(service)
                Toast.makeText(context, R.string.navigation_recording_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun enterPictureInPictureMode(): Boolean {
        val host = activity ?: return false
        if (host.isInPictureInPictureMode) return true
        val entered = try {
            if (!host.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) false
            else {
                val source = android.graphics.Rect()
                val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
                if (binding.previewContainer.getGlobalVisibleRect(source)) builder.setSourceRectHint(source)
                host.enterPictureInPictureMode(builder.build())
            }
        } catch (error: IllegalStateException) {
            android.util.Log.w("LiveFragment", "PiP unavailable", error)
            false
        }
        if (!entered) Toast.makeText(host, R.string.navigation_pip_unavailable, Toast.LENGTH_LONG).show()
        return entered
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
        binding.hudContainer.visibility = View.GONE
        binding.tvStreamMessage.textSize = 10f
        binding.btnRetryStream.visibility = View.GONE
        updatePreviewGeometry(isInPip || (activity as? MainActivity)?.isFullscreen == true)
        if (isInPip) {
            if (viewModel.currentStreamOwner.value == DashCamViewModel.MediaStreamOwner.NONE) {
                viewModel.requestStreamOwner(DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW)
            }
            setupMediaStream(viewModel.networkMonitor.wifiNetwork.value)
        }
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
                        binding.speedGauge?.contentDescription = getString(R.string.live_speed_s_km_h,
                            if (gps.isLocked) gps.speedKmh?.toString() ?: "--" else "--")
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
        binding.btnSnapshot.contentDescription = getString(if (takingPhoto) R.string.live_photo_in_progress else R.string.btn_take_photo)
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
                binding.btnToggleRecording.setIconResource(R.drawable.ic_live_stop)
                binding.btnToggleRecording.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.error_red)
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
                binding.btnToggleRecording.setIconResource(R.drawable.ic_recording_dot)
                binding.btnToggleRecording.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
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
                binding.btnToggleRecording.setIconResource(R.drawable.ic_recording_dot)
                binding.btnToggleRecording.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
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
        binding.btnToggleRecording.tooltipText = binding.btnToggleRecording.contentDescription
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
        val riding = com.aladdin.dashcam.gps.RideRepository.get(requireContext()).activeId.value != null
        binding.btnMapDestination.setIconResource(if (riding) R.drawable.ic_live_stop else R.drawable.ic_live_navigation)
        binding.btnMapDestination.contentDescription = getString(if (riding) R.string.navigation_finish_short else R.string.navigation_title)
        binding.btnMapDestination.tooltipText = binding.btnMapDestination.contentDescription
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
    }

    override fun onStop() {
        super.onStop()
        viewModel.gpsSpeedometer.stopListening()
        try {
            binding.mapView.onStop()
        } catch (_: Exception) {}
        if (viewModel.currentStreamOwner.value == DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW) {
            viewModel.releaseStreamOwner(DashCamViewModel.MediaStreamOwner.LIVE_PREVIEW)
        }
        streamJob?.cancel()
        streamJob = null
        nativePlayer?.stop()
        lastStreamNetwork = null
        lastStreamHost = null
    }

    override fun onDestroyView() {
        destinationLookupJob?.cancel()
        selectingArrivalDestination = null
        navigationDialog?.dismiss()
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
        outState.putString("navigation_destination", pendingDestination)
        outState.putParcelable("navigation_window", navigationWindowBounds)
        outState.putInt("live_mode", selectedModeId)
        outState.putBoolean("map_follow", isMapFollowEnabled)
        outState.putBoolean("map_camera", googleMap != null)
        outState.putInt("map_type", savedMapType)
        try {
            _binding?.mapView?.onSaveInstanceState(outState)
        } catch (_: Exception) {}
    }
}
