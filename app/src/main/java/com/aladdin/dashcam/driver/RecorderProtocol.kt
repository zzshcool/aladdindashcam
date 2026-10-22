package com.aladdin.dashcam.driver

enum class RecorderProtocol(val displayName: String, val defaultControlPort: Int, val defaultStreamPort: Int) {
    GENERALPLUS_GPSOCKET("GeneralPlus GPSocket (TCP 8081)", 8081, 8080),
    NOVATEK_HTTP("Novatek HTTP CGI (80/8080)", 80, 554),
    UNKNOWN("未知協定", 8081, 8080);

    companion object {
        fun fromHeaderTag(tag: String): RecorderProtocol = when {
            tag.startsWith("GPSOCK", ignoreCase = true) -> GENERALPLUS_GPSOCKET
            tag.startsWith("NOVATEK", ignoreCase = true) -> NOVATEK_HTTP
            else -> UNKNOWN
        }
    }
}
