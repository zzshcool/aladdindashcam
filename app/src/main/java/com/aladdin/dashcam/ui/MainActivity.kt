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

    private var activeFragment: Fragment? = null

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
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        if (!isFullscreen && !isInPictureInPictureMode) recreate()
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    setFullscreen(false)
                    liveFragment?.updateFullscreenLayout(false)
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
            binding.topBar.visibility = View.VISIBLE
            binding.bottomNav.visibility = View.VISIBLE
            recreate()
        }
    }

    private fun setupFragments() {
        val live = LiveFragment()
        val album = AlbumFragment()
        val downloads = DownloadsFragment()
        val device = DeviceFragment()

        liveFragment = live
        albumFragment = album
        downloadsFragment = downloads
        deviceFragment = device
        activeFragment = live

        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, device, "DEVICE").hide(device)
            .add(R.id.fragment_container, downloads, "DOWNLOADS").hide(downloads)
            .add(R.id.fragment_container, album, "ALBUM").hide(album)
            .add(R.id.fragment_container, live, "LIVE")
            .commit()
    }

    private fun restoreFragments(savedInstanceState: Bundle) {
        liveFragment = supportFragmentManager.findFragmentByTag("LIVE") as? LiveFragment ?: LiveFragment()
        albumFragment = supportFragmentManager.findFragmentByTag("ALBUM") as? AlbumFragment ?: AlbumFragment()
        downloadsFragment = supportFragmentManager.findFragmentByTag("DOWNLOADS") as? DownloadsFragment ?: DownloadsFragment()
        deviceFragment = supportFragmentManager.findFragmentByTag("DEVICE") as? DeviceFragment ?: DeviceFragment()

        val activeTag = savedInstanceState.getString("ACTIVE_FRAGMENT_TAG", "LIVE")
        activeFragment = when (activeTag) {
            "ALBUM" -> albumFragment
            "DOWNLOADS" -> downloadsFragment
            "DEVICE" -> deviceFragment
            else -> liveFragment
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val tag = when (activeFragment) {
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
                R.id.nav_live -> liveFragment?.let { switchFragment(it) } ?: false
                R.id.nav_album -> albumFragment?.let { switchFragment(it) } ?: false
                R.id.nav_downloads -> downloadsFragment?.let { switchFragment(it) } ?: false
                R.id.nav_device -> deviceFragment?.let { switchFragment(it) } ?: false
                else -> false
            }
        }
    }

    private fun switchFragment(target: Fragment): Boolean {
        if (target != activeFragment && activeFragment != null) {
            supportFragmentManager.beginTransaction()
                .hide(activeFragment!!)
                .show(target)
                .commit()
            activeFragment = target
        }
        return true
    }

    private fun setupTopBarActions() {
        binding.tvDeviceName.setOnClickListener {
            showMyDashCamDialog()
        }
    }

    private fun showMyDashCamDialog() {
        val devices = viewModel.registeredDevices.value
        val items = devices.map { dev ->
            val mark = if (dev.isSelected) "✓ " else "   "
            "$mark${dev.name} (${dev.host}) - ${dev.firmwareVersion}"
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("我的記錄器")
            .setItems(items) { _, which ->
                if (which in devices.indices) {
                    val selected = devices[which]
                    viewModel.selectDevice(selected)
                    binding.tvDeviceName.text = selected.name
                    android.widget.Toast.makeText(this, "已切換至記錄器：${selected.name}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("新增記錄器") { _, _ ->
                promptAddDevice()
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    private fun promptAddDevice() {
        val input = android.widget.EditText(this).apply {
            hint = "請輸入記錄器 IP (例如 192.168.25.1)"
            setText("192.168.25.1")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("新增行車記錄器")
            .setView(input)
            .setPositiveButton("新增") { _, _ ->
                val host = input.text.toString().trim()
                if (host.isNotEmpty()) {
                    val name = "Aladdin B1-${host.takeLast(4)}"
                    viewModel.addDevice(name, host)
                    android.widget.Toast.makeText(this, "已新增記錄器 $name", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
            binding.topBar.visibility = View.VISIBLE
            binding.bottomNav.visibility = View.VISIBLE
            liveFragment?.updatePipLayout(false)
        }
    }
}
