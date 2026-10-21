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

data class RtcSyncState(
    val lastAttemptTime: Long = 0L,
    val isAuto: Boolean = false,
    val isSuccess: Boolean = false,
    val message: String = "尚未校時"
)

data class StorageEstimate(
    val hasSdCard: Boolean = false,
    val isNormal: Boolean = false,
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val avgBitrateBps: Long = 0L,
    val minutesUntilOverwrite: Long = 0L,
    val retentionHours: Double = 0.0,
    val statusText: String = "尚未取得 SD 容量"
)

data class DashCamDeviceRecord(
    val id: String,
    val name: String,
    val host: String,
    val firmwareVersion: String = "20260513 V1.1.0",
    val lastConnectedTime: String = "",
    val isSelected: Boolean = false
)

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
    private val downloadGenerations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val generationCounter = java.util.concurrent.atomic.AtomicLong(1L)

    private val _openMediaItemEvent = MutableSharedFlow<MediaItem>(replay = 0, extraBufferCapacity = 1)
    val openMediaItemEvent: SharedFlow<MediaItem> = _openMediaItemEvent.asSharedFlow()

    fun requestOpenMedia(item: MediaItem) {
        _openMediaItemEvent.tryEmit(item)
    }

    val wifiNetwork = networkMonitor.wifiNetwork
    private val _deviceHost = MutableStateFlow("192.168.25.1")
    val deviceHost: StateFlow<String> = _deviceHost.asStateFlow()

    private val _resolutionDisplay = MutableStateFlow("2K")
    val resolutionDisplay: StateFlow<String> = _resolutionDisplay.asStateFlow()

    private val _loopDisplay = MutableStateFlow("5 分鐘")
    val loopDisplay: StateFlow<String> = _loopDisplay.asStateFlow()

    private val _captureResolutionDisplay = MutableStateFlow("5M (2592x1944)")
    val captureResolutionDisplay: StateFlow<String> = _captureResolutionDisplay.asStateFlow()

    private val _captureQualityDisplay = MutableStateFlow("優質")
    val captureQualityDisplay: StateFlow<String> = _captureQualityDisplay.asStateFlow()

    private val _exposureDisplay = MutableStateFlow("+0.0")
    val exposureDisplay: StateFlow<String> = _exposureDisplay.asStateFlow()

    private val _exposureValue = MutableStateFlow(0.0f)
    val exposureValue: StateFlow<Float> = _exposureValue.asStateFlow()

    private val _watermarkDisplay = MutableStateFlow("Date/Time")
    val watermarkDisplay: StateFlow<String> = _watermarkDisplay.asStateFlow()

    private val _audioRecordingDisplay = MutableStateFlow("開啟")
    val audioRecordingDisplay: StateFlow<String> = _audioRecordingDisplay.asStateFlow()

    private val _firmwareVersionDisplay = MutableStateFlow("20260513 V1.1.0")
    val firmwareVersionDisplay: StateFlow<String> = _firmwareVersionDisplay.asStateFlow()

    private val _wifiSsidDisplay = MutableStateFlow("B1-7bc74f")
    val wifiSsidDisplay: StateFlow<String> = _wifiSsidDisplay.asStateFlow()

    private val _rtcSyncState = MutableStateFlow(RtcSyncState())
    val rtcSyncState: StateFlow<RtcSyncState> = _rtcSyncState.asStateFlow()

    private val _storageEstimate = MutableStateFlow(StorageEstimate())
    val storageEstimate: StateFlow<StorageEstimate> = _storageEstimate.asStateFlow()

    private val _registeredDevices = MutableStateFlow(
        listOf(
            DashCamDeviceRecord(
                id = "B1-7bc74f",
                name = "B1-7bc74f",
                host = "192.168.25.1",
                firmwareVersion = "20260513 V1.1.0",
                lastConnectedTime = "2026-10-21 23:01:11",
                isSelected = true
            )
        )
    )
    val registeredDevices: StateFlow<List<DashCamDeviceRecord>> = _registeredDevices.asStateFlow()

    private val _isRidingModeActive = MutableStateFlow(false)
    val isRidingModeActive: StateFlow<Boolean> = _isRidingModeActive.asStateFlow()

    fun setRidingMode(active: Boolean) {
        _isRidingModeActive.value = active
    }

    private val _isRemotePlaybackActive = MutableStateFlow(false)
    val isRemotePlaybackActive: StateFlow<Boolean> = _isRemotePlaybackActive.asStateFlow()

    private val _currentPlaybackItem = MutableStateFlow<MediaItem?>(null)
    val currentPlaybackItem: StateFlow<MediaItem?> = _currentPlaybackItem.asStateFlow()

    @Volatile
    private var lastSyncedEpoch: Long = -1L

    init {
        networkMonitor.startMonitoring()
        viewModelScope.launch {
            networkMonitor.wifiNetwork.collect { network ->
                client.bindNetwork(network)
            }
        }
        viewModelScope.launch {
            client.targetIdentity.collect { identity ->
                if (identity.state == TargetState.READY) {
                    val epoch = identity.epoch
                    if (epoch != lastSyncedEpoch && epoch > 0) {
                        lastSyncedEpoch = epoch
                        // 新連線自動執行一次校時（去重：同 session 不重複發送）
                        performAutoSyncRtc(epoch)
                    }
                }
            }
        }
        viewModelScope.launch {
            client.deviceStatus.collect { status ->
                updateStorageEstimateFromStatus(status)
                if (status.audioEnabled) {
                    _audioRecordingDisplay.value = "開啟"
                } else if (status.lastUpdatedTimestamp > 0) {
                    _audioRecordingDisplay.value = "關閉"
                }
            }
        }
        client.start()
        gpsSpeedometer.startListening()
        loadLocalMedia()
    }

    private fun performAutoSyncRtc(epoch: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = client.syncRtc()
            val now = System.currentTimeMillis()
            _rtcSyncState.value = RtcSyncState(
                lastAttemptTime = now,
                isAuto = true,
                isSuccess = res.isSuccess,
                message = if (res.isSuccess) "連線自動校時成功" else "自動校時失敗: ${res.exceptionOrNull()?.message}"
            )
        }
    }

    fun syncRtcManual() {
        viewModelScope.launch(Dispatchers.IO) {
            val res = client.syncRtc()
            val now = System.currentTimeMillis()
            _rtcSyncState.value = RtcSyncState(
                lastAttemptTime = now,
                isAuto = false,
                isSuccess = res.isSuccess,
                message = if (res.isSuccess) "手動校時成功" else "手動校時失敗: ${res.exceptionOrNull()?.message}"
            )
        }
    }

    private fun updateStorageEstimateFromStatus(status: DashCamDeviceStatus) {
        if (status.lastUpdatedTimestamp == 0L) return
        val hasCard = status.sdCardReady || status.sdCardState == 1
        val isNormal = status.sdCardState == 1
        val total = status.sdTotalBytes
        val free = status.sdFreeBytes

        if (!hasCard) {
            _storageEstimate.value = StorageEstimate(
                hasSdCard = false,
                isNormal = false,
                statusText = "未偵測到記憶卡"
            )
            return
        }

        if (total <= 0L || free < 0L) {
            _storageEstimate.value = StorageEstimate(
                hasSdCard = true,
                isNormal = isNormal,
                statusText = "循環錄影中 (尚未取得 SD 容量)"
            )
            return
        }

        val used = (total - free).coerceAtLeast(0L)
        // 估算平均寫入速率：以已載入影片計算，若無影片則以 2K 碼率約 15Mbps (1.875 MB/s)
        val items = _deviceMediaList.value.filter { it.isVideo && it.sizeBytes > 0 && it.durationSeconds > 0 }
        val avgBitrateBps: Long = if (items.isNotEmpty()) {
            val totalBytesSum = items.sumOf { it.sizeBytes }
            val totalSecsSum = items.sumOf { it.durationSeconds.toLong() }
            if (totalSecsSum > 0) totalBytesSum / totalSecsSum else 1875000L
        } else {
            1875000L // 1.875 MB/s
        }

        val minutesUntilOverwrite = if (avgBitrateBps > 0) {
            free / avgBitrateBps / 60
        } else 0L

        val retentionHours = if (avgBitrateBps > 0) {
            (total.toDouble() / avgBitrateBps.toDouble()) / 3600.0
        } else 0.0

        val desc = if (minutesUntilOverwrite > 0) {
            String.format(
                java.util.Locale.TAIWAN,
                "循環錄影中 · 距覆寫約 %d 分鐘 · 總保留約 %.1f 小時",
                minutesUntilOverwrite,
                retentionHours
            )
        } else {
            String.format(
                java.util.Locale.TAIWAN,
                "循環錄影中 · 容量已滿持續覆寫 · 總保留約 %.1f 小時",
                retentionHours
            )
        }

        _storageEstimate.value = StorageEstimate(
            hasSdCard = true,
            isNormal = isNormal,
            totalBytes = total,
            freeBytes = free,
            usedBytes = used,
            avgBitrateBps = avgBitrateBps,
            minutesUntilOverwrite = minutesUntilOverwrite,
            retentionHours = retentionHours,
            statusText = desc
        )
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

    suspend fun restartStreaming(): Result<Unit> {
        return client.restartStreaming()
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
                _loopDisplay.value = displayName
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _loopDisplay.value = "設定失敗: ${result.exceptionOrNull()?.message}"
            }
        }
        return result
    }

    suspend fun setCaptureResolution(resIndex: Int, displayName: String): Result<Unit> {
        val currentIdentity = targetIdentity.value
        val epoch = currentIdentity.epoch
        val result = client.setParameter(
            GPSocketProtocol.PARAM_CAPTURE_RESOLUTION,
            byteArrayOf(resIndex.toByte()),
            expectedEpoch = epoch
        )
        if (result.isSuccess) {
            _captureResolutionDisplay.value = displayName
        }
        return result
    }

    suspend fun setCaptureQuality(qualityIndex: Int, displayName: String): Result<Unit> {
        val currentIdentity = targetIdentity.value
        val epoch = currentIdentity.epoch
        val result = client.setParameter(
            GPSocketProtocol.PARAM_CAPTURE_QUALITY,
            byteArrayOf(qualityIndex.toByte()),
            expectedEpoch = epoch
        )
        if (result.isSuccess) {
            _captureQualityDisplay.value = displayName
        }
        return result
    }

    suspend fun setExposure(exposureVal: Float): Result<Unit> {
        val currentIdentity = targetIdentity.value
        val epoch = currentIdentity.epoch
        val rawVal = (exposureVal * 3).toInt().coerceIn(-6, 6)
        val result = client.setParameter(
            GPSocketProtocol.PARAM_EXPOSURE,
            byteArrayOf(rawVal.toByte()),
            expectedEpoch = epoch
        )
        if (result.isSuccess) {
            _exposureValue.value = exposureVal
            _exposureDisplay.value = String.format(java.util.Locale.US, "%+.1f", exposureVal)
        }
        return result
    }

    suspend fun setWatermark(enabled: Boolean): Result<Unit> {
        val currentIdentity = targetIdentity.value
        val epoch = currentIdentity.epoch
        val rawVal = if (enabled) 1 else 0
        val result = client.setParameter(
            GPSocketProtocol.PARAM_WATERMARK,
            byteArrayOf(rawVal.toByte()),
            expectedEpoch = epoch
        )
        if (result.isSuccess) {
            _watermarkDisplay.value = if (enabled) "Date/Time" else "關閉"
        }
        return result
    }

    suspend fun toggleAudioRecording(): Result<Unit> {
        val result = client.toggleAudio()
        if (result.isSuccess) {
            val nextState = _audioRecordingDisplay.value != "開啟"
            _audioRecordingDisplay.value = if (nextState) "開啟" else "關閉"
        }
        return result
    }

    suspend fun startRemotePlayback(item: MediaItem): Result<Unit> {
        _currentPlaybackItem.value = item
        val epoch = targetIdentity.value.epoch
        val res = client.startPlayback(item.deviceIndex, expectedEpoch = epoch)
        if (res.isSuccess) {
            _isRemotePlaybackActive.value = true
        }
        return res
    }

    suspend fun stopRemotePlayback(): Result<Unit> {
        val epoch = targetIdentity.value.epoch
        val res = client.stopPlayback(expectedEpoch = epoch)
        _isRemotePlaybackActive.value = false
        _currentPlaybackItem.value = null
        client.restartStreaming()
        return res
    }

    suspend fun deleteRemoteMedia(item: MediaItem): Result<Unit> {
        val epoch = targetIdentity.value.epoch
        val res = client.deleteFile(item.deviceIndex, expectedEpoch = epoch)
        if (res.isSuccess) {
            _deviceMediaList.update { list -> list.filter { it.id != item.id } }
        }
        return res
    }

    suspend fun deleteBatchRemoteMedia(items: List<MediaItem>): Result<Int> {
        val epoch = targetIdentity.value.epoch
        var successCount = 0
        for (item in items) {
            val res = client.deleteFile(item.deviceIndex, expectedEpoch = epoch)
            if (res.isSuccess) {
                successCount++
            }
        }
        val deletedIds = items.take(successCount).map { it.id }.toSet()
        _deviceMediaList.update { list -> list.filter { !deletedIds.contains(it.id) } }
        return Result.success(successCount)
    }

    fun deleteLocalFile(item: MediaItem): Boolean {
        val file = getLocalMediaFile(item)
        val ok = file?.delete() ?: false
        if (ok) {
            _localMediaList.update { list -> list.filter { it.id != item.id } }
        }
        return ok
    }

    fun selectDevice(device: DashCamDeviceRecord) {
        _registeredDevices.update { list ->
            list.map { it.copy(isSelected = (it.id == device.id)) }
        }
        setDeviceHost(device.host)
    }

    fun addDevice(name: String, host: String) {
        val newDev = DashCamDeviceRecord(
            id = name,
            name = name,
            host = host,
            lastConnectedTime = "",
            isSelected = false
        )
        _registeredDevices.update { it + newDev }
    }

    fun removeDevice(deviceId: String) {
        _registeredDevices.update { it.filter { dev -> dev.id != deviceId } }
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
     * Uses atomic CAS StateFlow updates (F2) and generation isolation (C2).
     */
    suspend fun saveToPermanentStorage(
        item: MediaItem,
        generation: Long = 0L
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
                if (generation == 0L || downloadGenerations[item.fileName] == generation) {
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
                }
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
                if (coroutineContext.isActive && (generation == 0L || downloadGenerations[item.fileName] == generation)) {
                    updateDownloadTaskProgress(item.fileName, bytesRead)
                }
            }
        )
        if (res.isSuccess) {
            if (generation == 0L || downloadGenerations[item.fileName] == generation) {
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
            }
            Result.success(destFile)
        } else {
            val errMsg = res.exceptionOrNull()?.message ?: "永久儲存失敗"
            if (generation == 0L || downloadGenerations[item.fileName] == generation) {
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
            }
            Result.failure(res.exceptionOrNull() ?: Exception(errMsg))
        }
    }

    /**
     * Unified entry point for starting permanent downloads (F1 / Single Ownership).
     * Decoupled from Fragment callbacks (C4) with generation isolation (C2).
     */
    fun startPermanentDownload(item: MediaItem): Job {
        activeDownloadJobs[item.fileName]?.let { existing ->
            if (existing.isActive) return existing
        }
        val gen = generationCounter.incrementAndGet()
        downloadGenerations[item.fileName] = gen

        var ownedJob: Job? = null
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                saveToPermanentStorage(item, generation = gen)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    if (downloadGenerations[item.fileName] == gen) {
                        _downloadTasks.update { tasks ->
                            tasks.map { if (it.id == item.fileName) it.copy(status = DownloadStatus.CANCELLED) else it }
                        }
                    }
                } else {
                    if (downloadGenerations[item.fileName] == gen) {
                        _downloadTasks.update { tasks ->
                            tasks.map { if (it.id == item.fileName) it.copy(status = DownloadStatus.FAILED, errorMessage = e.message) else it }
                        }
                    }
                }
            } finally {
                ownedJob?.let { activeDownloadJobs.remove(item.fileName, it) }
            }
        }
        ownedJob = job
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
        val job = activeDownloadJobs[task.id]
        val currentGen = downloadGenerations[task.id] ?: 0L
        downloadGenerations[task.id] = currentGen + 1
        job?.cancel()
        client.abortActiveTransfer()
        _downloadTasks.update { tasks ->
            tasks.map { if (it.id == task.id) it.copy(status = DownloadStatus.CANCELLED) else it }
        }
    }

    /**
     * Downloads real media item from dashcam to local app storage (M4 / P-02)
     */
    suspend fun downloadDeviceMedia(item: MediaItem): Result<File> = saveToPermanentStorage(item)

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
     * 1) Standard: REC_20261021_073925_0001.MP4, EMG_20261021_073925_0001.MP4, PIC_20261021_073925_0001.JPG
     * 2) OEM Vidure: 20261021230048_MOV0187.mov
     */
    fun parseRecordingTimeFromFileName(fileName: String): Long {
        // Pattern 1: REC_20261021_073925_0001.MP4
        val p1 = Pattern.compile("^[A-Z]{3}_(\\d{8}_\\d{6})")
        val m1 = p1.matcher(fileName)
        if (m1.find()) {
            val dateStr = m1.group(1) ?: ""
            try {
                val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).apply {
                    isLenient = false
                }
                val date = sdf.parse(dateStr)
                if (date != null) {
                    val cal = Calendar.getInstance().apply { time = date }
                    if (cal.get(Calendar.YEAR) in 2020..2040) return date.time
                }
            } catch (_: Exception) {}
        }

        // Pattern 2: OEM Vidure format 20261021230048_MOV0187.mov
        val p2 = Pattern.compile("^(\\d{14})_")
        val m2 = p2.matcher(fileName)
        if (m2.find()) {
            val dateStr = m2.group(1) ?: ""
            try {
                val sdf = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply {
                    isLenient = false
                }
                val date = sdf.parse(dateStr)
                if (date != null) {
                    val cal = Calendar.getInstance().apply { time = date }
                    if (cal.get(Calendar.YEAR) in 2020..2040) return date.time
                }
            } catch (_: Exception) {}
        }
        return 0L
    }

    suspend fun getOrFetchThumbnail(item: MediaItem): File? = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val thumbDir = File(app.cacheDir, "thumbnails")
        if (!thumbDir.exists()) thumbDir.mkdirs()
        val thumbFile = File(thumbDir, "thumb_${item.id}.jpg")
        if (thumbFile.exists() && thumbFile.length() > 0) {
            return@withContext thumbFile
        }

        val res = client.getThumbnail(item.deviceIndex, expectedEpoch = targetIdentity.value.epoch)
        if (res.isSuccess) {
            val bytes = res.getOrNull()
            if (bytes != null && bytes.size > 4) {
                try {
                    thumbFile.writeBytes(bytes)
                    return@withContext thumbFile
                } catch (_: Exception) {}
            }
        }
        null
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
