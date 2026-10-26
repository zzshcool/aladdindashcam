package com.aladdin.dashcam.maintenance

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID
import java.util.Locale

data class MaintenanceRecord(val id: String, val date: LocalDate, val odometerKm: Long,
                             val costMinor: Long, val description: String, val vehiclePlate: String = "") {
    init {
        require(UUID.fromString(id).toString() == id)
        require(odometerKm >= 0 && costMinor >= 0 && description.isNotBlank())
        require(vehiclePlate.isEmpty() || validPlate(vehiclePlate))
    }
    val cost: BigDecimal get() = BigDecimal.valueOf(costMinor, 2)
    fun json() = JSONObject().put("id", id).put("date", date.toString()).put("odometerKm", odometerKm)
        .put("costMinor", costMinor).put("description", description).put("vehiclePlate", vehiclePlate)
    companion object {
        fun normalizePlate(value: String) = value.trim().replace(Regex("\\s+"), "")
            .replace('－', '-').replace('–', '-').uppercase(Locale.ROOT)
        fun validPlate(value: String) = value.isNotEmpty() && value.length <= 20 &&
            value == normalizePlate(value) && value.none { it.isISOControl() }
        fun parseCost(value: String): Long = BigDecimal(value.trim()).setScale(2, RoundingMode.UNNECESSARY)
            .movePointRight(2).longValueExact().also { require(it >= 0) }
        fun from(json: JSONObject) = MaintenanceRecord(json.getString("id"), LocalDate.parse(json.getString("date")),
            json.get("odometerKm").toString().toLong(), json.get("costMinor").toString().toLong(), json.getString("description"),
            if (json.has("vehiclePlate")) json.get("vehiclePlate") as? String ?: error("Invalid plate") else "")
    }
}

class MaintenanceRepository(private val file: File) {
    data class Snapshot(val records: List<MaintenanceRecord>, val plates: List<String>)
    private val atomic = AtomicFile(file)
    fun snapshot(): Snapshot = synchronized(lock) {
        if (!file.exists() && !File(file.path + ".bak").exists()) return@synchronized Snapshot(emptyList(), emptyList())
        val root = JSONObject(atomic.openRead().bufferedReader().use { it.readText() })
        val version = root.get("version").toString().toInt()
        require(version in 1..2)
        val plates = if (version == 1) emptyList() else root.getJSONArray("vehicles").let { array ->
            List(array.length()) { array.getString(it) }.also { values ->
                require(values.all { MaintenanceRecord.validPlate(it) } && values.distinct().size == values.size)
            }
        }
        val records = root.getJSONArray("records")
        val parsed = List(records.length()) {
            val record = records.getJSONObject(it)
            require(version == 1 || record.has("vehiclePlate"))
            MaintenanceRecord.from(record)
        }
            .also {
                require(it.map { record -> record.id }.distinct().size == it.size)
                require(it.all { record -> record.vehiclePlate.isEmpty() || record.vehiclePlate in plates })
            }
            .sortedWith(compareByDescending<MaintenanceRecord> { it.date }.thenByDescending { it.odometerKm })
        Snapshot(parsed, plates.sorted())
    }
    fun list(): List<MaintenanceRecord> = snapshot().records
    fun save(record: MaintenanceRecord) = synchronized(lock) {
        val data = snapshot()
        require(record.vehiclePlate.isEmpty() || record.vehiclePlate in data.plates)
        write(data.copy(records = data.records.filterNot { it.id == record.id } + record))
    }
    fun delete(id: String) = synchronized(lock) {
        val data = snapshot(); write(data.copy(records = data.records.filterNot { it.id == id }))
    }
    fun addPlate(plate: String) = synchronized(lock) {
        require(MaintenanceRecord.validPlate(plate))
        val data = snapshot(); require(plate !in data.plates)
        write(data.copy(plates = data.plates + plate))
    }
    fun renamePlate(old: String, new: String) = synchronized(lock) {
        require(MaintenanceRecord.validPlate(new))
        val data = snapshot(); require(old in data.plates)
        if (old == new) return@synchronized
        require(new !in data.plates)
        write(Snapshot(data.records.map { if (it.vehiclePlate == old) it.copy(vehiclePlate = new) else it },
            data.plates.map { if (it == old) new else it }))
    }
    fun deletePlate(plate: String) = synchronized(lock) {
        val data = snapshot()
        require(plate in data.plates && data.records.none { it.vehiclePlate == plate })
        write(data.copy(plates = data.plates - plate))
    }
    fun merge(records: List<MaintenanceRecord>, plates: List<String> = emptyList()): Int = synchronized(lock) {
        require(plates.all { MaintenanceRecord.validPlate(it) })
        val data = snapshot()
        val mergedPlates = (data.plates + plates).distinct()
        require(records.all { it.vehiclePlate.isEmpty() || it.vehiclePlate in mergedPlates })
        val ids = data.records.map { it.id }.toSet()
        val added = records.filter { it.id !in ids }.distinctBy { it.id }
        if (added.isNotEmpty() || mergedPlates != data.plates) write(Snapshot(data.records + added, mergedPlates))
        added.size
    }
    private fun write(data: Snapshot) {
        file.parentFile?.mkdirs()
        val json = JSONObject().put("version", 2).put("vehicles", JSONArray(data.plates.sorted()))
            .put("records", JSONArray().apply { data.records.forEach { put(it.json()) } })
        val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
    companion object { private val lock = Any() }
}
