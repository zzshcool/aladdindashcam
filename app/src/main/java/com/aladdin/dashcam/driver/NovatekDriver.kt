package com.aladdin.dashcam.driver

import android.net.Network

/**
 * Novatek HTTP CGI Driver (Draft Specification).
 *
 * NOTE per project review rules:
 * - This driver models the standard Novatek HTTP CGI protocol (custom=1&cmd=...).
 * - Hardware verification status: BLOCKED / NOT_TESTED (No physical Novatek device available in current session).
 * - NOT claimed to be universally compatible with all Novatek dashcams.
 */
class NovatekDriver : RecorderDriver {
    override val protocol: RecorderProtocol = RecorderProtocol.NOVATEK_HTTP

    override suspend fun probe(endpoint: RecorderEndpoint, network: Network?): DriverProbeResult =
        DriverProbeResult.Mismatched("聯詠協定尚待型號與真實回應驗證，暫不自動識別")

    override suspend fun identify(endpoint: RecorderEndpoint, network: Network?): IdentificationResult {
        return IdentificationResult(
            protocol = RecorderProtocol.NOVATEK_HTTP,
            brand = "Novatek (聯詠通用草案)",
            modelName = "聯詠通用機種 (待實機驗證)",
            firmwareVersion = "未知 (待實機驗證)",
            deviceId = "",
            capabilities = getCapabilities(),
            verifiedOnHardware = false, // Explicitly false: B1 is GP, Novatek not verified on physical hardware
            rawSignature = "NOVATEK_CGI_DRAFT"
        )
    }

    override fun getCapabilities(): RecorderCapabilities {
        return RecorderCapabilities.NOVATEK_DRAFT
    }
}
