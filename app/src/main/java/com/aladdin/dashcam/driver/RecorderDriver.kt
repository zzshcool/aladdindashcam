package com.aladdin.dashcam.driver

import android.net.Network

sealed class DriverProbeResult {
    data class Matched(val protocol: RecorderProtocol, val signature: String) : DriverProbeResult()
    data class Mismatched(val reason: String) : DriverProbeResult()
    data class Error(val throwable: Throwable) : DriverProbeResult()
}

interface RecorderDriver {
    val protocol: RecorderProtocol

    /**
     * Non-destructive probing to verify if candidate endpoint responds to this driver's protocol.
     */
    suspend fun probe(endpoint: RecorderEndpoint, network: Network?): DriverProbeResult

    /**
     * Interrogates the device to retrieve identity (brand, model, firmware, capabilities).
     */
    suspend fun identify(endpoint: RecorderEndpoint, network: Network?): IdentificationResult

    /**
     * Reads current hardware capabilities.
     */
    fun getCapabilities(): RecorderCapabilities
}
