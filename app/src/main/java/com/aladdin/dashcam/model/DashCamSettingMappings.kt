package com.aladdin.dashcam.model

data class SettingOption(val rawValue: Int, val displayName: String)

/**
 * Unified setting option mappings shared between UI and Socket Protocol (N04/N05).
 * Prevents index vs rawValue mismatch.
 */
object DashCamSettingMappings {
    val VIDEO_RESOLUTIONS = listOf(
        SettingOption(2, "2K"),
        SettingOption(0, "1080P"),
        SettingOption(1, "720P")
    )

    val CAPTURE_RESOLUTIONS = listOf(
        SettingOption(4, "5M (2592x1944)"),
        SettingOption(3, "3M (2048x1536)"),
        SettingOption(2, "2M (1920x1080)"),
        SettingOption(1, "1.3M (1280x960)"),
        SettingOption(0, "VGA (640x480)")
    )

    val CAPTURE_QUALITIES = listOf(
        SettingOption(0, "優質 (Super Fine)"),
        SettingOption(1, "標準 (Fine)"),
        SettingOption(2, "經濟 (Normal)")
    )

    val LOOP_RECORDINGS = listOf(
        SettingOption(1, "1 分鐘"),
        SettingOption(2, "2 分鐘"),
        SettingOption(3, "3 分鐘"),
        SettingOption(4, "5 分鐘"),
        SettingOption(0, "關閉")
    )

    val WATERMARKS = listOf(
        SettingOption(1, "開啟 (Date/Time)"),
        SettingOption(0, "關閉")
    )

    fun getVideoResolutionName(raw: Int): String =
        VIDEO_RESOLUTIONS.find { it.rawValue == raw }?.displayName ?: "未知 ($raw)"

    fun getCaptureResolutionName(raw: Int): String =
        CAPTURE_RESOLUTIONS.find { it.rawValue == raw }?.displayName ?: "未知 ($raw)"

    fun getCaptureQualityName(raw: Int): String =
        CAPTURE_QUALITIES.find { it.rawValue == raw }?.displayName ?: "未知 ($raw)"

    fun getLoopRecordingName(raw: Int): String =
        LOOP_RECORDINGS.find { it.rawValue == raw }?.displayName ?: if (raw == 5) "5 分鐘" else "未知 ($raw)"

    fun getWatermarkName(raw: Int): String =
        WATERMARKS.find { it.rawValue == raw }?.displayName ?: "未知 ($raw)"
}
