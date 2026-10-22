package com.aladdin.dashcam.driver

import android.net.Network

object DriverRegistry {
    private val drivers = mutableMapOf<RecorderProtocol, RecorderDriver>()

    init {
        register(GeneralPlusDriver())
        register(NovatekDriver())
    }

    fun register(driver: RecorderDriver) {
        drivers[driver.protocol] = driver
    }

    fun getDriver(protocol: RecorderProtocol): RecorderDriver? {
        return drivers[protocol]
    }

    fun getAllDrivers(): List<RecorderDriver> = drivers.values.toList()

    /**
     * Attempts to identify candidate host by probing registered drivers sequentially.
     */
    suspend fun probeAndIdentify(
        candidateHost: String,
        network: Network?,
        preferredProtocol: RecorderProtocol? = null
    ): IdentificationResult? {
        val candidateDrivers = if (preferredProtocol != null) {
            val pref = drivers[preferredProtocol]
            if (pref != null) listOf(pref) + drivers.values.filter { it.protocol != preferredProtocol }
            else drivers.values.toList()
        } else {
            drivers.values.toList()
        }

        for (driver in candidateDrivers) {
            val endpoint = when (driver.protocol) {
                RecorderProtocol.GENERALPLUS_GPSOCKET -> RecorderEndpoint(
                    host = candidateHost,
                    controlPort = 8081,
                    streamPort = 8080,
                    protocolHint = RecorderProtocol.GENERALPLUS_GPSOCKET
                )
                RecorderProtocol.NOVATEK_HTTP -> RecorderEndpoint(
                    host = candidateHost,
                    controlPort = 80,
                    streamPort = 554,
                    protocolHint = RecorderProtocol.NOVATEK_HTTP
                )
                else -> RecorderEndpoint(host = candidateHost)
            }

            when (driver.probe(endpoint, network)) {
                is DriverProbeResult.Matched -> {
                    return driver.identify(endpoint, network)
                }
                else -> {
                    // Try next driver
                }
            }
        }
        return null
    }
}
