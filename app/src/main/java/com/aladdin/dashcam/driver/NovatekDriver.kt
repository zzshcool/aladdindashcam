package com.aladdin.dashcam.driver

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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

    override suspend fun probe(endpoint: RecorderEndpoint, network: Network?): DriverProbeResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val probeUrl = URL("http://${endpoint.host}:${endpoint.controlPort}/?custom=1&cmd=3014")
            connection = if (network != null) {
                network.openConnection(probeUrl) as HttpURLConnection
            } else {
                probeUrl.openConnection() as HttpURLConnection
            }
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.requestMethod = "GET"
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val body = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                if (body.contains("0\n") || body.contains("CMD") || body.contains("Camera")) {
                    DriverProbeResult.Matched(protocol, "Novatek HTTP CGI (Response: ${body.trim().take(30)})")
                } else {
                    DriverProbeResult.Mismatched("Unexpected HTTP response: ${body.take(40)}")
                }
            } else {
                DriverProbeResult.Mismatched("HTTP status $responseCode")
            }
        } catch (e: Exception) {
            DriverProbeResult.Error(e)
        } finally {
            connection?.disconnect()
        }
    }

    override suspend fun identify(endpoint: RecorderEndpoint, network: Network?): IdentificationResult {
        return IdentificationResult(
            protocol = RecorderProtocol.NOVATEK_HTTP,
            brand = "Novatek (聯詠通用草案)",
            modelName = "聯詠通用機種 (待實機驗證)",
            firmwareVersion = "未知 (待實機驗證)",
            deviceId = "NVT_${endpoint.host.replace('.', '_')}",
            capabilities = getCapabilities(),
            verifiedOnHardware = false, // Explicitly false: B1 is GP, Novatek not verified on physical hardware
            rawSignature = "NOVATEK_CGI_DRAFT"
        )
    }

    override fun getCapabilities(): RecorderCapabilities {
        return RecorderCapabilities.NOVATEK_DRAFT
    }
}
