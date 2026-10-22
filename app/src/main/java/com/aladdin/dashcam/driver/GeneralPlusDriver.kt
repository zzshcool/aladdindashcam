package com.aladdin.dashcam.driver

import android.net.Network
import com.aladdin.dashcam.protocol.GPSocketProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

class GeneralPlusDriver : RecorderDriver {
    override val protocol: RecorderProtocol = RecorderProtocol.GENERALPLUS_GPSOCKET

    override suspend fun probe(endpoint: RecorderEndpoint, network: Network?): DriverProbeResult = withContext(Dispatchers.IO) {
        var testSocket: Socket? = null
        try {
            testSocket = Socket()
            network?.bindSocket(testSocket)
            testSocket.connect(InetSocketAddress(endpoint.host, endpoint.controlPort), 2500)
            testSocket.soTimeout = 2500

            val out = testSocket.getOutputStream()
            val inStream = testSocket.getInputStream()

            val packet = GPSocketProtocol.buildGetStatusPacket()
            out.write(packet)
            out.flush()

            val header = ByteArray(12)
            readFully(inStream, header)

            val tagMatch = header[0] == 0x47.toByte() && // 'G'
                    header[1] == 0x50.toByte() && // 'P'
                    header[2] == 0x53.toByte() && // 'S'
                    header[3] == 0x4F.toByte() && // 'O'
                    header[4] == 0x43.toByte() && // 'C'
                    header[5] == 0x4B.toByte()    // 'K'

            if (tagMatch) {
                DriverProbeResult.Matched(protocol, "GPSOCK (GeneralPlus)")
            } else {
                val headStr = String(header.take(6).toByteArray(), Charsets.US_ASCII)
                DriverProbeResult.Mismatched("Header mismatch: $headStr")
            }
        } catch (e: Exception) {
            DriverProbeResult.Error(e)
        } finally {
            try { testSocket?.close() } catch (_: Exception) {}
        }
    }

    override suspend fun identify(endpoint: RecorderEndpoint, network: Network?): IdentificationResult {
        return IdentificationResult(
            protocol = RecorderProtocol.GENERALPLUS_GPSOCKET,
            brand = "GeneralPlus",
            modelName = "通用記錄器 (GPSocket)",
            firmwareVersion = "讀取中...",
            deviceId = "GP_${endpoint.host.replace('.', '_')}",
            capabilities = getCapabilities(),
            verifiedOnHardware = true,
            rawSignature = "GPSOCK"
        )
    }

    override fun getCapabilities(): RecorderCapabilities {
        return RecorderCapabilities.GENERALPLUS_BASELINE
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var bytesRead = 0
        while (bytesRead < buffer.size) {
            val count = input.read(buffer, bytesRead, buffer.size - bytesRead)
            if (count < 0) throw java.io.EOFException("Premature EOF while probing GeneralPlus socket")
            bytesRead += count
        }
    }
}
