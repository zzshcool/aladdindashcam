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
import com.aladdin.dashcam.model.DashCamSettingMappings
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
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

sealed class ProbeStatus {
    object Idle : ProbeStatus()
    data class Probing(val host: String) : ProbeStatus()
    data class Success(val host: String, val protocolHeader: String) : ProbeStatus()
    data class NotDashCam(val host: String) : ProbeStatus()
    data class Failed(val host: String, val reason: String?) : ProbeStatus()
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
    val canDeleteFile: Boolean = false,
    val isNormal: Boolean = false,
    val isCapacityFieldMissing: Boolean = false,
    val totalBytes: Long = -1L,
    val freeBytes: Long = -1L,
    val usedBytes: Long = -1L,
    val listedMediaTotalBytes: Long = 0L,
    val avgBitrateBps: Long = 0L,
    val minutesUntilOverwrite: Long = -1L,
    val retentionHours: Double = -1.0,
    val statusText: String = "未偵測到記憶卡"
)

data class DashCamDeviceRecord(
    val id: String,
    val name: String,
    val host: String,
    val ssid: String = "",
    val bssid: String = "",
    val firmwareVersion: String = "未知 (待讀取)",
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

    val recordingTimerInterpolator = RecordingTimerInterpolator()
    private val _timerTickTrigger = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1).apply {
        tryEmit(Unit)
    }

    val recordingTimerDisplay: StateFlow<RecordingTimerDisplay> = merge(
        _timerTickTrigger,
        flow {
            while (true) {
                delay(1000L)
                emit(Unit)
            }
        }
    ).map {
        recordingTimerInterpolator.getCurrentDisplay()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000L),
        initialValue = recordingTimerInterpolator.getCurrentDisplay()
    )

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

    private val _resolutionDisplay = MutableStateFlow("未連線")
    val resolutionDisplay: StateFlow<String> = _resolutionDisplay.asStateFlow()

    private val _loopDisplay = MutableStateFlow("未連線")
    val loopDisplay: StateFlow<String> = _loopDisplay.asStateFlow()

    // 儲存真實回讀的循環錄影參數 (0=Off, 1=1m, 2=2m, 3=3m, 4=5m, -1=未確認)
    private val _currentLoopSettingRaw = MutableStateFlow<Int?>(-1)
    val currentLoopSettingRaw: StateFlow<Int?> = _currentLoopSettingRaw.asStateFlow()

    private val _captureResolutionDisplay = MutableStateFlow("未連線")
    val captureResolutionDisplay: StateFlow<String> = _captureResolutionDisplay.asStateFlow()

    private val _captureQualityDisplay = MutableStateFlow("未連線")
    val captureQualityDisplay: StateFlow<String> = _captureQualityDisplay.asStateFlow()

    private val _exposureDisplay = MutableStateFlow("未連線")
    val exposureDisplay: StateFlow<String> = _exposureDisplay.asStateFlow()

    private val _exposureValue = MutableStateFlow(0.0f)
    val exposureValue: StateFlow<Float> = _exposureValue.asStateFlow()

    private val _watermarkDisplay = MutableStateFlow("未連線")
    val watermarkDisplay: StateFlow<String> = _watermarkDisplay.asStateFlow()

    private val _audioRecordingDisplay = MutableStateFlow("未連線")
    val audioRecordingDisplay: StateFlow<String> = _audioRecordingDisplay.asStateFlow()

    private val _firmwareVersionDisplay = MutableStateFlow("未連線")
    val firmwareVersionDisplay: StateFlow<String> = _firmwareVersionDisplay.asStateFlow()

    private val _wifiSsidDisplay = MutableStateFlow("未連線")
    val wifiSsidDisplay: StateFlow<String> = _wifiSsidDisplay.asStateFlow()

    private val _rtcSyncState = MutableStateFlow(RtcSyncState())
    val rtcSyncState: StateFlow<RtcSyncState> = _rtcSyncState.asStateFlow()

    private val _storageEstimate = MutableStateFlow(StorageEstimate())
    val storageEstimate: StateFlow<StorageEstimate> = _storageEstimate.asStateFlow()

    private val prefs = application.getSharedPreferences("dashcam_registered_devices", android.content.Context.MODE_PRIVATE)

    private val _registeredDevices = MutableStateFlow<List<DashCamDeviceRecord>>(emptyList())
    val registeredDevices: StateFlow<List<DashCamDeviceRecord>> = _registeredDevices.asStateFlow()

    val selectedDevice: StateFlow<DashCamDeviceRecord?> = _registeredDevices
        .map { list -> list.firstOrNull { it.isSelected } ?: list.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var probeJob: Job? = null
    private val _probeStatus = MutableStateFlow<ProbeStatus>(ProbeStatus.Idle)
    val probeStatus: StateFlow<ProbeStatus> = _probeStatus.asStateFlow()

    private val _isRidingModeActive = MutableStateFlow(false)
    val isRidingModeActive: StateFlow<Boolean> = _isRidingModeActive.asStateFlow()

    fun setRidingMode(active: Boolean) {
        _isRidingModeActive.value = active
    }

    enum class MediaStreamOwner {
        NONE,
        LIVE_PREVIEW,
        ALBUM_PLAYBACK
    }

    private val _currentStreamOwner = MutableStateFlow(MediaStreamOwner.NONE)
    val currentStreamOwner: StateFlow<MediaStreamOwner> = _currentStreamOwner.asStateFlow()

    fun requestStreamOwner(owner: MediaStreamOwner): Boolean {
        _currentStreamOwner.value = owner
        return true
    }

    fun releaseStreamOwner(owner: MediaStreamOwner) {
        if (_currentStreamOwner.value == owner) {
            _currentStreamOwner.value = MediaStreamOwner.NONE
        }
    }

    private val _isRemotePlaybackActive = MutableStateFlow(false)
    val isRemotePlaybackActive: StateFlow<Boolean> = _isRemotePlaybackActive.asStateFlow()

    private val _currentPlaybackItem = MutableStateFlow<MediaItem?>(null)
    val currentPlaybackItem: StateFlow<MediaItem?> = _currentPlaybackItem.asStateFlow()

    @Volatile
    private var lastSyncedSessionId: Long = 0L

    init {
        loadPersistedDevices()
        networkMonitor.startMonitoring()
        viewModelScope.launch {
            networkMonitor.wifiNetwork.collect { network ->
                probeJob?.cancel()
                client.bindNetwork(network)
                if (network != null) {
                    probeJob = launch(Dispatchers.IO) {
                        delay(350)
                        val gwIp = networkMonitor.getWifiGatewayIp() ?: "192.168.25.1"
                        _probeStatus.value = ProbeStatus.Probing(gwIp)
                        val probeRes = client.probeDevice(gwIp, network)
                        if (probeRes.isSuccess) {
                            val isDashCam = probeRes.getOrNull() == true
                            if (isDashCam) {
                                _probeStatus.value = ProbeStatus.Success(gwIp, "GPSOCK")
                                autoMatchOrAddDeviceForHost(gwIp, "GPSOCK")
                            } else {
                                _probeStatus.value = ProbeStatus.NotDashCam(gwIp)
                            }
                        } else {
                            _probeStatus.value = ProbeStatus.Failed(gwIp, probeRes.exceptionOrNull()?.message)
                        }
                    }
                } else {
                    _probeStatus.value = ProbeStatus.Idle
                }
            }
        }
        viewModelScope.launch {
            client.connectionStatus.collect { status ->
                recordingTimerInterpolator.onConnectionStatusChanged(status)
                _timerTickTrigger.tryEmit(Unit)
            }
        }
        viewModelScope.launch {
            client.targetIdentity.collect { identity ->
                recordingTimerInterpolator.onDeviceSwitched(identity.epoch)
                _timerTickTrigger.tryEmit(Unit)
                if (identity.state == TargetState.READY && identity.sessionId > 0L) {
                    val session = identity.sessionId
                    if (session != lastSyncedSessionId) {
                        lastSyncedSessionId = session
                        // 新連線成功建立 Session，自動執行一次校時（R06）
                        performAutoSyncRtc(identity.epoch)
                        // 同步發起真實設定值回讀（R05）
                        refreshSettings()
                        updateDeviceLastConnected(identity.requestedHost)
                    }
                } else if (identity.state == TargetState.DISCONNECTED) {
                    _resolutionDisplay.value = "未連線"
                    _loopDisplay.value = "未連線"
                    _captureResolutionDisplay.value = "未連線"
                    _captureQualityDisplay.value = "未連線"
                    _exposureDisplay.value = "未連線"
                    _watermarkDisplay.value = "未連線"
                    _audioRecordingDisplay.value = "未連線"
                    _firmwareVersionDisplay.value = "未連線"
                    _wifiSsidDisplay.value = "未連線"
                }
            }
        }
        viewModelScope.launch {
            client.deviceStatus.collect { status ->
                recordingTimerInterpolator.onDeviceStatus(status, targetIdentity.value.epoch)
                _timerTickTrigger.tryEmit(Unit)
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
            val res = client.syncRtc(expectedEpoch = epoch)
            if (epoch != targetIdentity.value.epoch) {
                return@launch
            }
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
        val epoch = targetIdentity.value.epoch
        viewModelScope.launch(Dispatchers.IO) {
            val res = client.syncRtc(expectedEpoch = epoch)
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
        val canDelete = status.sdCardReady
        val sdCardState = status.sdCardState
        val hasCard = sdCardState == 1 || (sdCardState == -1 && canDelete)
        val isCardError = sdCardState in listOf(2, 3, 6, 7, 8, 9, 10, 12, 14)
        val isNormal = hasCard && !isCardError
        val total = status.sdTotalBytes
        val free = status.sdFreeBytes

        val loopRaw = _currentLoopSettingRaw.value
        val loopModePrefix = when (loopRaw) {
            0 -> "單次連續錄影 (不循環)"
            1 -> "循環錄影 (1分)"
            2 -> "循環錄影 (2分)"
            3 -> "循環錄影 (3分)"
            4 -> "循環錄影 (5分)"
            else -> "錄影模式 (循環設定待確認)"
        }

        val listedMediaBytes = _deviceMediaList.value.sumOf { it.sizeBytes }

        if (sdCardState == 0) {
            _storageEstimate.value = StorageEstimate(
                hasSdCard = false,
                canDeleteFile = canDelete,
                isNormal = false,
                listedMediaTotalBytes = listedMediaBytes,
                statusText = "未偵測到記憶卡"
            )
            return
        }

        if (isCardError) {
            _storageEstimate.value = StorageEstimate(
                hasSdCard = true,
                canDeleteFile = canDelete,
                isNormal = false,
                listedMediaTotalBytes = listedMediaBytes,
                statusText = "記憶卡異常或需格式化 (代碼 $sdCardState)"
            )
            return
        }

        if (!canDelete && sdCardState != 1) {
            _storageEstimate.value = StorageEstimate(
                hasSdCard = false,
                canDeleteFile = false,
                isNormal = false,
                listedMediaTotalBytes = listedMediaBytes,
                statusText = "記憶卡未就緒或無卡"
            )
            return
        }

        // 估算平均寫入速率：以已載入影片計算真實樣本
        val items = _deviceMediaList.value.filter { it.isVideo && it.sizeBytes > 0 && it.durationSeconds > 0 }
        val avgBitrateBps: Long = if (items.isNotEmpty()) {
            val totalBytesSum = items.sumOf { it.sizeBytes }
            val totalSecsSum = items.sumOf { it.durationSeconds.toLong() }
            if (totalSecsSum > 0) totalBytesSum / totalSecsSum else 0L
        } else {
            0L
        }

        if (total <= 0L || free < 0L) {
            // 實機韌體狀態封包長度為 14 bytes，缺少容量擴充欄位 (bytes 16..23)
            val desc = if (listedMediaBytes > 0L) {
                val mediaMb = listedMediaBytes / (1024.0 * 1024.0)
                String.format(
                    java.util.Locale.TAIWAN,
                    "%s · 記憶卡可操作 · 已列舉媒體大小: %.1f MB (總容量韌體未回報)",
                    loopModePrefix,
                    mediaMb
                )
            } else {
                String.format(
                    java.util.Locale.TAIWAN,
                    "%s · 記憶卡可操作 (容量 bytes 韌體未回報)",
                    loopModePrefix
                )
            }
            _storageEstimate.value = StorageEstimate(
                hasSdCard = true,
                canDeleteFile = canDelete,
                isNormal = true,
                isCapacityFieldMissing = true,
                totalBytes = -1L,
                freeBytes = -1L,
                usedBytes = -1L,
                listedMediaTotalBytes = listedMediaBytes,
                minutesUntilOverwrite = -1L,
                retentionHours = -1.0,
                statusText = desc
            )
            return
        }

        val used = (total - free).coerceAtLeast(0L)
        val minutesUntilOverwrite = if (avgBitrateBps > 0) {
            free / avgBitrateBps / 60
        } else -1L

        val retentionHours = if (avgBitrateBps > 0) {
            (total.toDouble() / avgBitrateBps.toDouble()) / 3600.0
        } else -1.0

        val desc = if (items.isEmpty() || avgBitrateBps <= 0L) {
            String.format(
                java.util.Locale.TAIWAN,
                "%s · 可用空間 %.1f GB (時間估算中)",
                loopModePrefix,
                free / (1024.0 * 1024.0 * 1024.0)
            )
        } else if (loopRaw != null && loopRaw > 0) {
            String.format(
                java.util.Locale.TAIWAN,
                "%s · 距循環覆蓋約 %d 分鐘 · 總保存約 %.1f 小時",
                loopModePrefix,
                minutesUntilOverwrite,
                retentionHours
            )
        } else {
            String.format(
                java.util.Locale.TAIWAN,
                "%s · 剩餘可錄約 %d 分鐘",
                loopModePrefix,
                minutesUntilOverwrite
            )
        }

        _storageEstimate.value = StorageEstimate(
            hasSdCard = true,
            canDeleteFile = canDelete,
            isNormal = isNormal,
            isCapacityFieldMissing = false,
            totalBytes = total,
            freeBytes = free,
            usedBytes = used,
            listedMediaTotalBytes = listedMediaBytes,
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
                _currentLoopSettingRaw.value = -1
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

    suspend fun setResolution(resRawValue: Int, displayName: String): Result<Unit> {
        val currentIdentity = targetIdentity.value
        val epoch = currentIdentity.epoch
        val result = client.setParameter(
            GPSocketProtocol.PARAM_RESOLUTION,
            byteArrayOf(resRawValue.toByte()),
            expectedEpoch = epoch
        )
        if (result.isSuccess) {
            if (targetIdentity.value.epoch == epoch) {
                _resolutionDisplay.value = "$displayName (確認中...)"
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(250)
                    val check = client.getParameter(GPSocketProtocol.PARAM_RESOLUTION, expectedEpoch = epoch)
                    if (targetIdentity.value.epoch == epoch) {
                        if (check.isSuccess) {
                            val actual = check.getOrNull()?.firstOrNull()?.toInt()
                            _resolutionDisplay.value = if (actual == resRawValue) displayName else "回讀值不符: ${DashCamSettingMappings.getVideoResolutionName(actual ?: -1)}"
                        } else {
                            _resolutionDisplay.value = "$displayName (已送出，回讀失敗)"
                        }
                    }
                }
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _resolutionDisplay.value = "設定失敗: ${result.exceptionOrNull()?.message}"
            }
        }
        return result
    }

    suspend fun setLoopDuration(loopRawValue: Int, displayName: String): Result<Unit> {
        val currentIdentity = targetIdentity.value
        val epoch = currentIdentity.epoch
        val result = client.setParameter(
            GPSocketProtocol.PARAM_LOOP_RECORDING,
            byteArrayOf(loopRawValue.toByte()),
            expectedEpoch = epoch
        )
        if (result.isSuccess) {
            if (targetIdentity.value.epoch == epoch) {
                _loopDisplay.value = "$displayName (確認中...)"
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(250)
                    val check = client.getParameter(GPSocketProtocol.PARAM_LOOP_RECORDING, expectedEpoch = epoch)
                    if (targetIdentity.value.epoch == epoch) {
                        if (check.isSuccess) {
                            val actual = check.getOrNull()?.firstOrNull()?.toInt()
                            if (actual == loopRawValue || (loopRawValue == 4 && actual == 5)) {
                                _loopDisplay.value = displayName
                                _currentLoopSettingRaw.value = actual
                                updateStorageEstimateFromStatus(client.deviceStatus.value)
                            } else {
                                _loopDisplay.value = "回讀值不符: ${DashCamSettingMappings.getLoopRecordingName(actual ?: -1)}"
                            }
                        } else {
                            _loopDisplay.value = "$displayName (已送出，回讀失敗)"
                        }
                    }
                }
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _loopDisplay.value = "設定失敗: ${result.exceptionOrNull()?.message}"
            }
        }
        return result
    }

    suspend fun setCaptureResolution(resRawValue: Int, displayName: String): Result<Unit> {
        val currentIdentity = targetIdentity.value
        val epoch = currentIdentity.epoch
        val result = client.setParameter(
            GPSocketProtocol.PARAM_CAPTURE_RESOLUTION,
            byteArrayOf(resRawValue.toByte()),
            expectedEpoch = epoch
        )
        if (result.isSuccess) {
            if (targetIdentity.value.epoch == epoch) {
                _captureResolutionDisplay.value = "$displayName (確認中...)"
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(250)
                    val check = client.getParameter(GPSocketProtocol.PARAM_CAPTURE_RESOLUTION, expectedEpoch = epoch)
                    if (targetIdentity.value.epoch == epoch) {
                        if (check.isSuccess) {
                            val actual = check.getOrNull()?.firstOrNull()?.toInt()
                            _captureResolutionDisplay.value = if (actual == resRawValue) displayName else "回讀值不符: ${DashCamSettingMappings.getCaptureResolutionName(actual ?: -1)}"
                        } else {
                            _captureResolutionDisplay.value = "$displayName (已送出，回讀失敗)"
                        }
                    }
                }
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _captureResolutionDisplay.value = "設定失敗: ${result.exceptionOrNull()?.message}"
            }
        }
        return result
    }

    suspend fun setCaptureQuality(qualityRawValue: Int, displayName: String): Result<Unit> {
        val currentIdentity = targetIdentity.value
        val epoch = currentIdentity.epoch
        val result = client.setParameter(
            GPSocketProtocol.PARAM_CAPTURE_QUALITY,
            byteArrayOf(qualityRawValue.toByte()),
            expectedEpoch = epoch
        )
        if (result.isSuccess) {
            if (targetIdentity.value.epoch == epoch) {
                _captureQualityDisplay.value = "$displayName (確認中...)"
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(250)
                    val check = client.getParameter(GPSocketProtocol.PARAM_CAPTURE_QUALITY, expectedEpoch = epoch)
                    if (targetIdentity.value.epoch == epoch) {
                        if (check.isSuccess) {
                            val actual = check.getOrNull()?.firstOrNull()?.toInt()
                            _captureQualityDisplay.value = if (actual == qualityRawValue) displayName else "回讀值不符: ${DashCamSettingMappings.getCaptureQualityName(actual ?: -1)}"
                        } else {
                            _captureQualityDisplay.value = "$displayName (已送出，回讀失敗)"
                        }
                    }
                }
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _captureQualityDisplay.value = "設定失敗: ${result.exceptionOrNull()?.message}"
            }
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
        val disp = String.format(java.util.Locale.US, "%+.1f", exposureVal)
        if (result.isSuccess) {
            if (targetIdentity.value.epoch == epoch) {
                _exposureValue.value = exposureVal
                _exposureDisplay.value = "$disp (確認中...)"
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(250)
                    val check = client.getParameter(GPSocketProtocol.PARAM_EXPOSURE, expectedEpoch = epoch)
                    if (targetIdentity.value.epoch == epoch) {
                        if (check.isSuccess) {
                            val actual = check.getOrNull()?.firstOrNull()?.toInt()
                            _exposureDisplay.value = if (actual == rawVal) disp else "回讀值: ${actual?.let { String.format(java.util.Locale.US, "%+.1f", it / 3.0f) } ?: actual}"
                        } else {
                            _exposureDisplay.value = "$disp (已送出，回讀失敗)"
                        }
                    }
                }
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _exposureDisplay.value = "設定失敗: ${result.exceptionOrNull()?.message}"
            }
        }
        return result
    }

    suspend fun setWatermark(rawVal: Int, displayName: String = DashCamSettingMappings.getWatermarkName(rawVal)): Result<Unit> {
        val currentIdentity = targetIdentity.value
        val epoch = currentIdentity.epoch
        val result = client.setParameter(
            GPSocketProtocol.PARAM_WATERMARK,
            byteArrayOf(rawVal.toByte()),
            expectedEpoch = epoch
        )
        if (result.isSuccess) {
            if (targetIdentity.value.epoch == epoch) {
                _watermarkDisplay.value = "$displayName (確認中...)"
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(250)
                    val check = client.getParameter(GPSocketProtocol.PARAM_WATERMARK, expectedEpoch = epoch)
                    if (targetIdentity.value.epoch == epoch) {
                        if (check.isSuccess) {
                            val actual = check.getOrNull()?.firstOrNull()?.toInt()
                            _watermarkDisplay.value = if (actual == rawVal) displayName else "回讀值不符: ${DashCamSettingMappings.getWatermarkName(actual ?: -1)}"
                        } else {
                            _watermarkDisplay.value = "$displayName (已送出，回讀失敗)"
                        }
                    }
                }
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _watermarkDisplay.value = "設定失敗: ${result.exceptionOrNull()?.message}"
            }
        }
        return result
    }
    suspend fun toggleAudioRecording(): Result<Unit> {
        val epoch = targetIdentity.value.epoch
        val result = client.toggleAudio(expectedEpoch = epoch)
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
        val successfullyDeletedIds = mutableSetOf<String>()
        for (item in items) {
            val res = client.deleteFile(item.deviceIndex, expectedEpoch = epoch)
            if (res.isSuccess) {
                successfullyDeletedIds.add(item.id)
            }
        }
        _deviceMediaList.update { list -> list.filter { !successfullyDeletedIds.contains(it.id) } }
        return Result.success(successfullyDeletedIds.size)
    }

    fun deleteLocalFile(item: MediaItem): Boolean {
        val file = getLocalMediaFile(item)
        val ok = file?.delete() ?: false
        if (ok) {
            _localMediaList.update { list -> list.filter { it.id != item.id } }
        }
        return ok
    }

    fun refreshSettings() {
        val epoch = targetIdentity.value.epoch
        viewModelScope.launch(Dispatchers.IO) {
            _resolutionDisplay.value = "讀取中..."
            _loopDisplay.value = "讀取中..."
            _exposureDisplay.value = "讀取中..."
            _watermarkDisplay.value = "讀取中..."
            _captureResolutionDisplay.value = "讀取中..."
            _captureQualityDisplay.value = "讀取中..."

            // 1. Resolution
            val resResult = client.getParameter(GPSocketProtocol.PARAM_RESOLUTION, expectedEpoch = epoch)
            if (resResult.isSuccess && targetIdentity.value.epoch == epoch) {
                val byteVal = resResult.getOrNull()?.firstOrNull()?.toInt() ?: -1
                _resolutionDisplay.value = DashCamSettingMappings.getVideoResolutionName(byteVal)
            } else if (targetIdentity.value.epoch == epoch) {
                _resolutionDisplay.value = "讀取失敗"
            }

            // 2. Loop Duration
            val loopResult = client.getParameter(GPSocketProtocol.PARAM_LOOP_RECORDING, expectedEpoch = epoch)
            if (loopResult.isSuccess && targetIdentity.value.epoch == epoch) {
                val byteVal = loopResult.getOrNull()?.firstOrNull()?.toInt() ?: -1
                _loopDisplay.value = DashCamSettingMappings.getLoopRecordingName(byteVal)
                _currentLoopSettingRaw.value = byteVal
                updateStorageEstimateFromStatus(client.deviceStatus.value)
            } else if (targetIdentity.value.epoch == epoch) {
                _loopDisplay.value = "讀取失敗"
                _currentLoopSettingRaw.value = -1
                updateStorageEstimateFromStatus(client.deviceStatus.value)
            }

            // 3. Exposure
            val expResult = client.getParameter(GPSocketProtocol.PARAM_EXPOSURE, expectedEpoch = epoch)
            if (expResult.isSuccess && targetIdentity.value.epoch == epoch) {
                val byteVal = expResult.getOrNull()?.firstOrNull()?.toInt() ?: 0
                val expVal = byteVal / 3.0f
                _exposureValue.value = expVal
                _exposureDisplay.value = String.format(java.util.Locale.US, "%+.1f", expVal)
            } else if (targetIdentity.value.epoch == epoch) {
                _exposureDisplay.value = "讀取失敗"
            }

            // 4. Watermark
            val wmResult = client.getParameter(GPSocketProtocol.PARAM_WATERMARK, expectedEpoch = epoch)
            if (wmResult.isSuccess && targetIdentity.value.epoch == epoch) {
                val byteVal = wmResult.getOrNull()?.firstOrNull()?.toInt() ?: -1
                _watermarkDisplay.value = DashCamSettingMappings.getWatermarkName(byteVal)
            } else if (targetIdentity.value.epoch == epoch) {
                _watermarkDisplay.value = "讀取失敗"
            }

            // 5. Capture Resolution
            val capResResult = client.getParameter(GPSocketProtocol.PARAM_CAPTURE_RESOLUTION, expectedEpoch = epoch)
            if (capResResult.isSuccess && targetIdentity.value.epoch == epoch) {
                val byteVal = capResResult.getOrNull()?.firstOrNull()?.toInt() ?: -1
                _captureResolutionDisplay.value = DashCamSettingMappings.getCaptureResolutionName(byteVal)
            } else if (targetIdentity.value.epoch == epoch) {
                _captureResolutionDisplay.value = "讀取失敗"
            }

            // 6. Capture Quality
            val capQualResult = client.getParameter(GPSocketProtocol.PARAM_CAPTURE_QUALITY, expectedEpoch = epoch)
            if (capQualResult.isSuccess && targetIdentity.value.epoch == epoch) {
                val byteVal = capQualResult.getOrNull()?.firstOrNull()?.toInt() ?: -1
                _captureQualityDisplay.value = DashCamSettingMappings.getCaptureQualityName(byteVal)
            } else if (targetIdentity.value.epoch == epoch) {
                _captureQualityDisplay.value = "讀取失敗"
            }
        }
    }

    private fun loadPersistedDevices() {
        val rawJson = prefs.getString("saved_devices", null)
        if (rawJson != null) {
            try {
                val jsonArray = org.json.JSONArray(rawJson)
                val list = mutableListOf<DashCamDeviceRecord>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(
                        DashCamDeviceRecord(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            host = obj.getString("host"),
                            ssid = obj.optString("ssid", ""),
                            bssid = obj.optString("bssid", ""),
                            firmwareVersion = obj.optString("firmwareVersion", "未知"),
                            lastConnectedTime = obj.optString("lastConnectedTime", ""),
                            isSelected = obj.optBoolean("isSelected", false)
                        )
                    )
                }
                _registeredDevices.value = list
                val selected = list.firstOrNull { it.isSelected } ?: list.firstOrNull()
                if (selected != null) {
                    _deviceHost.value = selected.host
                }
                return
            } catch (e: Exception) {
                android.util.Log.w("DashCamViewModel", "Failed to parse saved devices: ${e.message}")
            }
        }
        _registeredDevices.value = emptyList()
    }

    private fun savePersistedDevices(list: List<DashCamDeviceRecord>) {
        try {
            val jsonArray = org.json.JSONArray()
            for (dev in list) {
                val obj = org.json.JSONObject()
                obj.put("id", dev.id)
                obj.put("name", dev.name)
                obj.put("host", dev.host)
                obj.put("ssid", dev.ssid)
                obj.put("bssid", dev.bssid)
                obj.put("firmwareVersion", dev.firmwareVersion)
                obj.put("lastConnectedTime", dev.lastConnectedTime)
                obj.put("isSelected", dev.isSelected)
                jsonArray.put(obj)
            }
            prefs.edit().putString("saved_devices", jsonArray.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("DashCamViewModel", "Failed to save devices: ${e.message}")
        }
    }

    fun autoMatchOrAddDeviceForHost(host: String, protocolHeader: String = "GPSOCK") {
        val currentSsid = networkMonitor.getWifiSsid() ?: ""
        val currentBssid = networkMonitor.getWifiBssid() ?: ""
        val currentList = _registeredDevices.value

        // N03: 以 BSSID / SSID 作為主鍵，隔離相同熱點 IP (如 192.168.25.1) 的不同記錄器設備與快取
        val existing = currentList.find { dev ->
            (currentBssid.isNotEmpty() && dev.bssid.isNotEmpty() && dev.bssid.equals(currentBssid, ignoreCase = true)) ||
            (currentSsid.isNotEmpty() && dev.ssid.isNotEmpty() && dev.ssid == currentSsid) ||
            (dev.bssid.isEmpty() && dev.ssid.isEmpty() && dev.host == host)
        }

        if (existing != null) {
            val updatedExisting = existing.copy(
                host = host,
                ssid = if (currentSsid.isNotEmpty()) currentSsid else existing.ssid,
                bssid = if (currentBssid.isNotEmpty()) currentBssid else existing.bssid,
                isSelected = true
            )
            val updatedList = currentList.map { dev ->
                if (dev.id == existing.id) updatedExisting else dev.copy(isSelected = false)
            }
            _registeredDevices.value = updatedList
            savePersistedDevices(updatedList)
            setDeviceHost(host)
        } else {
            val devId = if (currentBssid.isNotEmpty()) {
                "DEV_${currentBssid.replace(":", "")}"
            } else if (currentSsid.isNotEmpty()) {
                "DEV_${currentSsid.replace(" ", "_")}"
            } else {
                "Aladdin-${host.replace(".", "_")}-${System.currentTimeMillis() % 10000}"
            }
            val devName = if (currentSsid.isNotEmpty()) currentSsid else "Aladdin DashCam ($host)"
            val newDev = DashCamDeviceRecord(
                id = devId,
                name = devName,
                host = host,
                ssid = currentSsid,
                bssid = currentBssid,
                firmwareVersion = "20260513 V1.1.0",
                lastConnectedTime = "",
                isSelected = true
            )
            val updated = currentList.map { it.copy(isSelected = false) } + newDev
            _registeredDevices.value = updated
            savePersistedDevices(updated)
            setDeviceHost(host)
        }
    }

    fun updateDeviceLastConnected(host: String) {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _registeredDevices.update { list ->
            val currentSelected = list.firstOrNull { it.isSelected }
            val target = if (currentSelected != null && currentSelected.host == host) {
                currentSelected
            } else {
                list.firstOrNull { it.host == host }
            }
            if (target == null) return@update list
            val updated = list.map { dev ->
                if (dev.id == target.id) {
                    dev.copy(lastConnectedTime = now, isSelected = true)
                } else {
                    dev.copy(isSelected = false)
                }
            }
            savePersistedDevices(updated)
            updated
        }
    }

    fun selectDevice(device: DashCamDeviceRecord) {
        _registeredDevices.update { list ->
            val updated = list.map { it.copy(isSelected = (it.id == device.id)) }
            savePersistedDevices(updated)
            updated
        }
        setDeviceHost(device.host)
    }

    fun addDevice(name: String, host: String) {
        val newDev = DashCamDeviceRecord(
            id = name,
            name = name,
            host = host,
            firmwareVersion = "未知",
            lastConnectedTime = "",
            isSelected = false
        )
        _registeredDevices.update {
            val updated = it + newDev
            savePersistedDevices(updated)
            updated
        }
    }

    fun removeDevice(deviceId: String) {
        _registeredDevices.update {
            val updated = it.filter { dev -> dev.id != deviceId }
            savePersistedDevices(updated)
            updated
        }
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
                updateStorageEstimateFromStatus(client.deviceStatus.value)
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
            val realDuration = if (item.isVideo) extractMediaDurationSeconds(destFile) else 0
            if (realDuration > 0) {
                _deviceMediaList.update { list ->
                    list.map {
                        if (it.fileName == item.fileName) it.copy(durationSeconds = realDuration) else it
                    }
                }
            }
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
                val realDuration = if (item.isVideo) extractMediaDurationSeconds(destFile) else 0
                if (generation == 0L || downloadGenerations[item.fileName] == generation) {
                    _deviceMediaList.update { list ->
                        list.map {
                            if (it.fileName == item.fileName) {
                                it.copy(
                                    isDownloaded = true,
                                    durationSeconds = if (realDuration > 0) realDuration else it.durationSeconds
                                )
                            } else it
                        }
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
            val realDuration = if (item.isVideo) extractMediaDurationSeconds(destFile) else 0
            if (generation == 0L || downloadGenerations[item.fileName] == generation) {
                _deviceMediaList.update { list ->
                    list.map {
                        if (it.fileName == item.fileName) {
                            it.copy(
                                isDownloaded = true,
                                durationSeconds = if (realDuration > 0) realDuration else it.durationSeconds
                            )
                        } else it
                    }
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
        val currentDev = _registeredDevices.value.firstOrNull { it.isSelected }
        val deviceKey = (currentDev?.id ?: (targetIdentity.value.activeHost ?: targetIdentity.value.requestedHost)).replace(Regex("[^a-zA-Z0-9_]"), "_")
        val thumbFile = File(thumbDir, "thumb_${deviceKey}_${item.fileName}.jpg")
        if (thumbFile.exists() && thumbFile.length() > 0) {
            return@withContext thumbFile
        }

        // 本機已下載或 LOCAL_ 項目直接於背景線程解碼，不發送遠端 GET（R10 閉合）
        if (item.isDownloaded || item.id.startsWith("LOCAL_")) {
            val moviesDir = getLocalMoviesDir()
            val localFile = File(moviesDir, item.fileName)
            if (localFile.exists() && localFile.length() > 0) {
                val ok = generateLocalThumbnail(localFile, thumbFile, item.isVideo)
                if (ok) return@withContext thumbFile
            }
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

    private fun generateLocalThumbnail(sourceFile: File, destThumbFile: File, isVideo: Boolean): Boolean {
        return try {
            val bitmap: android.graphics.Bitmap? = if (isVideo) {
                var retriever: MediaMetadataRetriever? = null
                try {
                    retriever = MediaMetadataRetriever()
                    retriever.setDataSource(sourceFile.absolutePath)
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever?.release()
                }
            } else {
                android.graphics.BitmapFactory.decodeFile(sourceFile.absolutePath)
            }
            if (bitmap != null) {
                java.io.FileOutputStream(destThumbFile).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                }
                bitmap.recycle()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
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
