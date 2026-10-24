package com.aladdin.dashcam.media

import android.content.Context
import android.util.AtomicFile
import com.aladdin.dashcam.model.MediaItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class DownloadStatus { PENDING, DOWNLOADING, COMPLETED, FAILED, CANCELLED }
data class DownloadTask(
    val id: String,
    val mediaItem: MediaItem,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val bytesRead: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null,
    val localFile: File? = null
)

class DownloadRepository(context: Context, private val scope: CoroutineScope) {
    private val store = AtomicFile(File(context.filesDir, "download_tasks.json"))
    val tasks = MutableStateFlow(load())
    val workCount = MutableStateFlow(0)
    val generations = ConcurrentHashMap<String, Long>()
    private val counter = AtomicLong()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val queue = Mutex()
    @Volatile var activeKey: String? = null; private set
    init { scope.launch(Dispatchers.IO) {
        var saved = ""
        tasks.collect { list ->
            val signature = list.joinToString { "${it.id}:${it.status}:${it.localFile}" }
            if (signature != saved) {
                runCatching { persist(list) }.onSuccess { saved = signature }
                    .onFailure { Diagnostics.record("download.persist", it.javaClass.simpleName) }
            }
        }
    } }

    @Synchronized fun enqueue(item: MediaItem, resolve: suspend (MediaItem) -> MediaItem?, retryWhen: () -> Boolean,
                              transfer: suspend (MediaItem, Long) -> Result<File>): Job {
        jobs[item.storageKey]?.takeIf { it.isActive }?.let { return it }
        val generation = counter.incrementAndGet()
        generations[item.storageKey] = generation
        tasks.update { list -> list.filterNot { it.id == item.storageKey } + DownloadTask(item.storageKey, item, totalBytes = item.sizeBytes) }
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                while (isActive) {
                    if (resolve(item) == null) { delay(2500); continue }
                    val finished = queue.withLock {
                        val current = resolve(item) ?: return@withLock false
                        ensureActive()
                        activeKey = item.storageKey
                        try {
                            val result = transfer(current, generation)
                            if (result.isFailure && retryWhen() && generations[item.storageKey] == generation) {
                                tasks.update { list -> list.map { if (it.id == item.storageKey) it.copy(status = DownloadStatus.PENDING, bytesRead = 0L) else it } }
                                false
                            } else {
                                if (result.isFailure && generations[item.storageKey] == generation) {
                                    tasks.update { list -> list.map { if (it.id == item.storageKey) it.copy(status = DownloadStatus.FAILED, errorMessage = result.exceptionOrNull()?.message) else it } }
                                }
                                true
                            }
                        } finally { activeKey = null }
                    }
                    if (finished) break
                    delay(2500)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generations[item.storageKey] == generation) tasks.update { list -> list.map {
                    if (it.id == item.storageKey) it.copy(status = DownloadStatus.FAILED, errorMessage = error.message) else it
                } }
            }
        }
        jobs[item.storageKey] = job
        workCount.update { it + 1 }
        job.invokeOnCompletion { jobs.remove(item.storageKey, job); workCount.update { it - 1 } }
        job.start()
        return job
    }
    fun cancel(task: DownloadTask, abort: () -> Unit) {
        generations[task.id] = counter.incrementAndGet()
        jobs[task.id]?.cancel()
        if (activeKey == task.id) abort()
        tasks.update { list -> list.map { if (it.id == task.id) it.copy(status = DownloadStatus.CANCELLED) else it } }
    }
    fun pauseAll(abort: () -> Unit) {
        tasks.value.filter { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING }.forEach { cancel(it, abort) }
    }
    private fun load(): List<DownloadTask> = runCatching {
        val array = JSONArray(store.openRead().bufferedReader().use { it.readText() })
        (0 until array.length()).map { index ->
            val j = array.getJSONObject(index)
            val item = MediaItem(j.getString("id"), j.getString("name"), j.getLong("size"), j.optInt("duration"),
                j.getLong("time"), j.getBoolean("video"), j.getBoolean("emergency"),
                sourceDeviceId = j.getString("device"), sourceEpoch = 0L)
            val status = DownloadStatus.valueOf(j.getString("status"))
            val path = j.optString("path").takeIf { it.isNotBlank() }?.let(::File)
            DownloadTask(item.storageKey, item, if (status == DownloadStatus.DOWNLOADING) DownloadStatus.PENDING else status,
                totalBytes = item.sizeBytes, localFile = path)
        }
    }.getOrDefault(emptyList())
    private fun persist(list: List<DownloadTask>) {
        val array = JSONArray()
        list.forEach { task -> val m = task.mediaItem; array.put(JSONObject().put("id", m.id).put("name", m.fileName)
            .put("size", m.sizeBytes).put("duration", m.durationSeconds).put("time", m.timestampMillis)
            .put("video", m.isVideo).put("emergency", m.isEmergency).put("device", m.sourceDeviceId)
            .put("status", task.status.name).put("path", task.localFile?.absolutePath.orEmpty())) }
        val output = store.startWrite()
        try { output.write(array.toString().toByteArray()); store.finishWrite(output) }
        catch (error: Exception) { store.failWrite(output); Diagnostics.record("download.persist", error.javaClass.simpleName) }
    }
}
