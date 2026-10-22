package com.aladdin.dashcam.driver

data class IdentificationResult(
    val protocol: RecorderProtocol,
    val brand: String,
    val modelName: String,
    val firmwareVersion: String,
    val deviceId: String,
    val capabilities: RecorderCapabilities,
    val verifiedOnHardware: Boolean = false,
    val rawSignature: String = ""
)
