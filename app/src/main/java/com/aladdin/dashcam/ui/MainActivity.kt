package com.aladdin.dashcam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        requestPermissionsIfNeeded()
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

    private fun observeConnectionStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
                        ConnectionStatus.DISCONNECTED,
                        ConnectionStatus.STREAM_INTERRUPTED -> {
                            binding.tvConnectionStatus.text = getString(R.string.device_status_disconnected)
                            binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.error_red))
                        }
                    }
                }
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        // C12: Request both FINE and COARSE together per Android guidelines
        if (!fineGranted || !coarseGranted) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
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
