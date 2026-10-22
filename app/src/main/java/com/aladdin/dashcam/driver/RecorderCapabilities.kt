package com.aladdin.dashcam.driver

enum class CapabilityState {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
    TEMPORARILY_UNAVAILABLE
}

data class RecorderCapabilities(
    val liveStream: CapabilityState = CapabilityState.UNKNOWN,
    val rtspStream: CapabilityState = CapabilityState.UNKNOWN,
    val batteryReporting: CapabilityState = CapabilityState.UNKNOWN,
    val sdCardReporting: CapabilityState = CapabilityState.UNKNOWN,
    val rtcClockSync: CapabilityState = CapabilityState.UNKNOWN,
    val fileListRetrieval: CapabilityState = CapabilityState.UNKNOWN,
    val remoteCapture: CapabilityState = CapabilityState.UNKNOWN,
    val recordingControl: CapabilityState = CapabilityState.UNKNOWN,
    val settingManagement: CapabilityState = CapabilityState.UNKNOWN
) {
    companion object {
        val GENERALPLUS_BASELINE = RecorderCapabilities(
            liveStream = CapabilityState.SUPPORTED,
            rtspStream = CapabilityState.SUPPORTED,
            batteryReporting = CapabilityState.SUPPORTED,
            sdCardReporting = CapabilityState.SUPPORTED,
            rtcClockSync = CapabilityState.SUPPORTED,
            fileListRetrieval = CapabilityState.SUPPORTED,
            remoteCapture = CapabilityState.SUPPORTED,
            recordingControl = CapabilityState.SUPPORTED,
            settingManagement = CapabilityState.SUPPORTED
        )

        val NOVATEK_DRAFT = RecorderCapabilities(
            liveStream = CapabilityState.SUPPORTED,
            rtspStream = CapabilityState.SUPPORTED,
            batteryReporting = CapabilityState.UNKNOWN,
            sdCardReporting = CapabilityState.UNKNOWN,
            rtcClockSync = CapabilityState.SUPPORTED,
            fileListRetrieval = CapabilityState.SUPPORTED,
            remoteCapture = CapabilityState.SUPPORTED,
            recordingControl = CapabilityState.SUPPORTED,
            settingManagement = CapabilityState.UNKNOWN
        )

        val UNKNOWN_DEVICE = RecorderCapabilities()
    }
}
