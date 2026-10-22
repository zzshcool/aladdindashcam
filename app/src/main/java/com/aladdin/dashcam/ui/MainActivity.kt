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
        val wifiNet = viewModel.networkMonitor.wifiNetwork.value
        val detectedGateway = viewModel.networkMonitor.getWifiGatewayIp() ?: "192.168.25.1"
        val probe = viewModel.probeStatus.value

        if (wifiNet != null) {
            when (probe) {
                is ProbeStatus.Success -> {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("新增行車記錄器")
                        .setMessage("已偵測並成功驗證記錄器 Wi-Fi！\n設備閘道位址：$detectedGateway (${probe.protocolHeader})\n\n是否立即自動綁定並建立連線？")
                        .setPositiveButton("立即自動連線") { _, _ ->
                            viewModel.autoMatchOrAddDeviceForHost(detectedGateway, probe.protocolHeader)
                            android.widget.Toast.makeText(this, "正在連線至記錄器 ($detectedGateway)...", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                is ProbeStatus.Probing -> {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("正在辨識設備")
                        .setMessage("已連線至 Wi-Fi，正在探測設備閘道 ($detectedGateway) 之記錄器通訊協定，請稍候片刻...")
                        .setPositiveButton("確定", null)
                        .show()
                }
                is ProbeStatus.NotDashCam -> {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("非相容行車記錄器")
                        .setMessage("當前連線之 Wi-Fi ($detectedGateway) 經安全協定探測非相容行車記錄器（非 GPSOCK 協定）。\n請確認已連線至記錄器 Wi-Fi AP（例如 B1-xxxxxx）。")
                        .setPositiveButton("前往 Wi-Fi 設定") { _, _ ->
                            try {
                                startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                            } catch (_: Exception) {}
                        }
                        .setNegativeButton("關閉", null)
                        .show()
                }
                is ProbeStatus.Failed -> {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("設備探測未完成")
                        .setMessage("當前 Wi-Fi ($detectedGateway) 探測記錄器回應逾時或失敗：${probe.reason ?: "無回應"}\n若確認為記錄器，請稍候重試或重新連接 Wi-Fi。")
                        .setPositiveButton("重試探測") { _, _ ->
                            viewModel.networkMonitor.startMonitoring()
                        }
                        .setNegativeButton("關閉", null)
                        .show()
                }
                is ProbeStatus.Idle -> {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("辨識行車記錄器")
                        .setMessage("設備閘道：$detectedGateway\n尚未完成通訊協定握手，是否立即嘗試連線？")
                        .setPositiveButton("嘗試連線") { _, _ ->
                            viewModel.autoMatchOrAddDeviceForHost(detectedGateway)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        } else {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("未連線至記錄器 Wi-Fi")
                .setMessage("新增設備無需手動輸入 IP。\n請先於手機「Wi-Fi 設定」中連線至記錄器 Wi-Fi AP（例如 B1-xxxxxx），連線後 App 將自動辨識並完成新增。")
                .setPositiveButton("前往 Wi-Fi 設定") { _, _ ->
                    try {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                    } catch (_: Exception) {}
                }
                .setNegativeButton("關閉", null)
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
            binding.topBar.visibility = View.VISIBLE
            binding.bottomNav.visibility = View.VISIBLE
            liveFragment?.updatePipLayout(false)
        }
    }
}
