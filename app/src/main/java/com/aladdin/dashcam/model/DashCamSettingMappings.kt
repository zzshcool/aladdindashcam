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

    // Menu.xml ID 0x00000005: Record Audio
    val RECORD_AUDIOS = listOf(
        SettingOption(1, "開啟"),
        SettingOption(0, "關閉")
    )

    // Menu.xml ID 0x00000001: Exposure
    // Values: 00=+2.0, 01=+5/3, 02=+4/3, 03=+1.0, 04=+2/3, 05=+1/3, 06=+0.0, 07=-1/3, 08=-2/3, 09=-1.0, 0A=-4/3, 0B=-5/3, 0C=-2.0
    // UI progress 0..12 maps left-to-right from -2.0 (progress 0) to +2.0 (progress 12), with center +0.0 (progress 6).
    val EXPOSURE_OPTIONS = listOf(
        SettingOption(0x0C, "-2.0"),
        SettingOption(0x0B, "-5/3"),
        SettingOption(0x0A, "-4/3"),
        SettingOption(0x09, "-1.0"),
        SettingOption(0x08, "-2/3"),
        SettingOption(0x07, "-1/3"),
        SettingOption(0x06, "+0.0"),
        SettingOption(0x05, "+1/3"),
        SettingOption(0x04, "+2/3"),
        SettingOption(0x03, "+1.0"),
        SettingOption(0x02, "+4/3"),
        SettingOption(0x01, "+5/3"),
        SettingOption(0x00, "+2.0")
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

    fun getExposureName(raw: Int): String =
        EXPOSURE_OPTIONS.find { it.rawValue == raw }?.displayName ?: if (raw in 0x00..0x0C) {
            val progress = (12 - raw).coerceIn(0, 12)
            EXPOSURE_OPTIONS[progress].displayName
        } else "未知 ($raw)"

    fun exposureProgressToRaw(progress: Int): Int = (12 - progress.coerceIn(0, 12))

    fun exposureRawToProgress(raw: Int): Int = (12 - raw.coerceIn(0, 12))
}
