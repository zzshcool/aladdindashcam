package com.aladdin.dashcam.driver

data class RecorderEndpoint(
    val host: String,
    val controlPort: Int = 8081,
    val streamPort: Int = 8080,
    val protocolHint: RecorderProtocol = RecorderProtocol.UNKNOWN,
    val rtspPath: String = "/?action=stream"
) {
    fun getRtspUrl(): String = "rtsp://$host:$streamPort$rtspPath"

    companion object {
        val DEFAULT_GENERALPLUS = RecorderEndpoint(
            host = "192.168.25.1",
            controlPort = 8081,
            streamPort = 8080,
            protocolHint = RecorderProtocol.GENERALPLUS_GPSOCKET,
            rtspPath = "/?action=stream"
        )

        val DEFAULT_NOVATEK = RecorderEndpoint(
            host = "192.168.1.254",
            controlPort = 80,
            streamPort = 554,
            protocolHint = RecorderProtocol.NOVATEK_HTTP,
            rtspPath = "/liveRTSP/av4"
        )
    }
}
