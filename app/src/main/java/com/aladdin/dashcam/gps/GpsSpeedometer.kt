package com.aladdin.dashcam.gps

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val speedKmh: Int? = null
)

data class GpsStatus(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val speedKmh: Int? = null, // null means Unknown/Searching, rendered as "--"
    val isLocked: Boolean = false,
    val hasPermission: Boolean = false,
    val altitudeMeters: Double? = null,
    val accumulatedAscentMeters: Double = 0.0,
    val bearingDegrees: Float? = null,
    val accelerationG: Float? = null,
    val lastUpdateElapsedRealtime: Long = 0L,
    val sourceName: String = "手機 GPS",
    val trajectory: List<LocationPoint> = emptyList()
)

class GpsSpeedometer(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : LocationListener, SensorEventListener {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val _gpsStatus = MutableStateFlow(GpsStatus())
    val gpsStatus: StateFlow<GpsStatus> = _gpsStatus.asStateFlow()

    private var watchdogJob: Job? = null
    private val GPS_TTL_MILLIS = 3500L // 3.5 seconds TTL before declaring speed stale

    private var previousAltitude: Double? = null
    private var totalAscentMeters: Double = 0.0
    private var currentGForce: Float? = null
    private val trajectoryPoints = mutableListOf<LocationPoint>()

    fun resetTrip() {
        totalAscentMeters = 0.0
        previousAltitude = null
        synchronized(trajectoryPoints) {
            trajectoryPoints.clear()
        }
        _gpsStatus.value = _gpsStatus.value.copy(
            accumulatedAscentMeters = 0.0,
            trajectory = emptyList()
        )
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        try {
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) {
                _gpsStatus.value = GpsStatus(hasPermission = false)
                return
            }
            // Register accelerometer for ride G-force measurement
            val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            accelerometer?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }

            val providers = selectLocationProviders(fine, coarse, locationManager.getProviders(true))
            if (providers.isEmpty()) {
                _gpsStatus.value = GpsStatus(
                    speedKmh = null,
                    isLocked = false,
                    hasPermission = true,
                    accelerationG = currentGForce
                )
                return
            }

            locationManager.removeUpdates(this)
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(provider, 1000L, 0f, this)
            }
            _gpsStatus.value = _gpsStatus.value.copy(hasPermission = true)
            startWatchdog()
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing: ${e.message}")
            _gpsStatus.value = GpsStatus(speedKmh = null, isLocked = false, hasPermission = false)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting GPS speedometer", e)
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(1000)
                val current = _gpsStatus.value
                if (current.isLocked) {
                    val age = SystemClock.elapsedRealtime() - current.lastUpdateElapsedRealtime
                    if (age > GPS_TTL_MILLIS) {
                        Log.i(TAG, "GPS fix expired after ${age}ms, marking speed unknown")
                        _gpsStatus.value = current.copy(
                            speedKmh = null,
                            isLocked = false
                        )
                    }
                }
            }
        }
    }

    fun stopListening() {
        try {
            watchdogJob?.cancel()
            watchdogJob = null
            locationManager.removeUpdates(this)
            sensorManager?.unregisterListener(this)
            _gpsStatus.value = GpsStatus(speedKmh = null, isLocked = false, hasPermission = false)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping GPS updates", e)
        }
    }

    override fun onLocationChanged(location: Location) {
        val now = SystemClock.elapsedRealtime()
        val speedKmh = if (location.hasSpeed()) {
            (location.speed * 3.6f).toInt().coerceAtLeast(0)
        } else if (location.hasAccuracy() && location.accuracy <= 20f) {
            0
        } else {
            null
        }

        val altitude = if (location.hasAltitude()) location.altitude else null
        if (altitude != null) {
            previousAltitude?.let { prev ->
                val diff = altitude - prev
                if (diff > 1.0) { // filter out minor barometric/GPS jitter under 1 meter
                    totalAscentMeters += diff
                }
            }
            previousAltitude = altitude
        }

        val bearing = if (location.hasBearing()) location.bearing else null
        val lat = location.latitude
        val lng = location.longitude
        val accuracy = if (location.hasAccuracy()) location.accuracy else null

        val currentPoints = synchronized(trajectoryPoints) {
            val last = trajectoryPoints.lastOrNull()
            if (last == null || (Math.abs(last.latitude - lat) > 0.00002 || Math.abs(last.longitude - lng) > 0.00002)) {
                trajectoryPoints.add(LocationPoint(lat, lng, System.currentTimeMillis(), speedKmh))
                if (trajectoryPoints.size > 1000) {
                    trajectoryPoints.removeAt(0)
                }
            }
            trajectoryPoints.toList()
        }

        _gpsStatus.value = GpsStatus(
            latitude = lat,
            longitude = lng,
            accuracyMeters = accuracy,
            speedKmh = speedKmh,
            isLocked = true,
            hasPermission = true,
            altitudeMeters = altitude,
            accumulatedAscentMeters = totalAscentMeters,
            bearingDegrees = bearing,
            accelerationG = currentGForce,
            lastUpdateElapsedRealtime = now,
            sourceName = if (location.provider == LocationManager.GPS_PROVIDER) "手機 GPS" else "手機網路定位",
            trajectory = currentPoints
        )
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            val magnitude = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
            val g = magnitude / 9.80665f
            currentGForce = (Math.round(g * 100f) / 100f) // Round to 2 decimal places
            _gpsStatus.value = _gpsStatus.value.copy(accelerationG = currentGForce)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {
        _gpsStatus.value = GpsStatus(speedKmh = null, isLocked = false, hasPermission = true)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    companion object {
        private const val TAG = "GpsSpeedometer"
        internal fun selectLocationProviders(fine: Boolean, coarse: Boolean, enabled: List<String>): List<String> =
            enabled.filter {
                (it == LocationManager.GPS_PROVIDER && fine) ||
                    (it == LocationManager.NETWORK_PROVIDER && (fine || coarse))
            }
    }
}
