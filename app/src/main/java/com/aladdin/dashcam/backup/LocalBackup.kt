package com.aladdin.dashcam.backup

import android.content.Context
import android.util.AtomicFile
import androidx.appcompat.app.AppCompatDelegate
import com.aladdin.dashcam.gps.RidePoint
import com.aladdin.dashcam.gps.RideRepository
import com.aladdin.dashcam.maintenance.MaintenanceRecord
import com.aladdin.dashcam.maintenance.MaintenanceRepository
import com.aladdin.dashcam.ui.AppAppearance
import com.aladdin.dashcam.ui.LaunchIntroView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object LocalBackup {
    private const val FORMAT = "AladdinDashCamBackup"
    private const val VERSION = 2
    private const val MAX_BYTES = 256L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    private const val MAX_RIDES = 10000
    private val rideName = Regex("rides/([a-f0-9-]{36})\\.jsonl")
    data class Preview(val directory: File, val records: List<MaintenanceRecord>, val rides: List<File>,
                       val settings: JSONObject, val createdAt: Long, val plates: List<String> = emptyList()) {
        fun discard() { directory.deleteRecursively() }
    }
    data class Imported(val maintenance: Int, val rides: Int, val skipped: Int, val plates: Int = 0)
    private fun maintenance(context: Context) = MaintenanceRepository(File(context.filesDir, "maintenance.json"))
    private fun requireIdle(context: Context) { check(RideRepository.get(context).activeId.value == null) { "ride_active" } }
    private fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun settings(context: Context): JSONObject {
        val nav = context.getSharedPreferences("ride_navigation", Context.MODE_PRIVATE)
        return JSONObject().put("theme", AppAppearance.nightMode(context))
            .put("language", AppCompatDelegate.getApplicationLocales().toLanguageTags())
            .put("repeatIntro", LaunchIntroView.repeats(context))
            .put("navigation", JSONObject().apply {
                for (key in listOf("company", "home", "last_destination")) put(key, nav.getString(key, ""))
            })
    }
    fun export(context: Context): File {
        requireIdle(context)
        val folder = File(context.cacheDir, "local_backups").apply { mkdirs() }
        val output = File(folder, "backup_${UUID.randomUUID()}.zip")
        try {
            val maintenance = maintenance(context).snapshot()
            val records = maintenance.records
            val rides = File(context.filesDir, "rides").listFiles { f -> f.extension == "jsonl" }.orEmpty().toList()
            val manifest = JSONObject().put("format", FORMAT).put("version", VERSION)
                .put("createdAt", System.currentTimeMillis()).put("settings", settings(context))
                .put("vehicles", JSONArray(maintenance.plates))
                .put("maintenance", JSONArray().apply { records.forEach { put(it.json()) } })
                .put("rides", JSONArray().apply { rides.forEach { file ->
                    validateRide(file)
                    put(JSONObject().put("name", "rides/${file.name}").put("sha256", checksum(file)))
                } })
            val metadata = manifest.toString().toByteArray(Charsets.UTF_8)
            require(rides.size <= MAX_RIDES && metadata.size <= MAX_ENTRY_BYTES && rides.all { it.length() <= MAX_ENTRY_BYTES })
            require(metadata.size + rides.sumOf { it.length() } <= MAX_BYTES)
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(metadata); zip.closeEntry()
                for (file in rides) {
                    requireIdle(context)
                    zip.putNextEntry(ZipEntry("rides/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            requireIdle(context)
        } catch (error: Throwable) { output.delete(); throw error }
        return output
    }
    fun inspect(context: Context, input: InputStream): Preview {
        val directory = File(context.cacheDir, "local_backups/import_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val names = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && names.add(entry.name))
                    require(entry.name == "manifest.json" || rideName.matches(entry.name))
                    require(names.size <= MAX_RIDES + 1)
                    val file = File(directory, entry.name)
                    file.parentFile?.mkdirs()
                    var size = 0L
                    file.outputStream().use { out ->
                        val bytes = ByteArray(8192)
                        while (true) {
                            val n = zip.read(bytes); if (n < 0) break
                            size += n; total += n
                            require(size <= MAX_ENTRY_BYTES && total <= MAX_BYTES)
                            out.write(bytes, 0, n)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val root = JSONObject(File(directory, "manifest.json").readText())
            val version = root.get("version").toString().toInt()
            require(root.getString("format") == FORMAT && version in 1..VERSION)
            val plates = if (version == 1) emptyList() else root.getJSONArray("vehicles").let { values ->
                List(values.length()) { values.getString(it) }.also {
                    require(it.all { plate -> MaintenanceRecord.validPlate(plate) } && it.distinct().size == it.size)
                }
            }
            val data = root.getJSONArray("maintenance")
            val records = List(data.length()) {
                val record = data.getJSONObject(it)
                require(version == 1 || record.has("vehiclePlate"))
                MaintenanceRecord.from(record)
            }
            require(records.distinctBy { it.id }.size == records.size)
            require(records.all { it.vehiclePlate.isEmpty() || it.vehiclePlate in plates })
            val listed = root.getJSONArray("rides")
            val declared = mutableSetOf("manifest.json")
            val rides = List(listed.length()) { index ->
                val info = listed.getJSONObject(index); val name = info.getString("name")
                require(rideName.matches(name) && declared.add(name) && name in names)
                File(directory, name).also { require(checksum(it) == info.getString("sha256")); validateRide(it) }
            }
            require(names == declared)
            val settings = root.getJSONObject("settings")
            require(settings.getInt("theme") in setOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES))
            require(settings.getString("language") in setOf("", "zh-TW", "zh-CN", "en"))
            settings.getBoolean("repeatIntro")
            for (key in listOf("company", "home", "last_destination")) settings.getJSONObject("navigation").getString(key)
            return Preview(directory, records, rides, settings, root.get("createdAt").toString().toLong().also { require(it > 0) }, plates)
        } catch (error: Throwable) { directory.deleteRecursively(); throw error }
    }
    @Synchronized fun mergeImport(context: Context, preview: Preview): Imported {
        requireIdle(context)
        val directory = File(context.filesDir, "rides").apply { mkdirs() }
        var added = 0
        for (file in preview.rides) {
            val destination = File(directory, file.name)
            if (destination.exists()) continue
            requireIdle(context)
            val atomic = AtomicFile(destination)
            val out = atomic.startWrite()
            try { file.inputStream().use { it.copyTo(out) }; atomic.finishWrite(out); added++ }
            catch (error: Throwable) { atomic.failWrite(out); throw error }
        }
        val repository = maintenance(context)
        val existingPlates = repository.snapshot().plates.toSet()
        val newPlates = preview.plates.count { it !in existingPlates }
        val maintenance = repository.merge(preview.records, preview.plates)
        return Imported(maintenance, added, preview.records.size + preview.rides.size - maintenance - added, newPlates)
    }
    fun applyPreferences(context: Context, settings: JSONObject) {
        val nav = context.getSharedPreferences("ride_navigation", Context.MODE_PRIVATE).edit()
        for (key in listOf("company", "home", "last_destination")) nav.putString(key, settings.getJSONObject("navigation").getString(key))
        check(nav.commit())
        LaunchIntroView.setRepeats(context, settings.getBoolean("repeatIntro"))
        AppAppearance.setNightMode(context, settings.getInt("theme"))
        AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(settings.getString("language")))
    }
    private fun validateRide(file: File) {
        val id = file.name.removeSuffix(".jsonl")
        require(UUID.fromString(id).toString() == id)
        file.bufferedReader().use { reader ->
            val header = JSONObject(requireNotNull(reader.readLine()))
            val started = header.get("started").toString().toLong()
            require(header.getString("id") == id && started > 0)
            var ended = false; var elapsed = -1L; var time = -1L
            reader.forEachLine { line ->
                require(!ended)
                val json = JSONObject(line)
                if (json.has("ended")) {
                    require(json.get("ended").toString().toLong() >= started); ended = true
                } else {
                    require(json.get("time").toString().toLong() > 0)
                    require(json.get("elapsed").toString().toLong() >= 0)
                    if (!json.isNull("speed")) require(json.get("speed").toString().toInt() >= 0)
                    val point = RidePoint.from(json)
                    require(point.lat.isFinite() && point.lat in -90.0..90.0 && point.lon.isFinite() && point.lon in -180.0..180.0)
                    require(point.accuracy.isFinite() && point.accuracy >= 0 && (point.speed == null || point.speed >= 0))
                    require(point.elapsed > elapsed && point.time > time)
                    elapsed = point.elapsed; time = point.time
                }
            }
        }
    }
}
