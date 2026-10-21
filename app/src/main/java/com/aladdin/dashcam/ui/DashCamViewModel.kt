package com.aladdin.dashcam.ui

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aladdin.dashcam.gps.GpsSpeedometer
import com.aladdin.dashcam.model.ConnectionStatus
import com.aladdin.dashcam.model.DashCamDeviceStatus
import com.aladdin.dashcam.model.MediaItem
import com.aladdin.dashcam.model.RecordingStatus
import com.aladdin.dashcam.network.DashCamClient
import com.aladdin.dashcam.network.NetworkMonitor
import com.aladdin.dashcam.network.TargetIdentity
import com.aladdin.dashcam.network.TargetState
import com.aladdin.dashcam.protocol.GPSocketProtocol
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.regex.Pattern

sealed class HostSwitchEvent {
    data class Success(val host: String, val epoch: Long) : HostSwitchEvent()
    data class Failure(val host: String, val error: String) : HostSwitchEvent()
}

sealed class AlbumQueryState {
    object Idle : AlbumQueryState()
    object Loading : AlbumQueryState()
    data class Success(val count: Int) : AlbumQueryState()
    data class Error(val message: String) : AlbumQueryState()
}

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class DownloadTask(
    val id: String,
    val mediaItem: MediaItem,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val bytesRead: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null,
    val localFile: File? = null
)

class DashCamViewModel(application: Application) : AndroidViewModel(application) {
    val client = DashCamClient(scope = viewModelScope)
    val networkMonitor = NetworkMonitor(application)
    val gpsSpeedometer = GpsSpeedometer(application)

    val connectionStatus: StateFlow<ConnectionStatus> = client.connectionStatus
    val deviceStatus: StateFlow<DashCamDeviceStatus> = client.deviceStatus
    val targetIdentity: StateFlow<TargetIdentity> = client.targetIdentity
    val gpsStatus = gpsSpeedometer.gpsStatus

    private val _hostSwitchEvent = MutableSharedFlow<HostSwitchEvent>(replay = 0, extraBufferCapacity = 1)
    val hostSwitchEvent: SharedFlow<HostSwitchEvent> = _hostSwitchEvent.asSharedFlow()

    private val _deviceMediaList = MutableStateFlow<List<MediaItem>>(emptyList())
    val deviceMediaList: StateFlow<List<MediaItem>> = _deviceMediaList.asStateFlow()

    private val _localMediaList = MutableStateFlow<List<MediaItem>>(emptyList())
    val localMediaList: StateFlow<List<MediaItem>> = _localMediaList.asStateFlow()

    private val _albumQueryState = MutableStateFlow<AlbumQueryState>(AlbumQueryState.Idle)
    val albumQueryState: StateFlow<AlbumQueryState> = _albumQueryState.asStateFlow()

    private val _downloadTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadTasks: StateFlow<List<DownloadTask>> = _downloadTasks.asStateFlow()
    private val activeDownloadJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    private val _openMediaItemEvent = MutableSharedFlow<MediaItem>(replay = 0, extraBufferCapacity = 1)
    val openMediaItemEvent: SharedFlow<MediaItem> = _openMediaItemEvent.asSharedFlow()

    fun requestOpenMedia(item: MediaItem) {
        _openMediaItemEvent.tryEmit(item)
    }

    val wifiNetwork = networkMonitor.wifiNetwork
    private val _deviceHost = MutableStateFlow("192.168.25.1")
    val deviceHost: StateFlow<String> = _deviceHost.asStateFlow()

    private val _resolutionDisplay = MutableStateFlow("未讀取")
    val resolutionDisplay: StateFlow<String> = _resolutionDisplay.asStateFlow()

    private val _loopDisplay = MutableStateFlow("未讀取")
    val loopDisplay: StateFlow<String> = _loopDisplay.asStateFlow()

    init {
        networkMonitor.startMonitoring()
        viewModelScope.launch {
            networkMonitor.wifiNetwork.collect { network ->
                client.bindNetwork(network)
            }
        }
        client.start()
        gpsSpeedometer.startListening()
        loadLocalMedia()
    }

