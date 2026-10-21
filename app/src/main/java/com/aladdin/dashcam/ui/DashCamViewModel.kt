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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
            if (targetIdentity.value.epoch == currentEpoch) {
                _deviceMediaList.value = mappedItems
                _albumQueryState.value = AlbumQueryState.Success(mappedItems.size)
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
     * Downloads real media item from dashcam to local app storage (M4 / P-02)
     */
    suspend fun downloadDeviceMedia(
        item: MediaItem,
        onProgress: (bytesRead: Long) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val currentEpoch = targetIdentity.value.epoch
        val moviesDir = getLocalMoviesDir()
        if (!moviesDir.exists()) moviesDir.mkdirs()
        val destFile = File(moviesDir, item.fileName)

        val res = client.downloadRawData(
            deviceIndex = item.deviceIndex,
            destinationFile = destFile,
            expectedEpoch = currentEpoch,
            onProgress = onProgress
        )
        if (res.isSuccess) {
            // Update device list item status
            _deviceMediaList.value = _deviceMediaList.value.map {
                if (it.deviceIndex == item.deviceIndex) it.copy(isDownloaded = true) else it
            }
            loadLocalMedia()
            Result.success(destFile)
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("下載失敗"))
        }
    }

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
                MediaItem(
                    id = "LOCAL_${file.name}",
                    fileName = file.name,
                    sizeBytes = file.length(),
                    durationSeconds = if (isVideo) 60 else 0,
                    timestampMillis = file.lastModified(),
                    isVideo = isVideo,
                    isEmergency = isEmergency,
                    isDownloaded = true,
                    deviceIndex = idx + 1
                )
            }.sortedByDescending { it.timestampMillis }
            _localMediaList.value = items
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
