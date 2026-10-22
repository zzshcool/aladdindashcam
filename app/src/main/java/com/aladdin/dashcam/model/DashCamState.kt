package com.aladdin.dashcam.model

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    STREAM_INTERRUPTED
}

enum class DeviceMode(val raw: Int) {
    RECORD(0),
    CAPTURE(1),
    PLAYBACK(2),
    MENU(3),
    UNKNOWN(-1);

    companion object {
        fun fromRaw(raw: Int): DeviceMode =
            values().firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

enum class RecordingStatus {
    UNKNOWN,
    RECORDING,
    STOPPED
}

data class DashCamDeviceStatus(
    val mode: DeviceMode = DeviceMode.UNKNOWN,
    val recordingStatus: RecordingStatus = RecordingStatus.UNKNOWN,
    val audioEnabled: Boolean = false,
    val rtspFlag: Boolean = false,
    val batteryLevel: Int = 0, // 0 to 4
    val isCharging: Boolean = false,
    val sdCardReady: Boolean = false,
    val recordResolutionIndex: Int = 0,
    // V1 / S2: 原廠 APK (o6/b.java:148,156) 證實 bytes 5~8 語意為錄影時間 (times = seconds * 1000)，非剩餘記憶卡容量
    val rawRecordSeconds: Long = 0L,
    @Deprecated("語意錯誤，原廠用於錄影時間而非剩餘容量。若無可信容量來源請以未知呈現")
    val captureResolutionIndex: Int = 0,
    val availablePhotos: Long = 0L,
    val sdCardState: Int = 0, // 0: 未知/無卡, 1: 正常, 2: 寫保護/異常
    val sdFreeBytes: Long = -1L, // -1L 代表尚未取得或不支援
    val sdTotalBytes: Long = -1L, // -1L 代表尚未取得或不支援
    val lastUpdatedTimestamp: Long = 0L,
    // Client-assigned ordering token for accepted status packets, independent of wall-clock changes.
    val sampleSequence: Long = 0L
)

data class MediaItem(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val durationSeconds: Int,
    val timestampMillis: Long,
    val isVideo: Boolean,
    val isEmergency: Boolean,
    val isDownloaded: Boolean = false,
    val deviceIndex: Int = 0
)

data class DownloadTask(
    val taskId: String,
    val mediaItem: MediaItem,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val isCompleted: Boolean = false,
    val isFailed: Boolean = false,
    val errorMessage: String? = null
)
