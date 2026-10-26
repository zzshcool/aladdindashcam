package com.aladdin.dashcam.gps

/** Arrival is an app estimate, not a Google Maps arrival callback. */
class RideArrivalDetector(val latitude: Double, val longitude: Double) {
    companion object {
        const val RADIUS_METERS = 80.0
        const val DWELL_MS = 20000L
        const val MAX_FIX_AGE_MS = 3500L
        const val MAX_ACCURACY_METERS = 25f
        const val MAX_SPEED_KMH = 5
    }
    private var approached = false
    private var announced = false
    private var enteredAt: Long? = null
    private var lastFix = 0L

    init { require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0) }

    fun observe(status: GpsStatus, nowElapsed: Long): Boolean {
        val lat = status.latitude
        val lon = status.longitude
        val accuracy = status.accuracyMeters
        val fix = status.lastUpdateElapsedRealtime
        if (!status.isLocked || status.sourceName != "手機 GPS" || lat == null || lon == null ||
            !lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0 ||
            accuracy == null || !accuracy.isFinite() || accuracy !in 0f..MAX_ACCURACY_METERS ||
            nowElapsed - fix !in 0..MAX_FIX_AGE_MS) {
            enteredAt = null
            return false
        }
        if (fix <= lastFix) return false
        if (lastFix != 0L && fix - lastFix > MAX_FIX_AGE_MS) enteredAt = null
        lastFix = fix
        val distance = Ride.distance(RidePoint(0, 0, latitude, longitude, null, null, 0f, ""),
            RidePoint(0, 0, lat, lon, null, null, 0f, ""))
        if (distance - accuracy > RADIUS_METERS) {
            approached = true; announced = false; enteredAt = null
            return false
        }
        if (!approached || announced || distance + accuracy > RADIUS_METERS ||
            status.speedKmh == null || status.speedKmh !in 0..MAX_SPEED_KMH) {
            enteredAt = null
            return false
        }
        val entered = enteredAt ?: fix.also { enteredAt = it }
        if (fix - entered < DWELL_MS) return false
        announced = true
        return true
    }
}
