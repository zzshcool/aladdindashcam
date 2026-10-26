package com.aladdin.dashcam.gps

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.*

data class RidePoint(val time: Long, val elapsed: Long, val lat: Double, val lon: Double,
                     val speed: Int?, val altitude: Double?, val accuracy: Float, val source: String) {
    fun json() = JSONObject().put("time", time).put("elapsed", elapsed).put("lat", lat).put("lon", lon)
        .put("speed", speed ?: JSONObject.NULL).put("altitude", altitude ?: JSONObject.NULL)
        .put("accuracy", accuracy.toDouble()).put("source", source)
    companion object {
        fun from(j: JSONObject) = RidePoint(j.getLong("time"), j.getLong("elapsed"), j.getDouble("lat"),
            j.getDouble("lon"), if (j.isNull("speed")) null else j.getInt("speed"),
            if (j.isNull("altitude")) null else j.getDouble("altitude"), j.getDouble("accuracy").toFloat(), j.getString("source"))
    }
}

data class Ride(val id: String, val started: Long, val ended: Long?, val points: List<RidePoint>, val interrupted: Boolean = false) {
    val distanceMeters: Double get() {
        var anchor = points.firstOrNull() ?: return 0.0
        var previous = anchor
        var total = 0.0
        points.drop(1).forEach { point ->
            if (point.elapsed - previous.elapsed !in 1..MAX_GAP_MS) anchor = point
            else {
                val step = distance(anchor, point)
                if (step >= maxOf(anchor.accuracy, point.accuracy)) { total += step; anchor = point }
            }
            previous = point
        }
        return total
    }
    val maxSpeed: Int? get() = points.mapNotNull { it.speed }.maxOrNull()
    /** Time-weighted km/h over consecutive located intervals; GPS gaps stay unknown. */
    val averageSpeedKmh: Double? get() {
        var observedMs = 0L
        var speedIntegral = 0.0
        points.zipWithNext().forEach { (a, b) ->
            val interval = b.elapsed - a.elapsed
            if (interval in 1..MAX_GAP_MS && a.speed != null && b.speed != null) {
                observedMs += interval
                speedIntegral += (a.speed + b.speed) / 2.0 * interval
            }
        }
        return if (observedMs > 0) speedIntegral / observedMs else null
    }
    val ascentMeters: Double? get() {
        if (points.none { it.altitude != null }) return null
        var base: Double? = null
        var ascent = 0.0
        points.forEach { p -> p.altitude?.let { altitude ->
            val previous = base
            if (previous == null || abs(altitude - previous) >= 3) {
                if (previous != null) ascent += (altitude - previous).coerceAtLeast(0.0)
                base = altitude
            }
        } }
        return ascent
    }
    /** A stale or missing fix stays unknown; never extrapolate speed through a GPS gap. */
    fun pointAt(time: Long): RidePoint? {
        val index = points.binarySearchBy(time) { it.time }.let { if (it >= 0) it else -it - 2 }
        return points.getOrNull(index)?.takeIf { time - it.time in 0..3500 }
    }
    fun gpx(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"AladdinDashCam\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:ad=\"https://aladdin.dashcam/ride/1\"><trk><name>Ride ")
        append(Instant.ofEpochMilli(started)); append("</name><trkseg>\n")
        points.forEachIndexed { index, p ->
            if (index > 0 && p.elapsed - points[index - 1].elapsed > MAX_GAP_MS) append("</trkseg><trkseg>\n")
            append("<trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append("\">")
            p.altitude?.let { append("<ele>").append(it).append("</ele>") }
            append("<time>").append(Instant.ofEpochMilli(p.time)).append("</time><extensions><ad:accuracy>")
                .append(p.accuracy).append("</ad:accuracy>")
            p.speed?.let { append("<ad:speed>").append(it / 3.6).append("</ad:speed>") }
            append("</extensions></trkpt>\n")
        }
        append("</trkseg></trk></gpx>")
    }
    companion object {
        const val MAX_GAP_MS = 10_000L
        fun distance(a: RidePoint, b: RidePoint): Double {
            val lat = Math.toRadians(b.lat - a.lat)
            val lon = Math.toRadians(b.lon - a.lon)
            val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(lon / 2).pow(2)
            return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}

class RideRepository private constructor(context: Context) {
    private val dir = File(context.filesDir, "rides").apply { mkdirs() }
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId = _activeId.asStateFlow()
    private var lastElapsed = 0L
    private var lastTime = 0L

    @Synchronized fun start(): String {
        _activeId.value?.let { return it }
        val id = UUID.randomUUID().toString()
        File(dir, "$id.jsonl").writeText(JSONObject().put("id", id).put("started", System.currentTimeMillis()).toString() + "\n")
        lastElapsed = 0; lastTime = 0
        _activeId.value = id
        return id
    }

    @Synchronized fun append(status: GpsStatus) {
        val id = _activeId.value ?: return
        val lat = status.latitude ?: return
        val lon = status.longitude ?: return
        val accuracy = status.accuracyMeters ?: return
        if (!status.isLocked || accuracy > 50 || !accuracy.isFinite() || accuracy < 0 ||
            !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            status.lastUpdateElapsedRealtime <= lastElapsed || status.fixTimestampMillis <= lastTime) return
        val point = RidePoint(status.fixTimestampMillis, status.lastUpdateElapsedRealtime, lat, lon,
            status.speedKmh, status.altitudeMeters?.takeIf { altitude -> altitude.isFinite() &&
                status.verticalAccuracyMeters?.let { it.isFinite() && it in 0f..10f } == true }, accuracy, status.sourceName)
        File(dir, "$id.jsonl").appendText(point.json().toString() + "\n")
        lastElapsed = point.elapsed; lastTime = point.time
    }

    @Synchronized fun stop() {
        val id = _activeId.value ?: return
        try { File(dir, "$id.jsonl").appendText(JSONObject().put("ended", System.currentTimeMillis()).toString() + "\n") }
        finally { _activeId.value = null }
    }

    @Synchronized fun summaries(): List<RideSummary> =
        dir.listFiles { file -> file.extension == "jsonl" }.orEmpty().mapNotNull { file ->
            val cache = File(dir, file.nameWithoutExtension + ".summary")
            val cached = runCatching { JSONObject(cache.readText()) }.getOrNull()
            if (cached != null && cached.optLong("sourceBytes", -1) == file.length() &&
                cached.optLong("sourceModified", -1) == file.lastModified() &&
                file.nameWithoutExtension != _activeId.value) runCatching { RideSummary.read(cached) }.getOrNull()
            else read(file.nameWithoutExtension)?.let { ride ->
                val summary = RideSummary.from(ride)
                if (ride.ended != null || ride.interrupted) {
                    val atomic = android.util.AtomicFile(cache)
                    val output = atomic.startWrite()
                    try {
                        output.write(summary.json().put("sourceBytes", file.length()).put("sourceModified", file.lastModified()).toString().toByteArray())
                        atomic.finishWrite(output)
                    } catch (error: Exception) { atomic.failWrite(output) }
                }
                summary
            }
        }.sortedByDescending { it.started }

    @Synchronized fun list(): List<Ride> = dir.listFiles { f -> f.extension == "jsonl" }.orEmpty()
        .mapNotNull { read(it.nameWithoutExtension) }.sortedByDescending { it.started }

    @Synchronized fun read(id: String): Ride? {
        if (!id.matches(Regex("[a-f0-9-]{36}"))) return null
        val file = File(dir, "$id.jsonl")
        if (!file.isFile) return null
        val lines = file.useLines { it.mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }.toList() }
        val header = lines.firstOrNull() ?: return null
        val ended = lines.lastOrNull()?.takeIf { it.has("ended") }?.getLong("ended")
        return Ride(id, header.getLong("started"), ended,
            lines.drop(1).filter { it.has("lat") }.mapNotNull { runCatching { RidePoint.from(it) }.getOrNull() },
            ended == null && _activeId.value != id)
    }

    companion object {
        @Volatile private var instance: RideRepository? = null
        fun get(context: Context): RideRepository = instance ?: synchronized(this) {
            instance ?: RideRepository(context.applicationContext).also { instance = it }
        }
    }
}