    /**
     * Atomically initiates target host update, clears settings cache, increments target epoch,
     * and publishes one-time event without retaining UI callbacks (R5-01 / R5-02).
     */
    fun setDeviceHost(newHost: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _resolutionDisplay.value = "未讀取"
                _loopDisplay.value = "未讀取"
                _deviceMediaList.value = emptyList()
                _albumQueryState.value = AlbumQueryState.Idle
                val newEpoch = client.updateHost(newHost)
                _deviceHost.value = newHost
                _hostSwitchEvent.emit(HostSwitchEvent.Success(newHost, newEpoch))
            } catch (e: Exception) {
                _hostSwitchEvent.emit(HostSwitchEvent.Failure(newHost, e.message ?: "切換失敗"))
            }
        }
    }

    suspend fun toggleRecording(): Boolean {
        val current = deviceStatus.value.recordingStatus
        val desired = current != RecordingStatus.RECORDING
        return client.ensureRecording(desired)
    }

    suspend fun ensureRecording(desired: Boolean): Boolean {
        return client.ensureRecording(desired)
    }

    suspend fun takeSnapshot(): Result<Unit> {
        return client.takePhoto()
    }

    suspend fun syncRtc(): Result<Unit> {
        return client.syncRtc()
    }

    suspend fun refreshStatus(): DashCamDeviceStatus? {
        return client.queryStatus()
    }

    suspend fun setResolution(resIndex: Int, displayName: String): Result<Unit> {
        val currentIdentity = targetIdentity.value
        val epoch = currentIdentity.epoch
        val result = client.setParameter(
            GPSocketProtocol.PARAM_RESOLUTION,
            byteArrayOf(resIndex.toByte()),
            expectedEpoch = epoch
        )
        if (result.isSuccess) {
            if (targetIdentity.value.epoch == epoch) {
                _resolutionDisplay.value = "$displayName (待回讀確認)"
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _resolutionDisplay.value = "設定失敗: ${result.exceptionOrNull()?.message}"
            }
        }
        return result
    }

    suspend fun setLoopDuration(minutes: Int, displayName: String): Result<Unit> {
        val currentIdentity = targetIdentity.value
        val epoch = currentIdentity.epoch
        val rawValue = when (minutes) {
            1 -> 1
            2 -> 2
            3 -> 3
            5 -> 4
            else -> minutes
        }
        val result = client.setParameter(
            GPSocketProtocol.PARAM_LOOP_RECORDING,
            byteArrayOf(rawValue.toByte()),
            expectedEpoch = epoch
        )
        if (result.isSuccess) {
            if (targetIdentity.value.epoch == epoch) {
                _loopDisplay.value = "$displayName (待回讀確認)"
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _loopDisplay.value = "設定失敗: ${result.exceptionOrNull()?.message}"
            }
        }
        return result
    }

    /**
     * Fetches real media file list from dashcam via CMD_GET_NAME_LIST (P-01 / Spec 25.8)
     */
    suspend fun fetchRemoteFileList(): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        val currentEpoch = targetIdentity.value.epoch
        _albumQueryState.value = AlbumQueryState.Loading
        val result = client.getFileList(expectedEpoch = currentEpoch)
        if (result.isSuccess) {
            val rawItems = result.getOrNull() ?: emptyList()
            // Cross check with local downloaded items
            val localFiles = getLocalMoviesDir().listFiles()?.map { it.name }?.toSet() ?: emptySet()
            val mappedItems = rawItems.map { item ->
                if (localFiles.contains(item.fileName)) {
                    item.copy(isDownloaded = true)
                } else {
                    item
                }
            }
            // Sort by recording time descending, unknown time placed after known times
            val sortedItems = mappedItems.sortedWith { a, b ->
                if (a.timestampMillis > 0 && b.timestampMillis > 0) {
                    b.timestampMillis.compareTo(a.timestampMillis)
                } else if (a.timestampMillis > 0) {
                    -1
                } else if (b.timestampMillis > 0) {
                    1
                } else {
                    b.deviceIndex.compareTo(a.deviceIndex)
                }
            }
            if (targetIdentity.value.epoch == currentEpoch) {
                _deviceMediaList.value = sortedItems
                _albumQueryState.value = AlbumQueryState.Success(sortedItems.size)
            }
        } else {
            if (targetIdentity.value.epoch == currentEpoch) {
                _albumQueryState.value = AlbumQueryState.Error(
                    result.exceptionOrNull()?.message ?: "無法取得記錄器影片清單"
                )
            }
        }
        result
    }

    /**
     * Cache directory for temporary viewing only. Does NOT mark item as downloaded or pollute local album.
     */
    fun getPreviewCacheFile(item: MediaItem): File {
        val app = getApplication<Application>()
        val cacheDir = File(app.cacheDir, "preview_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return File(cacheDir, item.fileName)
    }

    /**
     * Local storage directory for user-confirmed permanent downloads.
     */
    fun getLocalMediaFile(item: MediaItem): File? {
        val moviesDir = getLocalMoviesDir()
        val file = File(moviesDir, item.fileName)
        return if (file.exists()) file else null
    }

    /**
     * Fetches media file into temporary viewing cache. Does NOT mark as downloaded.
     * Progress dispatch is safely marshaled to Main thread and respects job cancellation (R1).
     */
    suspend fun fetchPreviewCache(
        item: MediaItem,
        onProgress: suspend (bytesRead: Long) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val destFile = getPreviewCacheFile(item)
        if (destFile.exists() && (item.sizeBytes <= 0 || destFile.length() == item.sizeBytes)) {
            return@withContext Result.success(destFile)
        }
        val currentEpoch = targetIdentity.value.epoch
        val res = client.downloadRawData(
            deviceIndex = item.deviceIndex,
            destinationFile = destFile,
            expectedEpoch = currentEpoch,
            onProgress = { bytesRead ->
                if (coroutineContext.isActive) {
                    kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                        if (coroutineContext.isActive) {
                            onProgress(bytesRead)
                        }
                    }
                }
            }
        )
        if (res.isSuccess) {
            Result.success(destFile)
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("下載預覽暫存失敗"))
        }
    }

    /**
     * Permanently saves media item to local album upon explicit user request.
     * Uses atomic CAS StateFlow updates (F2) and dispatches UI progress to Main thread (R1).
     */
    suspend fun saveToPermanentStorage(
        item: MediaItem,
        onProgress: suspend (bytesRead: Long) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val currentEpoch = targetIdentity.value.epoch
        val moviesDir = getLocalMoviesDir()
        if (!moviesDir.exists()) moviesDir.mkdirs()
        val destFile = File(moviesDir, item.fileName)

        updateDownloadTask(
            DownloadTask(
                id = item.fileName,
                mediaItem = item,
                status = DownloadStatus.DOWNLOADING,
                bytesRead = 0L,
                totalBytes = item.sizeBytes
            )
        )

        // If viewing cache already has the complete file, copy it directly
        val cacheFile = getPreviewCacheFile(item)
        if (cacheFile.exists() && (item.sizeBytes <= 0 || cacheFile.length() == item.sizeBytes)) {
            try {
                cacheFile.copyTo(destFile, overwrite = true)
                _deviceMediaList.update { list ->
                    list.map { if (it.fileName == item.fileName) it.copy(isDownloaded = true) else it }
                }
                updateDownloadTask(
                    DownloadTask(
                        id = item.fileName,
                        mediaItem = item,
                        status = DownloadStatus.COMPLETED,
                        bytesRead = destFile.length(),
                        totalBytes = destFile.length(),
                        localFile = destFile
                    )
                )
                loadLocalMedia()
                return@withContext Result.success(destFile)
            } catch (e: Exception) {
                // fallback to network download if copy fails
            }
        }

        val res = client.downloadRawData(
            deviceIndex = item.deviceIndex,
            destinationFile = destFile,
            expectedEpoch = currentEpoch,
            onProgress = { bytesRead ->
                if (coroutineContext.isActive) {
                    updateDownloadTaskProgress(item.fileName, bytesRead)
                    kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                        if (coroutineContext.isActive) {
                            onProgress(bytesRead)
                        }
                    }
                }
            }
        )
        if (res.isSuccess) {
            _deviceMediaList.update { list ->
                list.map { if (it.fileName == item.fileName) it.copy(isDownloaded = true) else it }
            }
            _downloadTasks.update { tasks ->
                tasks.map { existing ->
                    if (existing.id == item.fileName && existing.status != DownloadStatus.CANCELLED) {
                        existing.copy(
                            status = DownloadStatus.COMPLETED,
                            bytesRead = destFile.length(),
                            totalBytes = destFile.length(),
                            localFile = destFile
                        )
                    } else {
                        existing
                    }
                }
            }
            loadLocalMedia()
            Result.success(destFile)
        } else {
            val errMsg = res.exceptionOrNull()?.message ?: "永久儲存失敗"
            _downloadTasks.update { tasks ->
                tasks.map { existing ->
                    if (existing.id == item.fileName && existing.status != DownloadStatus.CANCELLED) {
                        existing.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = errMsg,
                            totalBytes = item.sizeBytes
                        )
                    } else {
                        existing
                    }
                }
            }
            Result.failure(res.exceptionOrNull() ?: Exception(errMsg))
        }
    }

    /**
     * Unified entry point for starting permanent downloads (F1 / Single Ownership).
     * Automatically registers and tracks Job in activeDownloadJobs.
     */
    fun startPermanentDownload(
        item: MediaItem,
        onProgress: (suspend (bytesRead: Long) -> Unit)? = null
    ): Job {
        activeDownloadJobs[item.fileName]?.let { existing ->
            if (existing.isActive) return existing
        }
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                saveToPermanentStorage(item, onProgress = onProgress ?: {})
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    _downloadTasks.update { tasks ->
                        tasks.map { if (it.id == item.fileName) it.copy(status = DownloadStatus.CANCELLED) else it }
                    }
                }
            } finally {
                activeDownloadJobs.remove(item.fileName)
            }
        }
        activeDownloadJobs[item.fileName] = job
        return job
    }

    private fun updateDownloadTask(task: DownloadTask) {
        _downloadTasks.update { tasks ->
            val index = tasks.indexOfFirst { it.id == task.id }
            if (index >= 0) {
                tasks.toMutableList().apply { set(index, task) }
            } else {
                listOf(task) + tasks
            }
        }
    }

    private fun updateDownloadTaskProgress(taskId: String, bytesRead: Long) {
        _downloadTasks.update { tasks ->
            val index = tasks.indexOfFirst { it.id == taskId }
            if (index >= 0) {
                val existing = tasks[index]
                if (existing.status == DownloadStatus.DOWNLOADING) {
                    tasks.toMutableList().apply { set(index, existing.copy(bytesRead = bytesRead)) }
                } else {
                    tasks
                }
            } else {
                tasks
            }
        }
    }

    fun retryDownload(task: DownloadTask) {
        startPermanentDownload(task.mediaItem)
    }

    fun cancelDownload(task: DownloadTask) {
        val job = activeDownloadJobs.remove(task.id)
        job?.cancel()
        _downloadTasks.update { tasks ->
            tasks.map { if (it.id == task.id) it.copy(status = DownloadStatus.CANCELLED) else it }
        }
    }

    /**
     * Downloads real media item from dashcam to local app storage (M4 / P-02)
     */
    suspend fun downloadDeviceMedia(
        item: MediaItem,
        onProgress: suspend (bytesRead: Long) -> Unit = {}
    ): Result<File> = saveToPermanentStorage(item, onProgress)

    fun loadLocalMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            val moviesDir = getLocalMoviesDir()
            if (!moviesDir.exists()) {
                _localMediaList.value = emptyList()
                return@launch
            }
            val files = moviesDir.listFiles() ?: return@launch
            val validFiles = files.filter { it.isFile && (it.name.endsWith(".MP4", true) || it.name.endsWith(".JPG", true)) }
            val items = validFiles.mapIndexed { idx, file ->
                val isVideo = file.name.endsWith(".MP4", true)
                val isEmergency = file.name.contains("EMG", true)
                val parsedTimestamp = parseRecordingTimeFromFileName(file.name)
                val duration = if (isVideo) extractMediaDurationSeconds(file) else 0

                MediaItem(
                    id = "LOCAL_${file.name}",
                    fileName = file.name,
                    sizeBytes = file.length(),
                    durationSeconds = duration, // 真實時長或 0（未知）
                    timestampMillis = parsedTimestamp, // 嚴格採用真實錄製時間，不冒用 lastModified()
                    isVideo = isVideo,
                    isEmergency = isEmergency,
                    isDownloaded = true,
                    deviceIndex = idx + 1
                )
            }.sortedWith { a, b ->
                if (a.timestampMillis > 0 && b.timestampMillis > 0) {
                    b.timestampMillis.compareTo(a.timestampMillis)
                } else if (a.timestampMillis > 0) {
                    -1
                } else if (b.timestampMillis > 0) {
                    1
                } else {
                    b.fileName.compareTo(a.fileName)
                }
            }
            _localMediaList.value = items

            // Atomic sync with download tasks (F2)
            val localTasks = items.map { localItem ->
                DownloadTask(
                    id = localItem.fileName,
                    mediaItem = localItem,
                    status = DownloadStatus.COMPLETED,
                    bytesRead = localItem.sizeBytes,
                    totalBytes = localItem.sizeBytes,
                    localFile = File(moviesDir, localItem.fileName)
                )
            }
            _downloadTasks.update { existingTasks ->
                val nonCompletedTasks = existingTasks.filter { it.status != DownloadStatus.COMPLETED }
                val existingNonCompletedIds = nonCompletedTasks.map { it.id }.toSet()
                nonCompletedTasks + localTasks.filter { !existingNonCompletedIds.contains(it.id) }
            }
        }
    }

    /**
     * Parses reliable recording timestamp from dashcam standard file names:
     * e.g. REC_20261021_073925_0001.MP4, EMG_20261021_073925_0001.MP4, PIC_20261021_073925_0001.JPG
     * Returns 0L if fileName does not match standard pattern or date is invalid.
     */
    private fun parseRecordingTimeFromFileName(fileName: String): Long {
        val pattern = Pattern.compile("^[A-Z]{3}_(\\d{8}_\\d{6})")
        val matcher = pattern.matcher(fileName)
        if (matcher.find()) {
            val dateStr = matcher.group(1) ?: return 0L
            return try {
                val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).apply {
                    isLenient = false
                }
                val date = sdf.parse(dateStr) ?: return 0L
                val cal = Calendar.getInstance().apply { time = date }
                val year = cal.get(Calendar.YEAR)
                if (year in 2020..2040) {
                    date.time
                } else {
                    0L
                }
            } catch (_: Exception) {
                0L
            }
        }
        return 0L // 無法驗證為可信錄製時間時返回 0L，代表時間未知
    }

    /**
     * Extracts actual duration from local video file using MediaMetadataRetriever.
     * Returns 0 if duration is unknown or file cannot be parsed.
     */
    private fun extractMediaDurationSeconds(file: File): Int {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durMs = durStr?.toLongOrNull() ?: 0L
            (durMs / 1000).toInt()
        } catch (_: Exception) {
            0
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {}
        }
    }

    private fun getLocalMoviesDir(): File {
        val app = getApplication<Application>()
        return app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(app.filesDir, "movies")
    }

    override fun onCleared() {
        super.onCleared()
        client.stop()
        networkMonitor.stopMonitoring()
        gpsSpeedometer.stopListening()
    }
}
