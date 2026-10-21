package com.aladdin.dashcam.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aladdin.dashcam.gps.GpsSpeedometer
import com.aladdin.dashcam.model.ConnectionStatus
import com.aladdin.dashcam.model.DashCamDeviceStatus
import com.aladdin.dashcam.model.MediaItem
import com.aladdin.dashcam.model.RecordingStatus
import com.aladdin.dashcam.network.DashCamClient
import com.aladdin.dashcam.network.NetworkMonitor
import com.aladdin.dashcam.protocol.GPSocketProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DashCamViewModel(application: Application) : AndroidViewModel(application) {
    val client = DashCamClient(scope = viewModelScope)
    val networkMonitor = NetworkMonitor(application)
    val gpsSpeedometer = GpsSpeedometer(application)

    val connectionStatus: StateFlow<ConnectionStatus> = client.connectionStatus
    val deviceStatus: StateFlow<DashCamDeviceStatus> = client.deviceStatus
    val gpsStatus = gpsSpeedometer.gpsStatus

    private val _deviceMediaList = MutableStateFlow<List<MediaItem>>(emptyList())
    val deviceMediaList: StateFlow<List<MediaItem>> = _deviceMediaList.asStateFlow()

    private val _localMediaList = MutableStateFlow<List<MediaItem>>(emptyList())
    val localMediaList: StateFlow<List<MediaItem>> = _localMediaList.asStateFlow()

    val wifiNetwork = networkMonitor.wifiNetwork
    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    init {
        networkMonitor.startMonitoring()
        viewModelScope.launch {
            networkMonitor.wifiNetwork.collect { network ->
                client.bindNetwork(network)
            }
        }
        client.start()
        gpsSpeedometer.startListening()
        // C05: Real mode starts empty by default; simulated album is isolated to explicit Demo Mode
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

    suspend fun fetchRemoteFileCount(): Result<Int> {
        return client.getFileCount()
    }

    suspend fun setResolution(resIndex: Int): Result<Unit> {
        // VR-01: Parameter ID 0x0000 (Video Resolution per Menu.xml), value 1 byte index (0..3)
        return client.setParameter(GPSocketProtocol.PARAM_RESOLUTION, byteArrayOf(resIndex.toByte()))
    }

    suspend fun setLoopDuration(minutes: Int): Result<Unit> {
        // VR-01: Parameter ID 0x0003 (Loop Recording per Menu.xml)
        // Protocol enum mapping: 1 min -> 1, 2 min -> 2, 3 min -> 3, 5 min -> 4
        val rawValue = when (minutes) {
            1 -> 1
            2 -> 2
            3 -> 3
            5 -> 4
            else -> minutes
        }
        return client.setParameter(GPSocketProtocol.PARAM_LOOP_RECORDING, byteArrayOf(rawValue.toByte()))
    }

    fun setDemoMode(enabled: Boolean) {
        _isDemoMode.value = enabled
        if (enabled) {
            loadSimulatedAlbum()
        } else {
            _deviceMediaList.value = emptyList()
            _localMediaList.value = emptyList()
        }
    }

    private fun loadSimulatedAlbum() {
        val now = System.currentTimeMillis()
        val items = listOf(
            MediaItem(
                id = "REC_0001",
                fileName = "2026-10-21_00-10-00.MP4",
                sizeBytes = 125_829_120L, // 120 MB
                durationSeconds = 60,
                timestampMillis = now - 600_000,
                isVideo = true,
                isEmergency = false,
                isDownloaded = true,
                deviceIndex = 1
            ),
            MediaItem(
                id = "REC_0002",
                fileName = "2026-10-21_00-11-00.MP4",
                sizeBytes = 125_829_120L,
                durationSeconds = 60,
                timestampMillis = now - 540_000,
                isVideo = true,
                isEmergency = false,
                isDownloaded = false,
                deviceIndex = 2
            ),
            MediaItem(
                id = "EMG_0001",
                fileName = "2026-10-21_00-12-00_EMG.MP4",
                sizeBytes = 125_829_120L,
                durationSeconds = 60,
                timestampMillis = now - 480_000,
                isVideo = true,
                isEmergency = true,
                isDownloaded = false,
                deviceIndex = 3
            ),
            MediaItem(
                id = "PIC_0001",
                fileName = "2026-10-21_00-13-00.JPG",
                sizeBytes = 2_450_000L,
                durationSeconds = 0,
                timestampMillis = now - 420_000,
                isVideo = false,
                isEmergency = false,
                isDownloaded = true,
                deviceIndex = 4
            )
        )
        _deviceMediaList.value = items
        _localMediaList.value = items.filter { it.isDownloaded }
    }

    override fun onCleared() {
        super.onCleared()
        client.stop()
        networkMonitor.stopMonitoring()
        gpsSpeedometer.stopListening()
    }
}
