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
        if (!isFullscreen && !isInPictureInPictureMode) recreate()
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
                    if (viewModel.connectionStatus.value != ConnectionStatus.CONNECTED) it.showLocalMedia()
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
        binding.topBar.visibility = if (activeFragment == homeFragment || landscapeLive) View.GONE else View.VISIBLE
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
            text = "我的記錄器"; textSize = 22f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }, android.widget.LinearLayout.LayoutParams(0, dp(48), 1f))
        heading.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "關閉"; textSize = 13f
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setOnClickListener { sheet.dismiss() }
        })
        content.addView(heading)
        val list = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        if (devices.isEmpty()) list.addView(android.widget.TextView(this).apply {
            text = "尚未新增設備，連上記錄器 Wi-Fi 即可開始。"
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
                val firmware = device.firmwareVersion.takeUnless { it.startsWith("讀取") || it == "未連線" } ?: "尚未取得"
                text = "韌體版本：$firmware\n最近連線：${device.lastConnectedTime.ifBlank { "尚無成功連線紀錄" }}"
                textSize = 12f; setPadding(0, dp(8), 0, dp(8))
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            })
            val actions = android.widget.LinearLayout(this)
            actions.addView(com.google.android.material.button.MaterialButton(this).apply {
                text = if (device.isSelected) "目前選取" else "選取設備"
                textSize = 13f; isEnabled = !device.isSelected
                setOnClickListener { viewModel.selectDevice(device); sheet.dismiss() }
            }, android.widget.LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(com.google.android.material.button.MaterialButton(this).apply {
                text = "移除"; contentDescription = "移除 ${device.name} 的本機記錄"
                textSize = 13f
                backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surface_elevated))
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("移除此設備？")
                        .setMessage("只移除本機設備記錄，不會刪除影片或重設記錄器。")
                        .setPositiveButton("移除") { _, _ ->
                            viewModel.removeDevice(device.id); sheet.dismiss(); showMyDashCamDialog()
                        }.setNegativeButton("取消", null).show()
                }
            }, android.widget.LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
            row.addView(actions); card.addView(row)
            list.addView(card, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        content.addView(android.widget.ScrollView(this).apply { addView(list) },
            android.widget.LinearLayout.LayoutParams(-1, minOf(dp(320), resources.displayMetrics.heightPixels / 2)))
        content.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "＋ 新增記錄器"
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
        ViewCompat.requestApplyInsets(binding.root)
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
