package com.aladdin.dashcam.media

import android.content.Context
import android.media.MediaMetadataRetriever
import com.aladdin.dashcam.gps.Ride
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exports actual overlapping local recordings; missing coverage is declared in the manifest. */
object EventPackage {
    suspend fun create(context: Context, source: File, eventTime: Long, beforeMs: Long, afterMs: Long,
                       ride: Ride?, snapshot: File?, gpsOffsetMs: Long = 0L, progress: (Int, Int) -> Unit): File = withContext(Dispatchers.IO) {
        require(eventTime > 0 && beforeMs >= 0 && afterMs > 0)
        val directory = File(context.filesDir, "events").apply { mkdirs() }
        val work = File(directory, UUID.randomUUID().toString()).apply { mkdirs() }
        val zip = File(directory, "event_${eventTime}_${work.name.take(8)}.zip")
        val partial = File(directory, zip.name + ".partial")
        val from = eventTime - beforeMs; val until = eventTime + afterMs
        data class Segment(val file: File, val started: Long, val duration: Long)
        try {
            val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { isLenient = false }
            val segments = source.parentFile?.listFiles().orEmpty().filter { it.extension.lowercase() in setOf("mov", "mp4") }
                .mapNotNull { file ->
                    currentCoroutineContext().ensureActive()
                    val timestamp = Regex("^(\\d{14})_").find(file.name)?.groupValues?.get(1)?.let { runCatching { format.parse(it)?.time }.getOrNull() }
                        ?: return@mapNotNull null
                    // Ignore unrelated files before opening metadata.
                    if (timestamp >= until) return@mapNotNull null
                    val metadata = MediaMetadataRetriever()
                    val duration = try { metadata.setDataSource(file.absolutePath); metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
                    catch (_: Exception) { null } finally { metadata.release() }
                    if (duration != null && duration > 0 && timestamp + duration > from) Segment(file, timestamp, duration) else null
                }.sortedBy { it.started }
            check(segments.isNotEmpty()) { context.getString(com.aladdin.dashcam.R.string.event_no_media) }
            val entries = JSONArray(); val gaps = JSONArray(); var coveredUntil = from
            segments.forEachIndexed { index, segment ->
                ensureActive()
                val start = maxOf(from, segment.started); val end = minOf(until, segment.started + segment.duration)
                if (start > coveredUntil) gaps.put(JSONObject().put("from", coveredUntil).put("until", start))
                coveredUntil = maxOf(coveredUntil, end)
                val clip = File(work, "clip_${index + 1}.mp4")
                VideoExporter().export(context, segment.file, clip, start - segment.started, end - segment.started,
                    null, segment.started, progress = {})
                entries.put(JSONObject().put("source", segment.file.name).put("clip", clip.name).put("from", start).put("until", end))
                progress(index + 1, segments.size)
            }
            if (coveredUntil < until) gaps.put(JSONObject().put("from", coveredUntil).put("until", until))
            File(work, "event.json").writeText(JSONObject().put("eventTime", eventTime).put("requestedFrom", from)
                .put("requestedUntil", until).put("gpsOffsetMs", gpsOffsetMs).put("clips", entries).put("missingIntervals", gaps).toString(2))
            ride?.let { original ->
                val points = original.points.filter { it.time in (from + gpsOffsetMs)..(until + gpsOffsetMs) }
                if (points.isNotEmpty()) File(work, "route.gpx").writeText(original.copy(points = points).gpx())
            }
            snapshot?.takeIf { it.isFile }?.copyTo(File(work, "snapshot.jpg"))
            ZipOutputStream(partial.outputStream().buffered()).use { out ->
                work.listFiles().orEmpty().forEach { file ->
                    ensureActive(); out.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(out) }; out.closeEntry()
                }
            }
            check(partial.renameTo(zip)) { "Cannot finalize event package" }
            zip
        } finally {
            partial.delete()
            work.listFiles().orEmpty().forEach { it.delete() }; work.delete()
        }
    }
}
