package com.aladdin.dashcam.ui

import android.app.Application
import com.aladdin.dashcam.R
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aladdin.dashcam.gps.GpsSpeedometer
import com.aladdin.dashcam.model.ConnectionStatus
import com.aladdin.dashcam.model.DeviceMode
import com.aladdin.dashcam.model.DashCamDeviceStatus
import com.aladdin.dashcam.model.MediaItem
import com.aladdin.dashcam.model.RecordingStatus
import com.aladdin.dashcam.model.DashCamSettingMappings
import com.aladdin.dashcam.network.DashCamClient
import com.aladdin.dashcam.network.NetworkMonitor
import com.aladdin.dashcam.network.TargetIdentity
import com.aladdin.dashcam.network.TargetState
import com.aladdin.dashcam.protocol.GPSocketProtocol
import com.aladdin.dashcam.driver.DriverRegistry
import com.aladdin.dashcam.driver.DriverProbeResult
import com.aladdin.dashcam.driver.RecorderProtocol
import com.aladdin.dashcam.media.MediaCacheManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.combine
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

typealias DownloadStatus = com.aladdin.dashcam.media.DownloadStatus
typealias DownloadTask = com.aladdin.dashcam.media.DownloadTask

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
) {
    fun matchesWifi(currentBssid: String): Boolean =
        currentBssid.isNotBlank() && currentBssid != "02:00:00:00:00:00" &&
            bssid.isNotBlank() && bssid.equals(currentBssid, ignoreCase = true)

    fun cacheDirectoryName(): String = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(id.toByteArray(Charsets.UTF_8))
}

class DashCamViewModel(application: Application) : AndroidViewModel(application) {
    private var displayLocale = AppAppearance.localizedContext(application).resources.configuration.locales.toLanguageTags()
    private fun getString(id: Int, vararg args: Any?): String =
        AppAppearance.localizedContext(getApplication()).let {
            if (args.isEmpty()) it.getString(id) else it.getString(id, *args)
        }

    fun refreshLocalizedText() {
        val locale = AppAppearance.localizedContext(getApplication()).resources.configuration.locales.toLanguageTags()
        if (locale == displayLocale) return
        displayLocale = locale
        val placeholder = getString(if (connectionStatus.value == ConnectionStatus.CONNECTED)
            R.string.recorder_reading else R.string.device_status_disconnected)
        _resolutionDisplay.value = placeholder
        _loopDisplay.value = placeholder
        _captureResolutionDisplay.value = placeholder
        _captureQualityDisplay.value = placeholder
        _exposureDisplay.value = placeholder
        _watermarkDisplay.value = placeholder
        _audioRecordingDisplay.value = if (connectionStatus.value == ConnectionStatus.CONNECTED)
            getString(if (deviceStatus.value.audioEnabled) R.string.device_on else R.string.setting_off) else placeholder
        if (connectionStatus.value == ConnectionStatus.CONNECTED) refreshSettings()
        else {
            _firmwareVersionDisplay.value = placeholder
            _wifiSsidDisplay.value = placeholder
        }
        updateStorageEstimateFromStatus(deviceStatus.value)
    }


    val client = DashCamClient(scope = viewModelScope)
    val networkMonitor = NetworkMonitor(application)
    val gpsSpeedometer = GpsSpeedometer(application)
    val mediaCacheManager = MediaCacheManager()

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

    private val downloadRepository = com.aladdin.dashcam.media.DownloadRepository(application, viewModelScope)
    private val _downloadTasks = downloadRepository.tasks
    val downloadTasks: StateFlow<List<DownloadTask>> = _downloadTasks.asStateFlow()
    val downloadWorkCount = downloadRepository.workCount.asStateFlow()
    private val downloadGenerations = downloadRepository.generations

    private val _openMediaItemEvent = Channel<MediaItem>(Channel.CONFLATED)
    val openMediaItemEvent = _openMediaItemEvent.receiveAsFlow()

    fun requestOpenMedia(item: MediaItem) {
        _openMediaItemEvent.trySend(item)
    }

    val wifiNetwork = networkMonitor.wifiNetwork
    private val _deviceHost = MutableStateFlow("192.168.25.1")
    val deviceHost: StateFlow<String> = _deviceHost.asStateFlow()

    private val _resolutionDisplay = MutableStateFlow(getString(R.string.device_status_disconnected))
    val resolutionDisplay: StateFlow<String> = _resolutionDisplay.asStateFlow()

    private val _loopDisplay = MutableStateFlow(getString(R.string.device_status_disconnected))
    val loopDisplay: StateFlow<String> = _loopDisplay.asStateFlow()

    // 儲存真實回讀的循環錄影參數 (0=Off, 1=1m, 2=2m, 3=3m, 4=5m, -1=未確認)
    private val _currentLoopSettingRaw = MutableStateFlow<Int?>(-1)
    val currentLoopSettingRaw: StateFlow<Int?> = _currentLoopSettingRaw.asStateFlow()

    private val _captureResolutionDisplay = MutableStateFlow(getString(R.string.device_status_disconnected))
    val captureResolutionDisplay: StateFlow<String> = _captureResolutionDisplay.asStateFlow()

    private val _captureQualityDisplay = MutableStateFlow(getString(R.string.device_status_disconnected))
    val captureQualityDisplay: StateFlow<String> = _captureQualityDisplay.asStateFlow()

    private val _exposureDisplay = MutableStateFlow(getString(R.string.device_status_disconnected))
    val exposureDisplay: StateFlow<String> = _exposureDisplay.asStateFlow()

    private val _exposureProgress = MutableStateFlow(6)
    val exposureProgress: StateFlow<Int> = _exposureProgress.asStateFlow()

    private val _exposureValue = MutableStateFlow(0.0f)
    val exposureValue: StateFlow<Float> = _exposureValue.asStateFlow()

