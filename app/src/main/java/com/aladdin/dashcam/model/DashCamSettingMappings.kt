package com.aladdin.dashcam.model

data class SettingOption(val rawValue: Int, val displayName: String)

/**
 * Unified setting option mappings shared between UI and Socket Protocol (N04/N05).
 * Prevents index vs rawValue mismatch.
 */
object DashCamSettingMappings {
    // Menu.xml ID 0x00000000: Resolution
    val VIDEO_RESOLUTIONS = listOf(
        SettingOption(0, "2K 2560x1440 30fps"),
        SettingOption(1, "1080FHD 1920x1080 30fps"),
        SettingOption(2, "1080P 1440x1080 30fps"),
        SettingOption(3, "720P 1280x720 30fps")
    )

    // Menu.xml ID 0x00000100: Resolution
    val CAPTURE_RESOLUTIONS = listOf(
        SettingOption(0, "12M (4032x3024)"),
        SettingOption(1, "10M (3648x2736)"),
        SettingOption(2, "8M (3264x2448)"),
        SettingOption(3, "5M (2592x1944)"),
        SettingOption(4, "3M (2048x1536)"),
        SettingOption(5, "2MHD (1920x1080)"),
        SettingOption(6, "1.3M (1280x960)")
    )

    // Menu.xml ID 0x00000102: Quality
    val CAPTURE_QUALITIES = listOf(
        SettingOption(0, "精細 (Fine)"),
        SettingOption(1, "一般 (Normal)"),
        SettingOption(2, "經濟 (Economical)")
    )

    // Menu.xml ID 0x00000003: Loop Recording
    val LOOP_RECORDINGS = listOf(
        SettingOption(1, "1 分鐘"),
        SettingOption(2, "2 分鐘"),
        SettingOption(3, "3 分鐘"),
        SettingOption(4, "5 分鐘"),
        SettingOption(0, "關閉")
    )

    // Menu.xml ID 0x00000107: Date/Time Stamp
    val WATERMARKS = listOf(
        SettingOption(2, "開啟 (日期與時間)"),
        SettingOption(1, "僅日期 (Date)"),
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
