package com.aladdin.dashcam.gps

import android.annotation.SuppressLint
import android.content.Context
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

data class GpsStatus(
    val speedKmh: Int? = null, // null means Unknown/Searching, rendered as "--"
    val isLocked: Boolean = false,
    val hasPermission: Boolean = false,
    val lastUpdateElapsedRealtime: Long = 0L
)

class GpsSpeedometer(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : LocationListener {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _gpsStatus = MutableStateFlow(GpsStatus())
    val gpsStatus: StateFlow<GpsStatus> = _gpsStatus.asStateFlow()

    private var watchdogJob: Job? = null
    private val GPS_TTL_MILLIS = 3500L // 3.5 seconds TTL before declaring speed stale

    @SuppressLint("MissingPermission")
    fun startListening() {
        try {
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if (!isGpsEnabled) {
                _gpsStatus.value = GpsStatus(speedKmh = null, isLocked = false, hasPermission = true)
                return
            }

            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                this
            )
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
            _gpsStatus.value = GpsStatus(speedKmh = null, isLocked = false, hasPermission = false)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping GPS updates", e)
        }
    }

    override fun onLocationChanged(location: Location) {
        val now = SystemClock.elapsedRealtime()
        val speedKmh = if (location.hasSpeed()) {
            (location.speed * 3.6f).toInt().coerceAtLeast(0)
        } else {
            null
        }

        _gpsStatus.value = GpsStatus(
            speedKmh = speedKmh,
            isLocked = location.hasSpeed(),
            hasPermission = true,
            lastUpdateElapsedRealtime = now
        )
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {
        _gpsStatus.value = GpsStatus(speedKmh = null, isLocked = false, hasPermission = true)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    companion object {
        private const val TAG = "GpsSpeedometer"
    }
}