    private val _watermarkDisplay = MutableStateFlow(getString(R.string.device_status_disconnected))
    val watermarkDisplay: StateFlow<String> = _watermarkDisplay.asStateFlow()

    private val _audioRecordingDisplay = MutableStateFlow(getString(R.string.device_status_disconnected))
    val audioRecordingDisplay: StateFlow<String> = _audioRecordingDisplay.asStateFlow()

    private val _firmwareVersionDisplay = MutableStateFlow(getString(R.string.device_status_disconnected))
    val firmwareVersionDisplay: StateFlow<String> = _firmwareVersionDisplay.asStateFlow()

    private val _wifiSsidDisplay = MutableStateFlow(getString(R.string.device_status_disconnected))
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
        ALBUM_PLAYBACK,
        DOWNLOAD
    }

    private val _currentStreamOwner = MutableStateFlow(MediaStreamOwner.NONE)
    val currentStreamOwner: StateFlow<MediaStreamOwner> = _currentStreamOwner.asStateFlow()

    @Synchronized fun requestStreamOwner(owner: MediaStreamOwner): Boolean {
        val current = _currentStreamOwner.value
        if (current != MediaStreamOwner.NONE && current != owner &&
            !(current == MediaStreamOwner.LIVE_PREVIEW && owner != MediaStreamOwner.NONE)) return false
        _currentStreamOwner.value = owner
        return true
    }

    @Synchronized fun releaseStreamOwner(owner: MediaStreamOwner) {
        if (_currentStreamOwner.value == owner) {
            _currentStreamOwner.value = MediaStreamOwner.NONE
        }
    }

    private val _isRemotePlaybackActive = MutableStateFlow(false)
    val isRemotePlaybackActive: StateFlow<Boolean> = _isRemotePlaybackActive.asStateFlow()

    private val _currentPlaybackItem = MutableStateFlow<MediaItem?>(null)
    val currentPlaybackItem: StateFlow<MediaItem?> = _currentPlaybackItem.asStateFlow()

    val playbackSession = com.aladdin.dashcam.media.RemotePlaybackSession(
        acquire = { requestStreamOwner(MediaStreamOwner.ALBUM_PLAYBACK) },
        release = { releaseStreamOwner(MediaStreamOwner.ALBUM_PLAYBACK) },
        network = { wifiNetwork.value },
        duration = { item -> withContext(Dispatchers.IO) {
            if (item.durationSeconds > 0) item.durationSeconds.toLong() * 1000L
            else getOrFetchThumbnail(item)?.let { file -> runCatching {
                val header = ByteArray(33)
                java.io.DataInputStream(file.inputStream()).use { it.readFully(header) }
                GPSocketProtocol.parseThumbnailDurationSeconds(header) * 1000L
            }.getOrDefault(0L) } ?: 0L
        } },
        restart = { restartStreaming() }, start = { item, sent -> startRemotePlayback(item, sent) },
        stop = { stopRemotePlayback() }, source = { "rtsp://${deviceHost.value}:8080/?action=stream" }
    )

    @Volatile
    private var lastSyncedSessionId: Long = 0L

    init {
        loadPersistedDevices()
        networkMonitor.startMonitoring()
        viewModelScope.launch {
            combine(networkMonitor.wifiNetwork, networkMonitor.wifiLinkRevision) { network, _ -> network }.collect { network ->
                probeJob?.cancel()
                withContext(Dispatchers.IO) { client.bindNetwork(network) }
                if (network != null) {
                    probeJob = launch(Dispatchers.IO) probe@{
                        delay(350)
                        val gwIp = networkMonitor.getWifiGatewayIp() ?: "192.168.25.1"
                        _probeStatus.value = ProbeStatus.Probing(gwIp)
                        var probeError: Throwable? = null
                        val identified = DriverRegistry.probeAndIdentify(gwIp, network, generalPlusProbe = { endpoint ->
                            val result = client.probeDevice(endpoint.host, network)
                            when {
                                result.getOrNull() == true -> DriverProbeResult.Matched(RecorderProtocol.GENERALPLUS_GPSOCKET, "GPSOCK")
                                result.isFailure -> DriverProbeResult.Error(result.exceptionOrNull()!!).also { probeError = it.throwable }
                                else -> DriverProbeResult.Mismatched("Not GPSocket")
                            }
                        })
                        if (!isActive) return@probe
                        if (identified?.protocol == RecorderProtocol.GENERALPLUS_GPSOCKET) {
                            _probeStatus.value = ProbeStatus.Success(gwIp, identified.rawSignature)
                            autoMatchOrAddDeviceForHost(gwIp, identified.rawSignature)
                        } else if (probeError != null) {
                            _probeStatus.value = ProbeStatus.Failed(gwIp, probeError?.message)
                        } else _probeStatus.value = ProbeStatus.NotDashCam(gwIp)
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
                    if (session != lastSyncedSessionId && currentStreamOwner.value != MediaStreamOwner.ALBUM_PLAYBACK &&
                        android.os.SystemClock.elapsedRealtime() - lastSessionInitializationAt >= 10_000L) {
                        lastSyncedSessionId = session
                        lastSessionInitializationAt = android.os.SystemClock.elapsedRealtime()
                        // 新連線成功建立 Session，自動執行一次校時（R06）
                        performAutoSyncRtc(identity.epoch)
                        // 同步發起真實設定值回讀（R05）
                        refreshSettings()
                        updateDeviceLastConnected(identity.requestedHost)
                    }
                } else if (identity.state == TargetState.DISCONNECTED) {
                    _resolutionDisplay.value = getString(R.string.device_status_disconnected)
                    _loopDisplay.value = getString(R.string.device_status_disconnected)
                    _captureResolutionDisplay.value = getString(R.string.device_status_disconnected)
                    _captureQualityDisplay.value = getString(R.string.device_status_disconnected)
                    _exposureDisplay.value = getString(R.string.device_status_disconnected)
                    _exposureProgress.value = 6
                    _watermarkDisplay.value = getString(R.string.device_status_disconnected)
                    _audioRecordingDisplay.value = getString(R.string.device_status_disconnected)
                    _firmwareVersionDisplay.value = getString(R.string.device_status_disconnected)
                    _wifiSsidDisplay.value = getString(R.string.device_status_disconnected)
                }
            }
        }
        viewModelScope.launch {
            client.deviceStatus.collect { status ->
                recordingTimerInterpolator.onDeviceStatus(status, targetIdentity.value.epoch)
                _timerTickTrigger.tryEmit(Unit)
                updateStorageEstimateFromStatus(status)
                if (status.audioEnabled) {
                    _audioRecordingDisplay.value = getString(R.string.device_on)
                } else if (status.lastUpdatedTimestamp > 0) {
                    _audioRecordingDisplay.value = getString(R.string.setting_off)
                }
            }
        }
        client.start()
    }

    private var lastSessionInitializationAt = -10_000L
    private var autoSyncJob: Job? = null
    private var settingsRefreshJob: Job? = null

    private fun performAutoSyncRtc(epoch: Long) {
        if (autoSyncJob?.isActive == true) return
        autoSyncJob = viewModelScope.launch(Dispatchers.IO) {
            val res = client.syncRtc(expectedEpoch = epoch)
            if (epoch != targetIdentity.value.epoch) {
                return@launch
            }
            val now = System.currentTimeMillis()
            _rtcSyncState.value = RtcSyncState(
                lastAttemptTime = now,
                isAuto = true,
                isSuccess = res.isSuccess,
                message = if (res.isSuccess) getString(R.string.recorder_time_synced_automatically) else getString(R.string.recorder_automatic_time_sync_failed_s, res.exceptionOrNull()?.message)
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
                message = if (res.isSuccess) getString(R.string.recorder_time_synced) else getString(R.string.recorder_time_sync_failed_s, res.exceptionOrNull()?.message)
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
            0 -> getString(R.string.recorder_continuous_recording_no_loop)
            1 -> getString(R.string.recorder_loop_recording_min)
            2 -> getString(R.string.recorder_loop_recording_min_007)
            3 -> getString(R.string.recorder_loop_recording_min_008)
            4 -> getString(R.string.recorder_loop_recording_min_009)
            else -> getString(R.string.recorder_recording_mode_loop_setting_unconfirmed)
        }

        val listedMediaBytes = _deviceMediaList.value.sumOf { it.sizeBytes }

        if (sdCardState == 0) {
            _storageEstimate.value = StorageEstimate(
                hasSdCard = false,
                canDeleteFile = canDelete,
                isNormal = false,
                listedMediaTotalBytes = listedMediaBytes,
                statusText = getString(R.string.live_no_memory_card_detected)
            )
            return
        }

        if (isCardError) {
            _storageEstimate.value = StorageEstimate(
                hasSdCard = true,
                canDeleteFile = canDelete,
                isNormal = false,
                listedMediaTotalBytes = listedMediaBytes,
                statusText = getString(R.string.recorder_memory_card_error_or_formatting_required, sdCardState)
            )
            return
        }

        if (!canDelete && sdCardState != 1) {
            _storageEstimate.value = StorageEstimate(
                hasSdCard = false,
                canDeleteFile = false,
                isNormal = false,
                listedMediaTotalBytes = listedMediaBytes,
                statusText = getString(R.string.recorder_memory_card_not_ready_or_missing)
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
                    getString(R.string.recorder_s_card_ready_listed_media_f),
                    loopModePrefix,
                    mediaMb
                )
            } else {
                String.format(
                    java.util.Locale.TAIWAN,
                    getString(R.string.recorder_s_card_ready_loop_recording),
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
                getString(R.string.recorder_s_f_gb_free_calculating_time),
                loopModePrefix,
                free / (1024.0 * 1024.0 * 1024.0)
            )
        } else if (loopRaw != null && loopRaw > 0) {
            String.format(
                java.util.Locale.TAIWAN,
                getString(R.string.recorder_s_overwrite_in_about_d_min),
                loopModePrefix,
                minutesUntilOverwrite,
                retentionHours
            )
        } else {
            String.format(
                java.util.Locale.TAIWAN,
                getString(R.string.recorder_s_about_d_min_recording_time),
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
                _resolutionDisplay.value = getString(R.string.recorder_not_read_yet)
                _loopDisplay.value = getString(R.string.recorder_not_read_yet)
                _currentLoopSettingRaw.value = -1
                _deviceMediaList.value = emptyList()
                _albumQueryState.value = AlbumQueryState.Idle
                val newEpoch = client.updateHost(newHost)
                _deviceHost.value = newHost
                _hostSwitchEvent.emit(HostSwitchEvent.Success(newHost, newEpoch))
            } catch (e: Exception) {
                _hostSwitchEvent.emit(HostSwitchEvent.Failure(newHost, e.message ?: getString(R.string.recorder_unable_to_switch)))
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
        return client.takePhoto(targetIdentity.value.epoch)
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
                _resolutionDisplay.value = getString(R.string.recorder_s_confirming, displayName)
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(250)
                    val check = client.getParameter(GPSocketProtocol.PARAM_RESOLUTION, expectedEpoch = epoch)
                    if (targetIdentity.value.epoch == epoch) {
                        if (check.isSuccess) {
                            val actual = check.getOrNull()?.firstOrNull()?.toInt()
                            _resolutionDisplay.value = if (actual == resRawValue) displayName else getString(R.string.recorder_read_back_mismatch_s, DashCamSettingMappings.getVideoResolutionName(actual ?: -1, AppAppearance.localizedContext(getApplication())))
                        } else {
                            _resolutionDisplay.value = getString(R.string.recorder_s_sent_read_back_failed, displayName)
                        }
                    }
                }
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _resolutionDisplay.value = getString(R.string.recorder_setting_failed_s, result.exceptionOrNull()?.message)
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
                _loopDisplay.value = getString(R.string.recorder_s_confirming, displayName)
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
                                _loopDisplay.value = getString(R.string.recorder_read_back_mismatch_s, DashCamSettingMappings.getLoopRecordingName(actual ?: -1, AppAppearance.localizedContext(getApplication())))
                            }
                        } else {
                            _loopDisplay.value = getString(R.string.recorder_s_sent_read_back_failed, displayName)
                        }
                    }
                }
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _loopDisplay.value = getString(R.string.recorder_setting_failed_s, result.exceptionOrNull()?.message)
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
                _captureResolutionDisplay.value = getString(R.string.recorder_s_confirming, displayName)
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(250)
                    val check = client.getParameter(GPSocketProtocol.PARAM_CAPTURE_RESOLUTION, expectedEpoch = epoch)
                    if (targetIdentity.value.epoch == epoch) {
                        if (check.isSuccess) {
                            val actual = check.getOrNull()?.firstOrNull()?.toInt()
                            _captureResolutionDisplay.value = if (actual == resRawValue) displayName else getString(R.string.recorder_read_back_mismatch_s, DashCamSettingMappings.getCaptureResolutionName(actual ?: -1, AppAppearance.localizedContext(getApplication())))
                        } else {
                            _captureResolutionDisplay.value = getString(R.string.recorder_s_sent_read_back_failed, displayName)
                        }
                    }
                }
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _captureResolutionDisplay.value = getString(R.string.recorder_setting_failed_s, result.exceptionOrNull()?.message)
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
                _captureQualityDisplay.value = getString(R.string.recorder_s_confirming, displayName)
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(250)
                    val check = client.getParameter(GPSocketProtocol.PARAM_CAPTURE_QUALITY, expectedEpoch = epoch)
                    if (targetIdentity.value.epoch == epoch) {
                        if (check.isSuccess) {
                            val actual = check.getOrNull()?.firstOrNull()?.toInt()
                            _captureQualityDisplay.value = if (actual == qualityRawValue) displayName else getString(R.string.recorder_read_back_mismatch_s, DashCamSettingMappings.getCaptureQualityName(actual ?: -1, AppAppearance.localizedContext(getApplication())))
                        } else {
                            _captureQualityDisplay.value = getString(R.string.recorder_s_sent_read_back_failed, displayName)
                        }
                    }
                }
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _captureQualityDisplay.value = getString(R.string.recorder_setting_failed_s, result.exceptionOrNull()?.message)
            }
        }
        return result
    }

    suspend fun setExposureByRaw(rawVal: Int, displayName: String, progress: Int): Result<Unit> {
        val currentIdentity = targetIdentity.value
        val epoch = currentIdentity.epoch
        val result = client.setParameter(
            GPSocketProtocol.PARAM_EXPOSURE,
            byteArrayOf(rawVal.toByte()),
            expectedEpoch = epoch
        )
        if (result.isSuccess) {
            if (targetIdentity.value.epoch == epoch) {
                _exposureProgress.value = progress
                _exposureDisplay.value = getString(R.string.recorder_s_confirming, displayName)
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(250)
                    val check = client.getParameter(GPSocketProtocol.PARAM_EXPOSURE, expectedEpoch = epoch)
                    if (targetIdentity.value.epoch == epoch) {
                        if (check.isSuccess) {
                            val actual = check.getOrNull()?.firstOrNull()?.toInt()
                            if (actual == rawVal) {
                                _exposureDisplay.value = displayName
                                _exposureProgress.value = DashCamSettingMappings.exposureRawToProgress(actual)
                            } else {
                                val actualName = actual?.let { DashCamSettingMappings.getExposureName(it, AppAppearance.localizedContext(getApplication())) } ?: actual.toString()
                                _exposureDisplay.value = getString(R.string.recorder_read_back_s, actualName)
                                if (actual != null) {
                                    _exposureProgress.value = DashCamSettingMappings.exposureRawToProgress(actual)
                                }
                            }
                        } else {
                            _exposureDisplay.value = getString(R.string.recorder_s_sent_read_back_failed, displayName)
                        }
                    }
                }
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _exposureDisplay.value = getString(R.string.recorder_setting_failed_s, result.exceptionOrNull()?.message)
            }
        }
        return result
    }

    suspend fun setExposure(exposureVal: Float): Result<Unit> {
        val progress = kotlin.math.round(exposureVal * 3f + 6f).toInt().coerceIn(0, 12)
        val rawVal = DashCamSettingMappings.exposureProgressToRaw(progress)
        val displayName = DashCamSettingMappings.getExposureName(rawVal, AppAppearance.localizedContext(getApplication()))
        return setExposureByRaw(rawVal, displayName, progress)
    }

    suspend fun setWatermark(rawVal: Int, displayName: String = DashCamSettingMappings.getWatermarkName(rawVal, AppAppearance.localizedContext(getApplication()))): Result<Unit> {
        val currentIdentity = targetIdentity.value
        val epoch = currentIdentity.epoch
        val result = client.setParameter(
            GPSocketProtocol.PARAM_WATERMARK,
            byteArrayOf(rawVal.toByte()),
            expectedEpoch = epoch
        )
        if (result.isSuccess) {
            if (targetIdentity.value.epoch == epoch) {
                _watermarkDisplay.value = getString(R.string.recorder_s_confirming, displayName)
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(250)
                    val check = client.getParameter(GPSocketProtocol.PARAM_WATERMARK, expectedEpoch = epoch)
                    if (targetIdentity.value.epoch == epoch) {
                        if (check.isSuccess) {
                            val actual = check.getOrNull()?.firstOrNull()?.toInt()
                            _watermarkDisplay.value = if (actual == rawVal) displayName else getString(R.string.recorder_read_back_mismatch_s, DashCamSettingMappings.getWatermarkName(actual ?: -1, AppAppearance.localizedContext(getApplication())))
                        } else {
                            _watermarkDisplay.value = getString(R.string.recorder_s_sent_read_back_failed, displayName)
                        }
                    }
                }
            }
        } else {
            if (targetIdentity.value.epoch == epoch) {
                _watermarkDisplay.value = getString(R.string.recorder_setting_failed_s, result.exceptionOrNull()?.message)
            }
        }
        return result
    }
    suspend fun toggleAudioRecording(): Result<Unit> {
        val epoch = targetIdentity.value.epoch
        val nextState = !deviceStatus.value.audioEnabled
        val result = client.toggleAudio(expectedEpoch = epoch)
        if (result.isSuccess) {
            _audioRecordingDisplay.value = if (nextState) getString(R.string.device_on) else getString(R.string.setting_off)
        }
        return result
    }

    suspend fun startRemotePlayback(item: MediaItem, onRequestSent: (() -> Unit)? = null): Result<Unit> {
        if (!isCurrentMediaSource(item)) return Result.failure(IllegalStateException(getString(R.string.recorder_video_source_changed)))
        _currentPlaybackItem.value = item
        val epoch = item.sourceEpoch
        val res = client.startPlayback(item.deviceIndex, expectedEpoch = epoch, onRequestSent = onRequestSent)
        if (res.isSuccess) {
            _isRemotePlaybackActive.value = true
        }
        return res
    }

    suspend fun toggleRemotePlaybackPause(item: MediaItem): Result<Unit> {
        if (!isCurrentMediaSource(item)) return Result.failure(IllegalStateException(getString(R.string.recorder_video_source_changed)))
        return client.togglePlaybackPause(item.deviceIndex, item.sourceEpoch)
    }

    private val formatStorageMutex = Mutex()
    private val _isFormattingStorage = MutableStateFlow(false)
    val isFormattingStorage: StateFlow<Boolean> = _isFormattingStorage.asStateFlow()

    suspend fun powerOffRecorder(expectedEpoch: Long): Result<Unit> =
        client.powerOff(expectedEpoch)

    suspend fun formatRecorderStorage(expectedEpoch: Long): Result<Unit> = withContext(kotlinx.coroutines.NonCancellable) {
        if (!formatStorageMutex.tryLock()) return@withContext Result.failure(IllegalStateException(getString(R.string.recorder_formatting_in_progress_do_not_repeat)))
        _isFormattingStorage.value = true
        try {
            if (expectedEpoch != targetIdentity.value.epoch) return@withContext Result.failure(IllegalStateException(getString(R.string.recorder_recorder_changed_please_confirm_again)))
            val result = client.formatStorage(expectedEpoch)
            if (expectedEpoch == targetIdentity.value.epoch) {
                _deviceMediaList.value = emptyList()
                _albumQueryState.value = AlbumQueryState.Idle
                fetchRemoteFileList()
            }
            result
        } finally {
            _isFormattingStorage.value = false
            formatStorageMutex.unlock()
        }
    }

    suspend fun stopRemotePlayback(): Result<Unit> {
        val epoch = targetIdentity.value.epoch
        val res = client.stopPlayback(expectedEpoch = epoch)
        _isRemotePlaybackActive.value = false
        _currentPlaybackItem.value = null
        return res
    }

    private val albumModeMutex = Mutex()

    suspend fun enterRemoteAlbumBrowsing(): Result<Unit> = albumModeMutex.withLock {
        client.enterAlbumBrowsing(targetIdentity.value.epoch)
    }

    suspend fun exitRemoteAlbumBrowsing(): Result<Unit> = albumModeMutex.withLock {
        client.exitAlbumBrowsing(targetIdentity.value.epoch)
    }

    suspend fun deleteRemoteMedia(item: MediaItem): Result<Unit> {
        if (!isCurrentMediaSource(item)) return Result.failure(IllegalStateException(getString(R.string.recorder_video_source_changed)))
        val epoch = item.sourceEpoch
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
            if (!isCurrentMediaSource(item)) return Result.failure(IllegalStateException(getString(R.string.recorder_video_source_changed)))
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
        if (settingsRefreshJob?.isActive == true) return
        val epoch = targetIdentity.value.epoch
        settingsRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            _resolutionDisplay.value = getString(R.string.recorder_reading)
            _loopDisplay.value = getString(R.string.recorder_reading)
            _exposureDisplay.value = getString(R.string.recorder_reading)
            _watermarkDisplay.value = getString(R.string.recorder_reading)
            _captureResolutionDisplay.value = getString(R.string.recorder_reading)
            _captureQualityDisplay.value = getString(R.string.recorder_reading)

            // 1. Resolution
            val resResult = client.getParameter(GPSocketProtocol.PARAM_RESOLUTION, expectedEpoch = epoch)
            if (resResult.isSuccess && targetIdentity.value.epoch == epoch) {
                val byteVal = resResult.getOrNull()?.firstOrNull()?.toInt() ?: -1
                _resolutionDisplay.value = DashCamSettingMappings.getVideoResolutionName(byteVal, AppAppearance.localizedContext(getApplication()))
            } else if (targetIdentity.value.epoch == epoch) {
                _resolutionDisplay.value = getString(R.string.recorder_unable_to_read)
            }

            // 2. Loop Duration
            val loopResult = client.getParameter(GPSocketProtocol.PARAM_LOOP_RECORDING, expectedEpoch = epoch)
            if (loopResult.isSuccess && targetIdentity.value.epoch == epoch) {
                val byteVal = loopResult.getOrNull()?.firstOrNull()?.toInt() ?: -1
                _loopDisplay.value = DashCamSettingMappings.getLoopRecordingName(byteVal, AppAppearance.localizedContext(getApplication()))
                _currentLoopSettingRaw.value = byteVal
                updateStorageEstimateFromStatus(client.deviceStatus.value)
            } else if (targetIdentity.value.epoch == epoch) {
                _loopDisplay.value = getString(R.string.recorder_unable_to_read)
                _currentLoopSettingRaw.value = -1
                updateStorageEstimateFromStatus(client.deviceStatus.value)
            }

            // 3. Exposure
            val expResult = client.getParameter(GPSocketProtocol.PARAM_EXPOSURE, expectedEpoch = epoch)
            if (expResult.isSuccess && targetIdentity.value.epoch == epoch) {
                val byteVal = expResult.getOrNull()?.firstOrNull()?.toInt() ?: 0x06
                val name = DashCamSettingMappings.getExposureName(byteVal, AppAppearance.localizedContext(getApplication()))
                val progress = DashCamSettingMappings.exposureRawToProgress(byteVal)
                _exposureDisplay.value = name
                _exposureProgress.value = progress
            } else if (targetIdentity.value.epoch == epoch) {
                _exposureDisplay.value = getString(R.string.recorder_unable_to_read)
            }

            // 4. Watermark
            val wmResult = client.getParameter(GPSocketProtocol.PARAM_WATERMARK, expectedEpoch = epoch)
            if (wmResult.isSuccess && targetIdentity.value.epoch == epoch) {
                val byteVal = wmResult.getOrNull()?.firstOrNull()?.toInt() ?: -1
                _watermarkDisplay.value = DashCamSettingMappings.getWatermarkName(byteVal, AppAppearance.localizedContext(getApplication()))
            } else if (targetIdentity.value.epoch == epoch) {
                _watermarkDisplay.value = getString(R.string.recorder_unable_to_read)
            }

            // 5. Capture Resolution
            val capResResult = client.getParameter(GPSocketProtocol.PARAM_CAPTURE_RESOLUTION, expectedEpoch = epoch)
            if (capResResult.isSuccess && targetIdentity.value.epoch == epoch) {
                val byteVal = capResResult.getOrNull()?.firstOrNull()?.toInt() ?: -1
                _captureResolutionDisplay.value = DashCamSettingMappings.getCaptureResolutionName(byteVal, AppAppearance.localizedContext(getApplication()))
            } else if (targetIdentity.value.epoch == epoch) {
                _captureResolutionDisplay.value = getString(R.string.recorder_unable_to_read)
            }

            // 6. Capture Quality
            val capQualResult = client.getParameter(GPSocketProtocol.PARAM_CAPTURE_QUALITY, expectedEpoch = epoch)
            if (capQualResult.isSuccess && targetIdentity.value.epoch == epoch) {
                val byteVal = capQualResult.getOrNull()?.firstOrNull()?.toInt() ?: -1
                _captureQualityDisplay.value = DashCamSettingMappings.getCaptureQualityName(byteVal, AppAppearance.localizedContext(getApplication()))
            } else if (targetIdentity.value.epoch == epoch) {
                _captureQualityDisplay.value = getString(R.string.recorder_unable_to_read)
            }

            // 7. Record Audio
            val audioResult = client.getParameter(GPSocketProtocol.PARAM_RECORD_AUDIO, expectedEpoch = epoch)
            if (audioResult.isSuccess && targetIdentity.value.epoch == epoch) {
                val byteVal = audioResult.getOrNull()?.firstOrNull()?.toInt() ?: 0
                _audioRecordingDisplay.value = if (byteVal == 1) getString(R.string.device_on) else getString(R.string.setting_off)
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
                            firmwareVersion = obj.optString("firmwareVersion", getString(R.string.recorder_unknown)),
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
        val existing = currentList.find { it.matchesWifi(currentBssid) }

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
            } else {
                "DEV_${java.util.UUID.randomUUID()}"
            }
            val devName = if (currentSsid.isNotEmpty()) currentSsid else "Aladdin DashCam ($host)"
            val newDev = DashCamDeviceRecord(
                id = devId,
                name = devName,
                host = host,
                ssid = currentSsid,
                bssid = currentBssid,
                firmwareVersion = getString(R.string.recorder_unknown_not_read_yet),
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
            firmwareVersion = getString(R.string.recorder_unknown),
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
        val sourceId = currentMediaSourceId()
        _albumQueryState.value = AlbumQueryState.Loading
        val result = client.getFileList(expectedEpoch = currentEpoch)
        if (result.isSuccess) {
            val rawItems = result.getOrNull() ?: emptyList()
            // Cross check with local downloaded items
            val mappedItems = rawItems.map { item ->
                val sourced = item.copy(sourceDeviceId = sourceId, sourceEpoch = currentEpoch)
                sourced.copy(id = sourced.storageKey, isDownloaded = getLocalMediaFile(sourced) != null)
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
            if (targetIdentity.value.epoch == currentEpoch && currentMediaSourceId() == sourceId) {
                _deviceMediaList.value = sortedItems
                _albumQueryState.value = AlbumQueryState.Success(sortedItems.size)
                updateStorageEstimateFromStatus(client.deviceStatus.value)
            }
        } else {
            if (targetIdentity.value.epoch == currentEpoch) {
                _albumQueryState.value = AlbumQueryState.Error(
                    result.exceptionOrNull()?.message ?: getString(R.string.recorder_unable_to_read_recorder_videos)
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
        val deviceKey = item.sourceDirectory.ifEmpty { "legacy" }
        val cacheDir = File(app.cacheDir, "preview_cache/$deviceKey")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val file = File(cacheDir, File(item.fileName).name)
        if (file.exists()) mediaCacheManager.touchFile(file)
        return file
    }

    private fun currentMediaSourceId(): String =
        _registeredDevices.value.firstOrNull { it.isSelected }?.id ?: "session_${targetIdentity.value.epoch}"

    private fun isCurrentMediaSource(item: MediaItem): Boolean =
        item.sourceDeviceId == currentMediaSourceId() && item.sourceEpoch == targetIdentity.value.epoch

    private fun permanentMediaFile(item: MediaItem): File {
        item.localPath?.let { return File(it) }
        val directory = if (item.sourceDeviceId.isNotBlank())
            File(getLocalMoviesDir(), item.sourceDirectory) else getLocalMoviesDir()
        return File(directory, File(item.fileName).name)
    }

    /**
     * Local storage directory for user-confirmed permanent downloads.
     */
    fun getLocalMediaFile(item: MediaItem): File? {
        val file = permanentMediaFile(item)
        return if (file.isFile && (item.sizeBytes <= 0 || file.length() == item.sizeBytes)) file else null
    }

    /**
     * Fetches media file into temporary viewing cache. Does NOT mark as downloaded.
     * Progress dispatch is safely marshaled to Main thread and respects job cancellation (R1).
     */
    suspend fun fetchPreviewCache(
        item: MediaItem,
        onProgress: suspend (bytesRead: Long) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!isCurrentMediaSource(item)) return@withContext Result.failure(IllegalStateException(getString(R.string.recorder_video_source_changed_reopen_the_album)))
        val destFile = getPreviewCacheFile(item)
        if (destFile.exists() && (item.sizeBytes <= 0 || destFile.length() == item.sizeBytes)) {
            mediaCacheManager.touchFile(destFile)
            return@withContext Result.success(destFile)
        }
        val currentEpoch = item.sourceEpoch
        val progress = Channel<Long>(Channel.CONFLATED)
        val progressJob = launch(Dispatchers.Main) {
            for (bytes in progress) {
                onProgress(bytes)
                delay(150)
            }
        }
        val res = try { client.downloadRawData(
            deviceIndex = item.deviceIndex,
            destinationFile = destFile,
            expectedEpoch = currentEpoch,
            onProgress = { bytesRead ->
                progress.trySend(bytesRead)
            }
        ) } finally { progress.close() }
        progressJob.join()
        if (res.isSuccess) {
            mediaCacheManager.touchFile(destFile)
            destFile.parentFile?.parentFile?.let { basePreviewDir ->
                mediaCacheManager.evictDirectoryIfExceeds(
                    basePreviewDir,
                    mediaCacheManager.previewQuotaBytes,
                    mediaCacheManager.previewLowWatermarkBytes
                )
            }
            val realDuration = if (item.isVideo) extractMediaDurationSeconds(destFile) else 0
            if (realDuration > 0 && isCurrentMediaSource(item)) {
                _deviceMediaList.update { list ->
                    list.map {
                        if (it.storageKey == item.storageKey) it.copy(durationSeconds = realDuration) else it
                    }
                }
            }
            Result.success(destFile)
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception(getString(R.string.recorder_unable_to_cache_preview)))
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
        val currentEpoch = item.sourceEpoch
        if (!isCurrentMediaSource(item)) return@withContext Result.failure(IllegalStateException(getString(R.string.recorder_video_source_changed_reopen_the_album)))
        val destFile = permanentMediaFile(item)
        destFile.parentFile?.mkdirs()

        updateDownloadTask(
            DownloadTask(
                id = item.storageKey,
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
                if (generation == 0L || downloadGenerations[item.storageKey] == generation) {
                    _deviceMediaList.update { list ->
                        list.map {
                            if (isCurrentMediaSource(item) && it.storageKey == item.storageKey) {
                                it.copy(
                                    isDownloaded = true,
                                    durationSeconds = if (realDuration > 0) realDuration else it.durationSeconds
                                )
                            } else it
                        }
                    }
                    updateDownloadTask(
                        DownloadTask(
                            id = item.storageKey,
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

        var lastProgressTime = 0L
        val res = client.downloadRawData(
            deviceIndex = item.deviceIndex,
            destinationFile = destFile,
            expectedEpoch = currentEpoch,
            onProgress = { bytesRead ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (coroutineContext.isActive && now - lastProgressTime >= 150 && (generation == 0L || downloadGenerations[item.storageKey] == generation)) {
                    lastProgressTime = now
                    updateDownloadTaskProgress(item.storageKey, bytesRead)
                }
            }
        )
        if (res.isSuccess) {
            val realDuration = if (item.isVideo) extractMediaDurationSeconds(destFile) else 0
            if (generation == 0L || downloadGenerations[item.storageKey] == generation) {
                _deviceMediaList.update { list ->
                    list.map {
                        if (isCurrentMediaSource(item) && it.storageKey == item.storageKey) {
                            it.copy(
                                isDownloaded = true,
                                durationSeconds = if (realDuration > 0) realDuration else it.durationSeconds
                            )
                        } else it
                    }
                }
                _downloadTasks.update { tasks ->
                    tasks.map { existing ->
                        if (existing.id == item.storageKey && existing.status != DownloadStatus.CANCELLED) {
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
            val errMsg = res.exceptionOrNull()?.message ?: getString(R.string.recorder_unable_to_save_file)
            if (generation == 0L || downloadGenerations[item.storageKey] == generation) {
                _downloadTasks.update { tasks ->
                    tasks.map { existing ->
                        if (existing.id == item.storageKey && existing.status != DownloadStatus.CANCELLED) {
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
        val job = downloadRepository.enqueue(item, resolve = { saved ->
            if (connectionStatus.value != ConnectionStatus.CONNECTED ||
                saved.sourceDeviceId != currentMediaSourceId() ||
                currentStreamOwner.value == MediaStreamOwner.ALBUM_PLAYBACK) null
            else saved.copy(sourceEpoch = targetIdentity.value.epoch)
        }, retryWhen = { connectionStatus.value != ConnectionStatus.CONNECTED }, transfer = { current, generation -> albumModeMutex.withLock {
            if (!requestStreamOwner(MediaStreamOwner.DOWNLOAD)) Result.failure(IllegalStateException("Recorder busy"))
            else {
                var before: DashCamDeviceStatus? = null
                try {
                    before = client.queryStatus()
                    check(before != null && before.mode != DeviceMode.UNKNOWN) { "Recorder mode unknown" }
                    client.enterAlbumBrowsing(current.sourceEpoch).getOrThrow()
                    val verified = client.getFileList(current.sourceEpoch).getOrThrow().firstOrNull {
                        it.fileName == current.fileName && it.sizeBytes == current.sizeBytes
                    } ?: error("Recording is no longer present on this device")
                    saveToPermanentStorage(verified.copy(sourceDeviceId = current.sourceDeviceId, sourceEpoch = current.sourceEpoch), generation)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Result.failure(error)
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        try {
                            if (before != null && before.mode != DeviceMode.PLAYBACK) client.exitAlbumBrowsing(current.sourceEpoch)
                        } finally { releaseStreamOwner(MediaStreamOwner.DOWNLOAD) }
                    }
                }
            }
        } })
        try {
            androidx.core.content.ContextCompat.startForegroundService(getApplication(),
                android.content.Intent(getApplication(), com.aladdin.dashcam.media.DownloadService::class.java))
        } catch (error: Exception) {
            downloadRepository.cancel(DownloadTask(item.storageKey, item)) { client.abortActiveTransfer() }
            com.aladdin.dashcam.media.Diagnostics.record("download.service", error.javaClass.simpleName)
        }
        return job
    }

    fun downloadEventWindow(item: MediaItem, eventTime: Long, seconds: Long = 30): Int {
        if (!isCurrentMediaSource(item) || eventTime <= 0) return 0
        val from = eventTime - seconds * 1000; val until = eventTime + seconds * 1000
        val media = deviceMediaList.value.filter { it.isVideo && it.sourceDeviceId == item.sourceDeviceId && it.timestampMillis > 0 }
        val previous = media.filter { it.timestampMillis < from }.maxByOrNull { it.timestampMillis }
        val selected = media.filter {
            it.timestampMillis in from..until ||
                (it.durationSeconds > 0 && it.timestampMillis < from && it.timestampMillis + it.durationSeconds * 1000L > from) ||
                (it.durationSeconds == 0 && it.id == previous?.id)
        }.filter { !it.isDownloaded }
        selected.forEach { startPermanentDownload(it) }
        return selected.size
    }

    fun resumeDownloads() {
        downloadTasks.value.filter { it.status == DownloadStatus.PENDING }.forEach { startPermanentDownload(it.mediaItem) }
    }
    fun pauseDownloads() = downloadRepository.pauseAll { client.abortActiveTransfer() }

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

    fun cancelDownload(task: DownloadTask) = downloadRepository.cancel(task) { client.abortActiveTransfer() }

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
            val files = moviesDir.walkTopDown().filter { it.isFile }.toList()
            val validFiles = files.filter { it.extension.lowercase() in setOf("mp4", "mov", "jpg", "jpeg") }
            val items = validFiles.mapIndexed { idx, file ->
                val isVideo = file.extension.lowercase() in setOf("mp4", "mov")
                val isEmergency = file.name.contains("EMG", true)
                val parsedTimestamp = parseRecordingTimeFromFileName(file.name)
                val duration = if (isVideo) extractMediaDurationSeconds(file) else 0

                MediaItem(
                    id = "LOCAL_${file.relativeTo(moviesDir).invariantSeparatorsPath}",
                    fileName = file.name,
                    sizeBytes = file.length(),
                    durationSeconds = duration, // 真實時長或 0（未知）
                    timestampMillis = parsedTimestamp, // 嚴格採用真實錄製時間，不冒用 lastModified()
                    isVideo = isVideo,
                    isEmergency = isEmergency,
                    isDownloaded = true,
                    deviceIndex = idx + 1,
                    sourceDeviceId = if (file.parentFile == moviesDir) "" else runCatching { String(java.util.Base64.getUrlDecoder().decode(file.parentFile!!.name), Charsets.UTF_8) }.getOrDefault(""),
                    localPath = file.absolutePath
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
                    id = localItem.storageKey,
                    mediaItem = localItem,
                    status = DownloadStatus.COMPLETED,
                    bytesRead = localItem.sizeBytes,
                    totalBytes = localItem.sizeBytes,
                    localFile = File(localItem.localPath!!)
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

    private val mediaRepository = com.aladdin.dashcam.media.MediaRepository(application, client, mediaCacheManager)
    fun getCachedThumbnailBitmap(item: MediaItem): Bitmap? = mediaCacheManager.getBitmap(item.storageKey)
    fun putCachedThumbnailBitmap(item: MediaItem, bitmap: Bitmap) = mediaCacheManager.putBitmap(item.storageKey, bitmap)
    suspend fun getOrFetchThumbnail(item: MediaItem): File? = mediaRepository.thumbnail(item, getLocalMediaFile(item)) {
        isCurrentMediaSource(item) && deviceStatus.value.mode == DeviceMode.PLAYBACK &&
            currentStreamOwner.value != MediaStreamOwner.ALBUM_PLAYBACK && currentStreamOwner.value != MediaStreamOwner.DOWNLOAD
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
