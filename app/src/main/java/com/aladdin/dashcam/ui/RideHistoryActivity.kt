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
    private var finishingRideId: String? = null
    private var selectedRideId: String? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchRideService() }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) startRide()
        else Toast.makeText(this, R.string.ride_permission, Toast.LENGTH_LONG).show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = RideRepository.get(this)
        finishingRideId = savedInstanceState?.getString("finishing_ride")
        selectedRideId = savedInstanceState?.getString("selected_ride")
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(rows) })
        if (savedInstanceState == null && intent.getBooleanExtra("finish_ride", false)) finishRide()
        AppAppearance.applyContentInsets(rows, 16, 12, 16, 16)
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                repository.activeId.collect { active ->
                    val finished = finishingRideId
                    if (active == null && finished != null) {
                        finishingRideId = null
                        showRide(finished)
                    } else selectedRideId?.let { showRide(it) } ?: renderList()
                }
            }
        }
        val arrivalId = intent.getStringExtra(RideRecordingService.ARRIVAL_RIDE_ID)
        if (arrivalId != null) {
            getSystemService(android.app.NotificationManager::class.java).cancel(RideRecordingService.ARRIVAL_NOTIFICATION)
            if (repository.activeId.value == arrivalId) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.arrival_title)
                    .setMessage(getString(R.string.arrival_confirm, intent.getStringExtra(RideRecordingService.DESTINATION_LABEL).orEmpty()))
                    .setPositiveButton(R.string.navigation_finish) { _, _ -> finishRide() }
                    .setNegativeButton(R.string.arrival_continue) { _, _ -> finish() }
                    .setOnCancelListener { finish() }.show()
            } else if (repository.activeId.value == null) finishingRideId = arrivalId
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
        if (repository.activeId.value != null) { finishRide(); return }
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) startRide() else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    private fun finishRide() {
        finishingRideId = repository.activeId.value ?: return
        intent.removeExtra(RideRecordingService.ARRIVAL_RIDE_ID)
        stopService(Intent(this, RideRecordingService::class.java))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("finishing_ride", finishingRideId)
        outState.putString("selected_ride", selectedRideId)
        super.onSaveInstanceState(outState)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun rideCard(ride: RideSummary, action: () -> Unit): View {
        val context = this
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        fun label(value: String, size: Float, color: Int) = TextView(context).apply {
            text = value; textSize = size
            setTextColor(ContextCompat.getColor(context, color))
        }
        content.addView(label(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ride.started)), 14f, R.color.text_secondary))
        content.addView(label(java.lang.String.format(java.util.Locale.getDefault(), "%.2f km", ride.distance / 1000), 30f, R.color.text_primary).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
        })
        val metrics = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val minutes = ((ride.ended ?: ride.started) - ride.started).coerceAtLeast(0) / 60000
        listOf(
            getString(R.string.ride_duration_label) to java.lang.String.format(java.util.Locale.getDefault(), "%d:%02d", minutes / 60, minutes % 60),
            getString(R.string.ride_speed_label) to "${ride.maxSpeed ?: "--"} km/h",
            getString(R.string.ride_coverage_label) to "${ride.coveragePercent}%"
        ).forEach { (title, value) ->
            metrics.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 12f, R.color.text_secondary))
                addView(label(value, 16f, R.color.text_primary).apply { setPadding(0, dp(4), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        content.addView(metrics)
        content.addView(label(if (ride.pointCount == 0) getString(R.string.ride_no_points_short) else getString(R.string.ride_view_details) + "  ›", 13f, R.color.accent_blue).apply {
            setPadding(0, dp(12), 0, 0)
        })
        return com.google.android.material.card.MaterialCardView(context).apply {
            radius = dp(16).toFloat(); cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_dark))
            addView(content)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            isClickable = true; isFocusable = true
            setOnClickListener { action() }
        }
    }

    private fun renderList() {
        selectedRideId = null
        contentJob?.cancel()
        clearMap()
        rows.removeAllViews()
        rows.addView(button("‹ ${getString(R.string.ride_title)}") { finish() })
        rows.addView(button(getString(if (repository.activeId.value == null) R.string.ride_start else R.string.ride_stop)) { toggleRide() })
        rows.addView(TextView(this).apply { text = getString(R.string.ride_notification); textSize = 13f; setPadding(dp(8), dp(8), dp(8), dp(8)) })
        contentJob = lifecycleScope.launch {
            val rides = withContext(Dispatchers.IO) {
                repository.summaries().sortedByDescending { it.started }
            }
            if (rides.isEmpty()) rows.addView(TextView(this@RideHistoryActivity).apply { text = getString(R.string.ride_empty) })
            rides.forEach { ride ->
                rows.addView(rideCard(ride) { showRide(ride.id) })
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
        selectedRideId = id
        contentJob?.cancel()
        contentJob = lifecycleScope.launch {
            val ride = withContext(Dispatchers.IO) { repository.read(id) } ?: return@launch
            clearMap()
            rows.removeAllViews()
            rows.addView(button("‹ ${getString(R.string.ride_title)}") { renderList() })
            rows.addView(TextView(this@RideHistoryActivity).apply {
                text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ride.started))
                textSize = 14f; setPadding(dp(16), dp(8), dp(16), dp(4))
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            })
            addRideStatistics(ride)
            rows.addView(TextView(this@RideHistoryActivity).apply { text = summary(ride); textSize = 14f; setPadding(dp(16), dp(16), dp(16), dp(16)) })
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
    private fun addRideStatistics(ride: Ride) {
        val end = ride.ended ?: ride.points.lastOrNull()?.time ?: ride.started
        val duration = (end - ride.started).coerceAtLeast(0L)
        val average = ride.averageSpeedKmh?.let { "%.1f km/h".format(it) } ?: "--"
        rows.addView(TextView(this).apply {
            text = getString(R.string.ride_stats_title)
            textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(20), dp(16), dp(8))
        })
        val metrics = listOf(
            getString(R.string.ride_total_duration) to "%02d:%02d:%02d".format(duration / 3600000, duration / 60000 % 60, duration / 1000 % 60),
            getString(R.string.ride_total_distance) to "%.2f km".format(ride.distanceMeters / 1000),
            getString(R.string.ride_average_speed) to average,
            getString(R.string.ride_maximum_speed) to (ride.maxSpeed?.let { "$it km/h" } ?: "--")
        )
        metrics.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            pair.forEach { (label, value) ->
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(8), dp(12))
                    addView(TextView(this@RideHistoryActivity).apply { text = label; textSize = 12f })
                    addView(TextView(this@RideHistoryActivity).apply {
                        text = value; textSize = 21f; setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            rows.addView(row)
        }
        rows.addView(TextView(this).apply {
            text = getString(R.string.ride_speed_chart); textSize = 16f
            setPadding(dp(16), dp(16), dp(16), dp(8))
        })
        val speeds = ride.points.filter { it.speed != null }
        if (speeds.size < 2) {
            rows.addView(TextView(this).apply { setText(R.string.ride_chart_empty); setPadding(dp(16), dp(12), dp(16), dp(12)) })
            return
        }
        val description = metrics.joinToString("; ") { "${it.first}: ${it.second}" }
        rows.addView(object : View(this) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            init { contentDescription = getString(R.string.ride_speed_chart) + ": " + description }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val left = dp(42).toFloat(); val top = dp(20).toFloat()
                val right = width - dp(16).toFloat(); val bottom = height - dp(30).toFloat()
                val maxSpeed = maxOf(10, ((ride.maxSpeed ?: 0) / 10 + 1) * 10)
                val first = ride.points.first()
                val origin = first.elapsed - (first.time - ride.started).coerceAtLeast(0L)
                val span = maxOf(duration, ride.points.last().elapsed - origin, 1L)
                paint.textSize = dp(11).toFloat(); paint.strokeWidth = dp(1).toFloat()
                for (step in 0..4) {
                    val y = bottom - (bottom - top) * step / 4
                    paint.color = ContextCompat.getColor(context, R.color.text_secondary); paint.alpha = 45
                    canvas.drawLine(left, y, right, y, paint)
                    paint.alpha = 255
                    canvas.drawText((maxSpeed * step / 4).toString(), dp(4).toFloat(), y + dp(4), paint)
                }
                for (step in 0..2) {
                    val x = left + (right - left) * step / 2
                    canvas.drawText("%.1f".format(span * step / 2.0 / 60000), x - dp(8), bottom + dp(22), paint)
                }
                paint.color = ContextCompat.getColor(context, R.color.accent_blue)
                paint.strokeWidth = dp(2).toFloat()
                var previous: RidePoint? = null
                ride.points.forEach { point ->
                    val a = previous
                    if (a != null && a.speed != null && point.speed != null && point.elapsed - a.elapsed in 1..Ride.MAX_GAP_MS) {
                        fun x(p: RidePoint) = left + (right - left) * (p.elapsed - origin).toFloat() / span
                        fun y(p: RidePoint) = bottom - (bottom - top) * (p.speed ?: 0).toFloat() / maxSpeed
                        canvas.drawLine(x(a), y(a), x(point), y(point), paint)
                    }
                    previous = point
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(200)))
        rows.addView(TextView(this).apply {
            text = getString(R.string.ride_chart_axes) + "\n" + getString(R.string.ride_stats_coverage_hint)
            textSize = 12f; setPadding(dp(16), dp(8), dp(16), dp(16))
        })
    }
    private fun clearMap() {
        routeFragment?.let { supportFragmentManager.beginTransaction().remove(it).commitNow() }
        routeFragment = null
    }
}
