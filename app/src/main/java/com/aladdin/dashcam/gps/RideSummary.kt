package com.aladdin.dashcam.gps

import org.json.JSONObject

data class RideSummary(val id: String, val started: Long, val ended: Long?, val distance: Double,
    val maxSpeed: Int?, val ascent: Double?, val pointCount: Int, val movingMs: Long,
    val stops: Int, val coveragePercent: Int, val meanAccuracy: Double?, val interrupted: Boolean) {
    fun json(): JSONObject = JSONObject().put("id", id).put("started", started).put("ended", ended ?: JSONObject.NULL)
        .put("distance", distance).put("speed", maxSpeed ?: JSONObject.NULL).put("ascent", ascent ?: JSONObject.NULL)
        .put("points", pointCount).put("moving", movingMs).put("stops", stops).put("coverage", coveragePercent)
        .put("accuracy", meanAccuracy ?: JSONObject.NULL).put("interrupted", interrupted)
    companion object {
        fun from(ride: Ride): RideSummary {
            var moving = 0L; var covered = 0L; var stopped = 0L; var stops = 0
            for (i in 1 until ride.points.size) {
                val previous = ride.points[i - 1]; val current = ride.points[i]
                val dt = current.elapsed - previous.elapsed
                if (dt in 1..Ride.MAX_GAP_MS) {
                    covered += dt
                    if (previous.speed != null && previous.speed > 3) { moving += dt; if (stopped >= 30_000) stops++; stopped = 0 }
                    else if (previous.speed != null) stopped += dt
                    else { if (stopped >= 30_000) stops++; stopped = 0 }
                } else { if (stopped >= 30_000) stops++; stopped = 0 }
            }
            if (stopped >= 30_000) stops++
            val end = ride.ended ?: ride.points.lastOrNull()?.time ?: ride.started
            val total = (end - ride.started).coerceAtLeast(1)
            return RideSummary(ride.id, ride.started, ride.ended, ride.distanceMeters, ride.maxSpeed,
                ride.ascentMeters, ride.points.size, moving, stops, (covered * 100 / total).coerceIn(0, 100).toInt(),
                ride.points.takeIf { it.isNotEmpty() }?.map { it.accuracy.toDouble() }?.average(), ride.interrupted)
        }
        fun read(j: JSONObject) = RideSummary(j.getString("id"), j.getLong("started"), if (j.isNull("ended")) null else j.getLong("ended"),
            j.getDouble("distance"), if (j.isNull("speed")) null else j.getInt("speed"), if (j.isNull("ascent")) null else j.getDouble("ascent"),
            j.getInt("points"), j.getLong("moving"), j.getInt("stops"), j.getInt("coverage"),
            if (j.isNull("accuracy")) null else j.getDouble("accuracy"), j.getBoolean("interrupted"))
    }
}
