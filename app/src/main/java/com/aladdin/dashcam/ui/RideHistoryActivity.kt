package com.aladdin.dashcam.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aladdin.dashcam.R
import com.aladdin.dashcam.gps.*
import com.aladdin.dashcam.media.MediaFiles
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import kotlinx.coroutines.*
import java.io.File
import java.text.DateFormat
import java.util.Date

class RideHistoryActivity : AppCompatActivity() {
    private lateinit var rows: LinearLayout
    private lateinit var repository: RideRepository
    private var routeFragment: SupportMapFragment? = null
    private var contentJob: Job? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchRideService() }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) startRide()
        else Toast.makeText(this, R.string.ride_permission, Toast.LENGTH_LONG).show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = RideRepository.get(this)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(rows) })
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rows) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom); insets
        }
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                repository.activeId.collect { renderList() }
            }
        }
    }
    private fun button(label: String, action: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = label; isAllCaps = false; minHeight = (48 * resources.displayMetrics.density).toInt()
        setOnClickListener { action() }
    }
    private fun startRide() {
        val prefs = getPreferences(MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notificationsRequested", false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("notificationsRequested", true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchRideService()
    }
    private fun launchRideService() {
        try { ContextCompat.startForegroundService(this, Intent(this, RideRecordingService::class.java)) }
        catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
    }
    private fun toggleRide() {
        if (repository.activeId.value != null) { startService(Intent(this, RideRecordingService::class.java).setAction("stop")); return }
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) startRide() else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    private fun renderList() {
        contentJob?.cancel()
        clearMap()
        rows.removeAllViews()
        rows.addView(button("‹ ${getString(R.string.ride_title)}") { finish() })
        rows.addView(button(getString(if (repository.activeId.value == null) R.string.ride_start else R.string.ride_stop)) { toggleRide() })
        rows.addView(TextView(this).apply { text = getString(R.string.ride_notification) })
        contentJob = lifecycleScope.launch {
            val rides = withContext(Dispatchers.IO) {
                repository.summaries().map { it.id to DateFormat.getDateTimeInstance().format(Date(it.started)) + "\n" + summary(it) }
            }
            if (rides.isEmpty()) rows.addView(TextView(this@RideHistoryActivity).apply { text = getString(R.string.ride_empty) })
            rides.forEach { (id, label) ->
                rows.addView(button(label) { showRide(id) })
            }
        }
    }
    private fun summary(ride: Ride): String = summary(RideSummary.from(ride))
    private fun summary(ride: RideSummary): String = getString(R.string.ride_summary,
        ride.distance / 1000, ((ride.ended ?: ride.started) - ride.started).coerceAtLeast(0) / 60000,
        ride.maxSpeed?.toString() ?: "--", ride.ascent?.let { "%.0f".format(it) } ?: "--", ride.pointCount) +
        "\n" + getString(R.string.ride_extended_summary, ride.movingMs / 60000, ride.stops, ride.coveragePercent,
            ride.meanAccuracy?.let { "%.0f".format(it) } ?: "--") +
        if (ride.interrupted) " · ${getString(R.string.ride_interrupted)}" else ""

    private fun showRide(id: String) {
        contentJob?.cancel()
        contentJob = lifecycleScope.launch {
            val ride = withContext(Dispatchers.IO) { repository.read(id) } ?: return@launch
            clearMap()
            rows.removeAllViews()
            rows.addView(button("‹ ${getString(R.string.ride_title)}") { renderList() })
            rows.addView(TextView(this@RideHistoryActivity).apply { text = summary(ride); textSize = 17f })
            rows.addView(button(getString(R.string.ride_export_gpx)) {
                if (ride.points.isEmpty()) { Toast.makeText(this@RideHistoryActivity, R.string.ride_no_fixes, Toast.LENGTH_LONG).show(); return@button }
                lifecycleScope.launch {
                    val file = withContext(Dispatchers.IO) {
                        File(filesDir, "ride_exports").apply { mkdirs() }.let { dir -> File(dir, "ride_${ride.id}.gpx").apply { writeText(ride.gpx()) } }
                    }
                    MediaFiles.share(this@RideHistoryActivity, file, "application/gpx+xml")
                }
            })
            if (ride.points.isEmpty()) { rows.addView(TextView(this@RideHistoryActivity).apply { text = getString(R.string.ride_no_fixes) }); return@launch }
            val container = FrameLayout(this@RideHistoryActivity).apply { this.id = View.generateViewId() }
            rows.addView(container, LinearLayout.LayoutParams(-1, (360 * resources.displayMetrics.density).toInt()))
            val fragment = SupportMapFragment.newInstance()
            routeFragment = fragment
            supportFragmentManager.beginTransaction().replace(container.id, fragment).commitNow()
            fragment.getMapAsync { map ->
                if (routeFragment !== fragment) return@getMapAsync
                val bounds = LatLngBounds.Builder()
                var segment = mutableListOf<LatLng>()
                fun drawSegment() { if (segment.size >= 2) map.addPolyline(PolylineOptions().addAll(segment).color(0xff00bfa5.toInt()).width(7f)) }
                ride.points.forEachIndexed { index, p ->
                    if (index > 0 && p.elapsed - ride.points[index - 1].elapsed > Ride.MAX_GAP_MS) { drawSegment(); segment = mutableListOf() }
                    val point = LatLng(p.lat, p.lon); bounds.include(point); segment.add(point)
                }
                drawSegment()
                map.addMarker(MarkerOptions().position(LatLng(ride.points.first().lat, ride.points.first().lon)))
                container.post { if (!isFinishing) map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), container.width.coerceAtLeast(1), container.height.coerceAtLeast(1), 40)) }
            }
        }
    }
    private fun clearMap() {
        routeFragment?.let { supportFragmentManager.beginTransaction().remove(it).commitNow() }
        routeFragment = null
    }
}
