package com.aladdin.dashcam.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aladdin.dashcam.R
import com.aladdin.dashcam.rain.RainGuardService
import kotlinx.coroutines.launch

class RainModeActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var start: Button
    private var pendingStart = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val requested = pendingStart; pendingStart = false
        if (allowed && requested) arm()
        else if (!allowed) Toast.makeText(this, R.string.rain_permissions_required, Toast.LENGTH_LONG).show()
        render()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("stop", false)) stopService(Intent(this, RainGuardService::class.java))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(rows) })
        AppAppearance.applyContentInsets(rows, 20, 20, 20, 20)
        fun button(title: Int, action: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(title); isAllCaps = false; minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { action() }; rows.addView(this)
        }
        button(R.string.rain_title) { finish() }.text = "‹ ${getString(R.string.rain_title)}"
        rows.addView(TextView(this).apply { setText(R.string.rain_description); textSize = 15f })
        status = TextView(this).apply { textSize = 14f }; rows.addView(status)
        button(R.string.rain_overlay_permission) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button(R.string.rain_notification_permission) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingStart = false
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        start = button(R.string.rain_start) { arm() }
        button(R.string.rain_stop) { stopService(Intent(this, RainGuardService::class.java)) }
        rows.addView(TextView(this).apply { setText(R.string.rain_pinning_help); textSize = 14f })
        button(R.string.rain_security_settings) {
            try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { RainGuardService.state.collect { render() } }
        }
    }
    private fun arm() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.rain_permissions_required, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!RainGuardService.notificationsReady(this)) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingStart = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else Toast.makeText(this, R.string.rain_permissions_required, Toast.LENGTH_LONG).show()
            return
        }
        try { RainGuardService.arm(this) }
        catch (error: Exception) { android.util.Log.e("RainMode", "Cannot start rain guard", error); Toast.makeText(this, R.string.rain_start_failed, Toast.LENGTH_LONG).show() }
    }
    private fun render() {
        if (!::status.isInitialized) return
        val state = RainGuardService.state.value
        status.text = getString(when (state) {
            RainGuardService.State.ARMING -> R.string.rain_arming
            RainGuardService.State.LOCKED -> R.string.rain_locked
            RainGuardService.State.OFF -> if (RainGuardService.ready(this)) R.string.rain_ready else R.string.rain_permissions_required
        })
        start.isEnabled = state == RainGuardService.State.OFF
    }
    override fun onResume() { super.onResume(); render() }
}
