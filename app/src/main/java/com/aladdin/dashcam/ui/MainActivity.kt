package com.aladdin.dashcam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aladdin.dashcam.R
import com.aladdin.dashcam.databinding.ActivityMainBinding
import com.aladdin.dashcam.model.ConnectionStatus
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: DashCamViewModel by viewModels()

    private var liveFragment: LiveFragment? = null
    private var albumFragment: AlbumFragment? = null
    private var downloadsFragment: DownloadsFragment? = null
    private var deviceFragment: DeviceFragment? = null
    private var homeFragment: HomeFragment? = null

    private var activeFragment: Fragment? = null
    private var albumPreviewExpanded = false

    fun setAlbumPreviewExpanded(enabled: Boolean) {
        albumPreviewExpanded = enabled
        updateTopBarVisibility()
        binding.bottomNav.visibility = if (enabled || isFullscreen || isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocation = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineLocation || coarseLocation) {
            viewModel.gpsSpeedometer.startListening()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_AladdinDashCam)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel.refreshLocalizedText()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val immersive = isFullscreen || isInPictureInPictureMode
            view.setPadding(bars.left, if (immersive) 0 else bars.top, bars.right, if (immersive) 0 else bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)

        if (savedInstanceState == null) {
            setupFragments()
        } else {
            restoreFragments(savedInstanceState)
        }

        setupBottomNav()
        observeConnectionStatus()
        setupBackPressedHandler()
    }

    var isFullscreen: Boolean = false
        private set
    private var orientationBeforeFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateTopBarVisibility()
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    setFullscreen(false)
                    liveFragment?.updateFullscreenLayout(false)
                } else if (activeFragment != homeFragment) {
                    binding.bottomNav.selectedItemId = R.id.nav_live
                    homeFragment?.let { switchFragment(it) }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    fun setFullscreen(enabled: Boolean) {
        if (enabled == isFullscreen) return
        isFullscreen = enabled
        ViewCompat.requestApplyInsets(binding.root)
        val systemBars = WindowCompat.getInsetsController(window, binding.root)
        if (enabled) {
            orientationBeforeFullscreen = requestedOrientation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            systemBars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            systemBars.hide(WindowInsetsCompat.Type.systemBars())
            binding.topBar.visibility = View.GONE
            binding.bottomNav.visibility = View.GONE
        } else {
            requestedOrientation = orientationBeforeFullscreen
            systemBars.show(WindowInsetsCompat.Type.systemBars())
            updateTopBarVisibility()
            liveFragment?.updateFullscreenLayout(false)
            ViewCompat.requestApplyInsets(binding.root)
        }
    }

    private fun setupFragments() {
        val live = LiveFragment()
        val album = AlbumFragment()
        val downloads = DownloadsFragment()
        val device = DeviceFragment()
        val home = HomeFragment()

        liveFragment = live
        albumFragment = album
        downloadsFragment = downloads
        deviceFragment = device
        homeFragment = home
        activeFragment = home
        binding.topBar.visibility = View.GONE

        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, home, "HOME")
            .commit()
    }

    private fun restoreFragments(savedInstanceState: Bundle) {
        liveFragment = supportFragmentManager.findFragmentByTag("LIVE") as? LiveFragment ?: LiveFragment()
        albumFragment = supportFragmentManager.findFragmentByTag("ALBUM") as? AlbumFragment ?: AlbumFragment()
        downloadsFragment = supportFragmentManager.findFragmentByTag("DOWNLOADS") as? DownloadsFragment ?: DownloadsFragment()
        deviceFragment = supportFragmentManager.findFragmentByTag("DEVICE") as? DeviceFragment ?: DeviceFragment()
        homeFragment = supportFragmentManager.findFragmentByTag("HOME") as? HomeFragment ?: HomeFragment()

        val activeTag = savedInstanceState.getString("ACTIVE_FRAGMENT_TAG", "HOME")
        activeFragment = when (activeTag) {
            "ALBUM" -> albumFragment
            "DOWNLOADS" -> downloadsFragment
            "DEVICE" -> deviceFragment
            "HOME" -> homeFragment
            else -> liveFragment
        }
        updateTopBarVisibility()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val tag = when (activeFragment) {
            homeFragment -> "HOME"
            albumFragment -> "ALBUM"
            downloadsFragment -> "DOWNLOADS"
            deviceFragment -> "DEVICE"
            else -> "LIVE"
        }
        outState.putString("ACTIVE_FRAGMENT_TAG", tag)
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_live -> homeFragment?.let { switchFragment(it) } ?: false
                R.id.nav_album -> albumFragment?.let {
                    it.showDeviceMedia()
                    switchFragment(it)
                } ?: false
                R.id.nav_downloads -> downloadsFragment?.let { switchFragment(it) } ?: false
                R.id.nav_device -> deviceFragment?.let { switchFragment(it) } ?: false
                else -> false
            }
        }
    }

    private fun switchFragment(target: Fragment): Boolean {
        if (target != activeFragment && activeFragment != null) {
            val transaction = supportFragmentManager.beginTransaction()
                .hide(activeFragment!!)
                .setMaxLifecycle(activeFragment!!, Lifecycle.State.CREATED)
            if (target.isAdded) transaction.show(target) else {
                val tag = when (target) {
                    homeFragment -> "HOME"
                    liveFragment -> "LIVE"
                    albumFragment -> "ALBUM"
                    downloadsFragment -> "DOWNLOADS"
                    else -> "DEVICE"
                }
                transaction.add(R.id.fragment_container, target, tag)
            }
            transaction.setMaxLifecycle(target, Lifecycle.State.RESUMED).commit()
            activeFragment = target
            updateTopBarVisibility()
        }
        return true
    }

    private fun updateTopBarVisibility() {
        val landscapeLive = activeFragment == liveFragment &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.topBar.visibility = if (activeFragment == homeFragment || activeFragment == liveFragment || albumPreviewExpanded) View.GONE else View.VISIBLE
        binding.bottomNav.visibility = if (landscapeLive || isFullscreen || isInPictureInPictureMode || albumPreviewExpanded) View.GONE else View.VISIBLE
    }

    private fun setupTopBarActions() {
        binding.tvDeviceName.setOnClickListener {
            showMyDashCamDialog()
        }
    }

    fun openLive(modeId: Int) {
        val live = liveFragment ?: return
        live.selectMode(modeId)
        switchFragment(live)
    }

    /**
     * Explicitly navigates to the Album and guarantees local media source is shown (D07)
     */
    fun openLocalAlbum() {
        binding.bottomNav.selectedItemId = R.id.nav_album
        val album = albumFragment ?: return
        album.showLocalMedia()
        switchFragment(album)
    }

    fun openDownloads() {
        binding.bottomNav.selectedItemId = R.id.nav_downloads
        downloadsFragment?.let { switchFragment(it) }
    }

    fun showMyDashCamDialog() {
        val devices = viewModel.registeredDevices.value
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg_dark))
        }
        val heading = android.widget.LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        heading.addView(android.widget.TextView(this).apply {
            text = getString(R.string.main_activity_my_recorders); textSize = 22f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }, android.widget.LinearLayout.LayoutParams(0, dp(48), 1f))
        heading.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.main_activity_close); textSize = 13f
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setOnClickListener { sheet.dismiss() }
        })
        content.addView(heading)
        val list = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        if (devices.isEmpty()) list.addView(android.widget.TextView(this).apply {
            text = getString(R.string.main_activity_no_recorder_yet_connect_to_recorder)
            setPadding(0, dp(24), 0, dp(24))
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        })
        devices.forEach { device ->
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_dark))
                strokeWidth = dp(1)
                strokeColor = ContextCompat.getColor(context, if (device.isSelected) R.color.accent_teal else R.color.border_subtle)
            }
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            row.addView(android.widget.TextView(this).apply {
                text = if (device.isSelected) "✓  ${device.name}" else device.name
                textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            })
            row.addView(android.widget.TextView(this).apply {
                val firmware = device.firmwareVersion.takeUnless { it.startsWith("讀取") || it == "未連線" } ?: getString(R.string.main_activity_unavailable)
                text = getString(R.string.main_activity_firmware_s_nlast_connected_s, firmware, device.lastConnectedTime.ifBlank { getString(R.string.main_activity_no_successful_connection_yet) })
                textSize = 12f; setPadding(0, dp(8), 0, dp(8))
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            })
            val actions = android.widget.LinearLayout(this)
            actions.addView(com.google.android.material.button.MaterialButton(this).apply {
                text = if (device.isSelected) getString(R.string.main_activity_selected) else getString(R.string.main_activity_select_recorder)
                textSize = 13f; isEnabled = !device.isSelected
                setOnClickListener { viewModel.selectDevice(device); sheet.dismiss() }
            }, android.widget.LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(com.google.android.material.button.MaterialButton(this).apply {
                text = getString(R.string.main_activity_remove); contentDescription = getString(R.string.main_activity_remove_local_record_for_s, device.name)
                textSize = 13f
                backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surface_elevated))
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.main_activity_remove_this_recorder))
                        .setMessage(getString(R.string.main_activity_only_the_local_device_record_will))
                        .setPositiveButton(getString(R.string.main_activity_remove)) { _, _ ->
                            viewModel.removeDevice(device.id); sheet.dismiss(); showMyDashCamDialog()
                        }.setNegativeButton(getString(R.string.album_cancel), null).show()
                }
            }, android.widget.LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
            row.addView(actions); card.addView(row)
            list.addView(card, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        content.addView(android.widget.ScrollView(this).apply { addView(list) },
            android.widget.LinearLayout.LayoutParams(-1, minOf(dp(320), resources.displayMetrics.heightPixels / 2)))
        content.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.main_activity_add_recorder)
            setOnClickListener { sheet.dismiss(); promptAddDevice() }
        })
        sheet.setContentView(content)
        sheet.show()
    }

    fun promptAddDevice() {
        val wifiNet = viewModel.networkMonitor.wifiNetwork.value
        val detectedGateway = viewModel.networkMonitor.getWifiGatewayIp() ?: "192.168.25.1"
        val probe = viewModel.probeStatus.value

        if (wifiNet != null) {
            when (probe) {
                is ProbeStatus.Success -> {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.home_action_add_recorder_desc))
                        .setMessage(getString(R.string.main_activity_recorder_wi_fi_verified_ngateway_s, detectedGateway, probe.protocolHeader))
                        .setPositiveButton(getString(R.string.main_activity_connect_now)) { _, _ ->
                            viewModel.autoMatchOrAddDeviceForHost(detectedGateway, probe.protocolHeader)
                            android.widget.Toast.makeText(this, getString(R.string.main_activity_connecting_to_recorder_s, detectedGateway), android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(getString(R.string.album_cancel), null)
                        .show()
                }
                is ProbeStatus.Probing -> {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.main_activity_identifying_recorder))
                        .setMessage(getString(R.string.main_activity_connected_to_wi_fi_checking_the, detectedGateway))
                        .setPositiveButton(getString(R.string.device_ok_061), null)
                        .show()
                }
                is ProbeStatus.NotDashCam -> {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.main_activity_incompatible_recorder))
                        .setMessage(getString(R.string.main_activity_the_wi_fi_device_at_s, detectedGateway))
                        .setPositiveButton(getString(R.string.main_activity_wi_fi_settings)) { _, _ ->
                            try {
                                startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                            } catch (_: Exception) {}
                        }
                        .setNegativeButton(getString(R.string.main_activity_close), null)
                        .show()
                }
                is ProbeStatus.Failed -> {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.main_activity_recorder_detection_incomplete))
                        .setMessage(getString(R.string.main_activity_recorder_detection_at_s_timed_out, detectedGateway, probe.reason ?: getString(R.string.main_activity_no_response)))
                        .setPositiveButton(getString(R.string.main_activity_retry_detection)) { _, _ ->
                            viewModel.networkMonitor.startMonitoring()
                        }
                        .setNegativeButton(getString(R.string.main_activity_close), null)
                        .show()
                }
                is ProbeStatus.Idle -> {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.main_activity_identify_recorder))
                        .setMessage(getString(R.string.main_activity_gateway_s_nprotocol_handshake_incomplete_try, detectedGateway))
                        .setPositiveButton(getString(R.string.main_activity_try_connecting)) { _, _ ->
                            viewModel.autoMatchOrAddDeviceForHost(detectedGateway)
                        }
                        .setNegativeButton(getString(R.string.album_cancel), null)
                        .show()
                }
            }
        } else {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.main_activity_recorder_wi_fi_not_connected))
                .setMessage(getString(R.string.main_activity_no_ip_address_entry_is_needed))
                .setPositiveButton(getString(R.string.main_activity_wi_fi_settings)) { _, _ ->
                    try {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                    } catch (_: Exception) {}
                }
                .setNegativeButton(getString(R.string.main_activity_close), null)
                .show()
        }
    }

    private fun observeConnectionStatus() {
        setupTopBarActions()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.registeredDevices.collect { devs ->
                        val current = devs.firstOrNull { it.isSelected } ?: devs.firstOrNull()
                        if (current != null) {
                            binding.tvDeviceName.text = current.name
                        }
                    }
                }
                launch {
                    viewModel.connectionStatus.collect { status ->
                        when (status) {
                            ConnectionStatus.CONNECTED -> {
                                binding.tvConnectionStatus.text = getString(R.string.device_status_connected)
                                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                            }
                            ConnectionStatus.CONNECTING -> {
                                binding.tvConnectionStatus.text = getString(R.string.device_status_connecting)
                                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.warning_yellow))
                            }
                            ConnectionStatus.STREAM_INTERRUPTED -> {
                                binding.tvConnectionStatus.text = getString(R.string.device_status_stream_interrupted)
                                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.warning_yellow))
                            }
                            ConnectionStatus.DISCONNECTED -> {
                                binding.tvConnectionStatus.text = getString(R.string.device_status_disconnected)
                                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Context-aware permission request (UX-04 / Chapter 27)
     * Permissions are requested gracefully without blocking core live/album features if denied.
     */
    fun requestLocationPermission() {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            viewModel.gpsSpeedometer.startListening()
            return
        }

        // Request location permissions specifically for HUD speedometer
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.topBar.visibility = View.GONE
            binding.bottomNav.visibility = View.GONE
            liveFragment?.let {
                switchFragment(it)
                it.updatePipLayout(true)
            }
        } else {
            updateTopBarVisibility()
            liveFragment?.updatePipLayout(false)
        }
        ViewCompat.requestApplyInsets(binding.root)
    }
}
