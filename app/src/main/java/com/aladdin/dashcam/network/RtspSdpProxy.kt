package com.aladdin.dashcam.network

import android.net.Network
import java.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Detects GeneralPlus firmware sending frame durations instead of cumulative 90 kHz RTP timestamps. */
internal class RtpVideoTimestampNormalizer(
    private val durationOverride: Boolean = false,
    private val fallbackDuration: Long = 3000L
) {
    private var source: Long? = null
    private var previousTimestamp = -1L
    private var completedFrames = 0
    private var nonIncreasingFrames = 0
    var durationMode = false
        private set
    private var timeline = 0L

    fun normalize(packet: ByteArray): ByteArray {
        if (packet.size < 16 || (packet[4].toInt() and 0xc0) != 0x80) return packet
        fun unsignedInt(offset: Int): Long = (offset until offset + 4).fold(0L) { value, index ->
            (value shl 8) or (packet[index].toLong() and 255)
        }
        val ssrc = unsignedInt(12)
        if (source != ssrc) {
            source = ssrc
            previousTimestamp = -1L
            completedFrames = 0
            nonIncreasingFrames = 0
            durationMode = durationOverride
            timeline = if (durationOverride) 0L else unsignedInt(8)
        }
        val timestamp = unsignedInt(8)
        var payloadOffset = 16 + (packet[4].toInt() and 15) * 4
        if (packet[4].toInt() and 16 != 0) {
            if (packet.size < payloadOffset + 4) return packet
            val words = ((packet[payloadOffset + 2].toInt() and 255) shl 8) or (packet[payloadOffset + 3].toInt() and 255)
            payloadOffset += 4 + words * 4
        }
        val fragmentEnd = durationOverride && packet.size > payloadOffset + 1 &&
            packet[payloadOffset].toInt() and 31 == 28 && packet[payloadOffset + 1].toInt() and 64 != 0
        val endOfFrame = packet[5].toInt() and 0x80 != 0 || fragmentEnd
        if (endOfFrame && !durationMode) {
            if (timestamp !in 900L..9000L) {
                completedFrames = 0
                nonIncreasingFrames = 0
            } else {
                completedFrames++
                if (timestamp <= previousTimestamp) nonIncreasingFrames++
            }
            previousTimestamp = timestamp
            if (completedFrames >= 6 && nonIncreasingFrames >= 4) {
                durationMode = true
            }
        }
        val output = if (durationMode) packet.copyOf().also { normalized ->
            for (index in 0..3) normalized[8 + index] = (timeline ushr (24 - index * 8)).toByte()
            if (fragmentEnd) normalized[5] = (normalized[5].toInt() or 128).toByte()
        } else packet
        if (endOfFrame) {
            val duration = if (durationOverride && timestamp !in 900L..9000L) fallbackDuration else timestamp
            timeline = (timeline + duration) and 0xffffffffL
        }
        return output
    }
}

/**
 * Robust RTSP Loopback Proxy with full message framing state machine (RFC 2326, P1),
 * accurate SDP media track patching (P2, P4), RFC 3550 RTP parsing (P5), dynamic cache (P6),
 * and centralized socket lifecycle management without concurrent probe collisions (P3, P7).
 */
class RtspSdpProxy(
    private val remoteHost: String,
    private val remotePort: Int = 8080,
    private val network: Network,
    private val deviceIdentifier: String = ""
) {
    companion object {
        private const val TAG = "RtspSdpProxy"

        // P6: Keyed cache holding real, authenticated SPS/PPS extracted directly from stream:
        // Key format: "$host:$port" or "$host:$port@$deviceIdentifier"
        private val spsPpsCache = ConcurrentHashMap<String, String>()

        fun clearCache(keyPrefix: String? = null) {
            if (keyPrefix != null) {
                spsPpsCache.keys.filter { it.startsWith(keyPrefix) || it.contains(keyPrefix) }.forEach {
                    spsPpsCache.remove(it)
                }
                Log.i(TAG, "Cache cleared for prefix: $keyPrefix")
            } else {
                spsPpsCache.clear()
                Log.i(TAG, "All RtspSdpProxy cache cleared")
            }
        }

        fun putCachedSpsPps(key: String, spsPps: String) {
            spsPpsCache[key] = spsPps
        }

        fun getCachedSpsPps(key: String): String? {
            return spsPpsCache[key]
        }
    }

    fun getEffectiveCacheKey(): String {
        return if (deviceIdentifier.isNotEmpty()) "$remoteHost:$remotePort@$deviceIdentifier" else "$remoteHost:$remotePort"
    }

    private var serverSocket: ServerSocket? = null
    private var proxyJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    // P3: Centralized active socket registry for clean resource reclamation
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    val localPort: Int
        get() = serverSocket?.localPort ?: -1

    fun start(): Int {
        if (isRunning.get()) return localPort
        val ss = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        isRunning.set(true)
        Log.i(TAG, "RtspSdpProxy started on 127.0.0.1:${ss.localPort} targeting $remoteHost:$remotePort (device: $deviceIdentifier)")

        proxyJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning.get() && !ss.isClosed) {
                try {
                    val clientSocket = ss.accept()
                    activeSockets.add(clientSocket)
                    Log.i(TAG, "ExoPlayer connected to local RTSP proxy")
                    launch {
                        handleClientSession(clientSocket)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Error accepting client connection: ${e.message}")
                    }
                }
            }
        }
        return ss.localPort
    }

    fun stop() {
        isRunning.set(false)
        proxyJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        // P3: Force-close all client, dashcam, and probe sockets to immediately unblock all read loops
        for (s in activeSockets) {
            try { s.close() } catch (_: Exception) {}
        }
        activeSockets.clear()
        Log.i(TAG, "RtspSdpProxy stopped and all active sockets forcefully closed")
    }

    private suspend fun handleClientSession(clientSocket: Socket) = withContext(Dispatchers.IO) {
        var dashcamSocket: Socket? = null
        try {
            clientSocket.tcpNoDelay = true

            // P7: Probe strictly beforehand in sequential order if cache is absent; never run two sessions in parallel
            val cacheKey = getEffectiveCacheKey()
            if (!spsPpsCache.containsKey(cacheKey)) {
                delay(300) // Allow dashcam lwIP sufficient time to release preceding socket
                probeLiveSpsPpsSequentially(cacheKey)
            }

            val sock = network.socketFactory.createSocket()
            dashcamSocket = sock
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(remoteHost, remotePort), 3000)
            activeSockets.add(sock)
            Log.i(TAG, "Proxy successfully connected to dashcam at $remoteHost:$remotePort via dedicated Wi-Fi")

            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            val serverIn = dashcamSocket.getInputStream()
            val serverOut = dashcamSocket.getOutputStream()

            val jobC2S = launch {
                try {
                    forwardClientToServer(clientIn, serverOut)
                } catch (e: Exception) {
                    Log.d(TAG, "C2S forward ended: ${e.message}")
                } finally {
                    // P3: On client disconnection, instantly close dashcam socket to break server read
                    try { dashcamSocket.close() } catch (_: Exception) {}
                }
            }

            val jobS2C = launch {
                try {
                    forwardServerToClient(serverIn, clientOut)
                } catch (e: Exception) {
                    Log.d(TAG, "S2C forward ended: ${e.message}")
                } finally {
                    // P3: On server disconnection or RST, instantly close client socket to break client read
                    try { clientSocket.close() } catch (_: Exception) {}
                }
            }

            // Await either job termination
            while (jobC2S.isActive && jobS2C.isActive) {
                delay(50)
            }
            jobC2S.cancelAndJoin()
            jobS2C.cancelAndJoin()
        } catch (e: Exception) {
            Log.w(TAG, "Proxy session exception: ${e.message}")
        } finally {
            activeSockets.remove(clientSocket)
            try { clientSocket.close() } catch (_: Exception) {}
            if (dashcamSocket != null) {
                activeSockets.remove(dashcamSocket)
                try { dashcamSocket.close() } catch (_: Exception) {}
            }
            Log.i(TAG, "Proxy session closed")
        }
    }

    /**
     * P1: Incremental framing parser that handles both RTSP text requests and interleaved binary frames ($)
     * without relying on TCP read buffer boundaries.
     */
    private fun forwardClientToServer(input: InputStream, output: OutputStream) {
        val streamReader = RtspFramingReader(input)
        while (isRunning.get()) {
            val frame = streamReader.nextFrame() ?: break
            when (frame) {
                is RtspFrame.Binary -> {
                    // Interleaved RTP/RTCP from client: forward exactly without modification
                    output.write(frame.rawPacket)
                    output.flush()
                }
                is RtspFrame.Text -> {
                    val port = localPort
                    // Rewrite local URI to remote target
                    var rewritten = frame.headerText.replace("127.0.0.1:$port", "$remoteHost:$remotePort")
                    rewritten = rewritten.replace("0.0.0.0:$remotePort", "$remoteHost:$remotePort")
                    rewritten = rewritten.replace("0.0.0.0", remoteHost)
                    // Rewrite Host header
                    rewritten = rewritten.replace("Host: 127.0.0.1:$port", "Host: $remoteHost:$remotePort")
                    val headerBytes = rewritten.toByteArray(StandardCharsets.UTF_8)
                    output.write(headerBytes)
                    if (frame.body.isNotEmpty()) {
                        output.write(frame.body)
                    }
                    output.flush()
                    Log.i(TAG, "C->S REQUEST:\n$rewritten")
                }
            }
        }
    }

    /**
     * P1 & P4: Processes server-to-client responses, inspecting SDP and patching only H.264 video track fmtp.
     */
    private fun forwardServerToClient(input: InputStream, output: OutputStream) {
        val streamReader = RtspFramingReader(input)
        val cacheKey = getEffectiveCacheKey()
        val parameterSets = RtpParameterSetCollector()
        var timestamps = RtpVideoTimestampNormalizer()
        var timestampModeChosen = false
        val initialVideoPackets = ArrayList<ByteArray>()
        val initialDurations = ArrayList<Long>()

        var binaryCount = 0
        while (isRunning.get()) {
            val frame = streamReader.nextFrame()
            if (frame == null) {
                Log.w(TAG, "S->C streamReader.nextFrame returned null, connection broken or closed")
                break
            }
            when (frame) {
                is RtspFrame.Binary -> {
                    binaryCount++
                    if (binaryCount == 1 || binaryCount % 100 == 0) {
                        Log.i(TAG, "S->C Binary frame #$binaryCount channel=${frame.channel} len=${frame.payload.size}")
                    }
                    // Forward binary packet directly
                    if (frame.channel == 0 && !timestampModeChosen) {
                        initialVideoPackets.add(frame.rawPacket)
                        timestamps.normalize(frame.rawPacket)
                        if (frame.payload.size >= 12 && frame.payload[1].toInt() and 128 != 0) {
                            initialDurations.add((4..7).fold(0L) { value, index ->
                                (value shl 8) or (frame.payload[index].toLong() and 255)
                            })
                        }
                        if (initialDurations.size < 12 && initialVideoPackets.size < 128) continue
                        val durations = initialDurations.filter { it in 900L..9000L }.sorted()
                        val fallback = durations.getOrNull(durations.size / 2) ?: 3000L
                        Log.i(TAG, "RTP duration compatibility mode=${timestamps.durationMode}")
                        timestamps = RtpVideoTimestampNormalizer(timestamps.durationMode, fallback)
                        initialVideoPackets.forEach { output.write(timestamps.normalize(it)) }
                        initialVideoPackets.clear()
                        timestampModeChosen = true
                    } else {
                        output.write(if (frame.channel == 0) timestamps.normalize(frame.rawPacket) else frame.rawPacket)
                    }
                    output.flush()

                    // P5: Monitor in-band SPS/PPS dynamically using RFC 3550 parser
                    if (frame.channel == 0 && frame.payload.isNotEmpty()) {
                        val parsed = parameterSets.offer(frame.payload)
                        if (parsed != null) {
                            val param = "${parsed.first},${parsed.second}"
                            if (spsPpsCache.put(cacheKey, param) != param) {
                                Log.i(TAG, "Dynamic SPS/PPS updated from live stream STAP-A/SPS: $param")
                            }
                        }
                    }
                }
                is RtspFrame.Text -> {
                    val headerText = frame.headerText
                    val port = localPort
                    val isSdp = headerText.contains("application/sdp", ignoreCase = true)

                    if (isSdp && frame.body.isNotEmpty()) {
                        val originalSdp = String(frame.body, StandardCharsets.UTF_8)
                        Log.i(TAG, "ORIGINAL SDP FROM DASHCAM:\n$originalSdp")
                        val patchedSdp = patchSdpVideoTrack(originalSdp, cacheKey)

                        if (patchedSdp != null) {
                            val patchedBodyBytes = patchedSdp.toByteArray(StandardCharsets.UTF_8)
                            val updatedHeaders = updateContentLength(headerText, patchedBodyBytes.size)
                            val finalHeaders = updatedHeaders.replace("$remoteHost:$remotePort", "127.0.0.1:$port")
                            output.write(finalHeaders.toByteArray(StandardCharsets.UTF_8))
                            output.write(patchedBodyBytes)
                            output.flush()
                            Log.i(TAG, "PATCHED SDP DELIVERED TO EXOPLAYER with real parameters:\n$patchedSdp")
                        } else {
                            val finalHeaders = headerText.replace("$remoteHost:$remotePort", "127.0.0.1:$port")
                            output.write(finalHeaders.toByteArray(StandardCharsets.UTF_8))
                            output.write(frame.body)
                            output.flush()
                        }
                    } else {
                        Log.i(TAG, "S->C RESPONSE:\n$headerText")
                        val finalHeaders = headerText.replace("$remoteHost:$remotePort", "127.0.0.1:$port")
                        output.write(finalHeaders.toByteArray(StandardCharsets.UTF_8))
                        if (frame.body.isNotEmpty()) {
                            output.write(frame.body)
                        }
                        output.flush()
                    }
                }
            }
        }
    }

    /**
     * P2 & P4: Parses SDP structured media sections, identifies the H.264 video track,
     * and patches only its fmtp attribute while keeping audio and session headers untouched.
     */
    private fun patchSdpVideoTrack(sdp: String, cacheKey: String): String? {
        val spsPpsParam = spsPpsCache[cacheKey]
        if (spsPpsParam.isNullOrEmpty()) {
            Log.w(TAG, "Cannot patch SDP: no verified real SPS/PPS parameter available in cache for $cacheKey")
            return null
        }

        val lines = sdp.lines()
        val result = mutableListOf<String>()
        var inVideoTrack = false
        var videoPayloadType: String? = null
        var videoFmtpFound = false
        var patched = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("m=")) {
                if (inVideoTrack && !videoFmtpFound && videoPayloadType != null && !patched) {
                    result.add("a=fmtp:$videoPayloadType packetization-mode=1;sprop-parameter-sets=$spsPpsParam")
                    patched = true
                }
                inVideoTrack = trimmed.startsWith("m=video")
                if (inVideoTrack) {
                    val parts = trimmed.split(" ")
                    videoPayloadType = if (parts.size >= 4) parts[3] else "96"
                }
                result.add(line)
                continue
            }

            if (inVideoTrack) {
                if (trimmed.startsWith("a=rtpmap:") && trimmed.contains("H264", ignoreCase = true)) {
                    val pt = trimmed.substringAfter("a=rtpmap:").substringBefore(" ").trim()
                    if (pt.isNotEmpty()) videoPayloadType = pt
                }

                if (trimmed.startsWith("a=fmtp:$videoPayloadType") || (trimmed.startsWith("a=fmtp:") && videoPayloadType == null)) {
                    videoFmtpFound = true
                    if (!trimmed.contains("sprop-parameter-sets")) {
                        result.add("$trimmed;sprop-parameter-sets=$spsPpsParam")
                        patched = true
                    } else {
                        result.add(line)
                        patched = true
                    }
                    continue
                }
            }
            result.add(line)
        }

        if (inVideoTrack && !videoFmtpFound && videoPayloadType != null && !patched) {
            result.add("a=fmtp:$videoPayloadType packetization-mode=1;sprop-parameter-sets=$spsPpsParam")
            patched = true
        }

        return if (patched) result.joinToString("\r\n") else null
    }

    /**
     * P7: Sequential, isolated hardware RTSP probe.
     * Establishes a single, dedicated TCP connection, negotiates OPTIONS/DESCRIBE/SETUP/PLAY,
     * extracts authentic SPS/PPS from early RTP packets, sends TEARDOWN, and fully closes the socket.
     * Guarantees zero session competition with the main proxy session.
     */
    private fun probeLiveSpsPpsSequentially(cacheKey: String) {
        var probeSocket: Socket? = null
        try {
            Log.i(TAG, "Starting isolated sequential probe on $remoteHost:$remotePort")
            val sock = network.socketFactory.createSocket()
            probeSocket = sock
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(remoteHost, remotePort), 2500)
            sock.soTimeout = 2500
            activeSockets.add(sock)

            val out = probeSocket.getOutputStream()
            val input = probeSocket.getInputStream()
            val reader = RtspFramingReader(input)

            // 1. OPTIONS
            out.write("OPTIONS rtsp://$remoteHost:$remotePort/?action=stream RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: AladdinDashCam-Probe/1.0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.flush()
            val optResp = reader.nextFrame() as? RtspFrame.Text
            if (optResp == null || !optResp.is200Ok) {
                Log.w(TAG, "Probe OPTIONS failed or rejected: ${optResp?.firstLine}")
                return
            }

            // 2. DESCRIBE
            out.write("DESCRIBE rtsp://$remoteHost:$remotePort/?action=stream RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.flush()
            val descResp = reader.nextFrame() as? RtspFrame.Text
            if (descResp == null || !descResp.is200Ok) {
                Log.w(TAG, "Probe DESCRIBE failed: ${descResp?.firstLine}")
                return
            }

            // P2: Extract authentic video control track URI from media section
            val sdpText = String(descResp.body, StandardCharsets.UTF_8)
            val videoControlTrack = extractVideoControlUri(sdpText)
            val setupUri = if (videoControlTrack.startsWith("rtsp://")) {
                videoControlTrack
            } else {
                "rtsp://$remoteHost:$remotePort/?action=stream/$videoControlTrack"
            }

            // 3. SETUP
            out.write("SETUP $setupUri RTSP/1.0\r\nCSeq: 3\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.flush()
            val setupResp = reader.nextFrame() as? RtspFrame.Text
            if (setupResp == null || !setupResp.is200Ok) {
                Log.w(TAG, "Probe SETUP failed: ${setupResp?.firstLine}")
                return
            }
            val sessionId = setupResp.getHeader("Session")?.split(";")?.get(0)?.trim()
            if (sessionId.isNullOrEmpty()) {
                Log.w(TAG, "Probe SETUP lacked Session header")
                return
            }

            // 4. PLAY
            out.write("PLAY rtsp://$remoteHost:$remotePort/?action=stream RTSP/1.0\r\nCSeq: 4\r\nSession: $sessionId\r\nRange: npt=0.000-\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.flush()
            val playResp = reader.nextFrame() as? RtspFrame.Text
            if (playResp == null || !playResp.is200Ok) {
                Log.w(TAG, "Probe PLAY failed: ${playResp?.firstLine}")
                return
            }

            // 5. Read incoming RTP interleaved packets to extract genuine SPS/PPS
            var foundSps: String? = null
            var foundPps: String? = null
            val parameterSets = RtpParameterSetCollector()
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < 3000 && (foundSps == null || foundPps == null)) {
                val frame = reader.nextFrame() ?: break
                if (frame is RtspFrame.Binary && frame.channel == 0 && frame.payload.isNotEmpty()) {
                    val parsed = parameterSets.offer(frame.payload)
                    if (parsed != null) {
                        foundSps = parsed.first
                        foundPps = parsed.second
                        break
                    }
                }
            }

            // 6. TEARDOWN to gracefully release hardware session
            try {
                out.write("TEARDOWN rtsp://$remoteHost:$remotePort/?action=stream RTSP/1.0\r\nCSeq: 5\r\nSession: $sessionId\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()
            } catch (_: Exception) {}

            if (foundSps != null && foundPps != null) {
                val realParam = "$foundSps,$foundPps"
                spsPpsCache[cacheKey] = realParam
                Log.i(TAG, "Probe successfully extracted real in-band SPS/PPS: $realParam")
            } else {
                Log.w(TAG, "Probe completed without capturing SPS/PPS from hardware stream")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sequential probe exception: ${e.message}")
        } finally {
            if (probeSocket != null) {
                activeSockets.remove(probeSocket)
                try { probeSocket.close() } catch (_: Exception) {}
            }
            // Give device lwIP stack 150ms to completely reclaim the closed socket before main connection starts
            Thread.sleep(150)
            Log.i(TAG, "Probe socket fully released and reclaimed")
        }
    }

    private fun extractVideoControlUri(sdp: String): String {
        var inVideo = false
        for (line in sdp.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("m=")) {
                inVideo = trimmed.startsWith("m=video")
            }
            if (inVideo && trimmed.startsWith("a=control:")) {
                val ctrl = trimmed.substringAfter("a=control:").trim()
                if (ctrl.isNotEmpty() && ctrl != "*") {
                    return ctrl
                }
            }
        }
        return "streamid=0"
    }

    private fun updateContentLength(headers: String, newLen: Int): String {
        val sb = StringBuilder()
        for (line in headers.lines()) {
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                sb.append("Content-Length: $newLen\r\n")
            } else if (line.isNotEmpty()) {
                sb.append(line).append("\r\n")
            }
        }
        sb.append("\r\n")
        return sb.toString()
    }
}

/**
 * P1: Structured RTSP frame representation.
 */
sealed class RtspFrame {
    data class Binary(val channel: Int, val payload: ByteArray, val rawPacket: ByteArray) : RtspFrame()
    data class Text(val headerText: String, val body: ByteArray) : RtspFrame() {
        val firstLine: String
            get() = headerText.lines().firstOrNull() ?: ""
        val is200Ok: Boolean
            get() = firstLine.contains("200 OK")
        fun getHeader(name: String): String? {
            for (line in headerText.lines()) {
                if (line.startsWith("$name:", ignoreCase = true)) {
                    return line.substringAfter(":").trim()
                }
            }
            return null
        }
    }
}

/**
 * P1: Stream parser that handles incremental TCP buffering, double-CRLF headers,
 * Content-Length framing, and binary '$' interleaved packets.
 */
class RtspFramingReader(private val input: InputStream) {
    fun nextFrame(): RtspFrame? {
        val firstByte = input.read()
        if (firstByte == -1) return null

        if (firstByte == 0x24) { // '$' Interleaved binary frame
            val channel = input.read()
            val lenH = input.read()
            val lenL = input.read()
            if (channel == -1 || lenH == -1 || lenL == -1) return null
            val packetLen = ((lenH and 0xFF) shl 8) or (lenL and 0xFF)
            val payload = ByteArray(packetLen)
            var read = 0
            while (read < packetLen) {
                val r = input.read(payload, read, packetLen - read)
                if (r == -1) return null
                read += r
            }

            val rawPacket = ByteArray(4 + packetLen)
            rawPacket[0] = 0x24
            rawPacket[1] = channel.toByte()
            rawPacket[2] = lenH.toByte()
            rawPacket[3] = lenL.toByte()
            System.arraycopy(payload, 0, rawPacket, 4, packetLen)

            return RtspFrame.Binary(channel, payload, rawPacket)
        }

        // RTSP Text message
        val headerStream = ByteArrayOutputStream()
        headerStream.write(firstByte)
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            headerStream.write(b)
            when (state) {
                0 -> if (b == '\r'.code) state = 1 else state = 0
                1 -> if (b == '\n'.code) state = 2 else if (b == '\r'.code) state = 1 else state = 0
                2 -> if (b == '\r'.code) state = 3 else state = 0
                3 -> if (b == '\n'.code) break else if (b == '\r'.code) state = 1 else state = 0
            }
        }

        val headerText = headerStream.toString("UTF-8")
        var contentLength = 0
        for (line in headerText.lines()) {
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                break
            }
        }

        val body = if (contentLength > 0) {
            val bodyBytes = ByteArray(contentLength)
            var bRead = 0
            while (bRead < contentLength) {
                val r = input.read(bodyBytes, bRead, contentLength - bRead)
                if (r == -1) return null
                bRead += r
            }
            bodyBytes
        } else {
            ByteArray(0)
        }

        return RtspFrame.Text(headerText, body)
    }
}

/**
 * P5: RFC 3550 standard RTP parser with dynamic CSRC, Extension, and Padding support.
 */
object RtpPacketParser {
    fun extractSpsPps(rtpPayload: ByteArray): Pair<String, String>? {
        val (sps, pps) = extractParameterSets(rtpPayload)
        return if (sps != null && pps != null) sps to pps else null
    }

    fun extractParameterSets(rtpPayload: ByteArray): Pair<String?, String?> {
        if (rtpPayload.size < 12) return null to null
        val b0 = rtpPayload[0].toInt() and 0xFF
        val version = (b0 ushr 6) and 0x03
        if (version != 2) return null to null // Must be RTP version 2

        val padding = (b0 and 0x20) != 0
        val extension = (b0 and 0x10) != 0
        val csrcCount = b0 and 0x0F

        var headerOffset = 12 + (csrcCount * 4)
        if (rtpPayload.size < headerOffset) return null to null

        if (extension) {
            if (rtpPayload.size < headerOffset + 4) return null to null
            val extLenWords = ((rtpPayload[headerOffset + 2].toInt() and 0xFF) shl 8) or
                    (rtpPayload[headerOffset + 3].toInt() and 0xFF)
            headerOffset += 4 + (extLenWords * 4)
        }

        var payloadEnd = rtpPayload.size
        if (padding) {
            val padLen = rtpPayload[rtpPayload.size - 1].toInt() and 0xFF
            if (padLen == 0 || padLen > rtpPayload.size - headerOffset) return null to null
            payloadEnd -= padLen
        }

        if (headerOffset >= payloadEnd) return null to null

        val naluHeader = rtpPayload[headerOffset].toInt() and 0xFF
        val naluType = naluHeader and 0x1F

        var foundSps: String? = null
        var foundPps: String? = null

        if (naluType == 7) {
            foundSps = Base64.getEncoder().encodeToString(rtpPayload.copyOfRange(headerOffset, payloadEnd))
        } else if (naluType == 8) {
            foundPps = Base64.getEncoder().encodeToString(rtpPayload.copyOfRange(headerOffset, payloadEnd))
        } else if (naluType == 24) { // STAP-A
            var offset = headerOffset + 1
            while (offset + 2 < payloadEnd) {
                val naluSize = ((rtpPayload[offset].toInt() and 0xFF) shl 8) or
                        (rtpPayload[offset + 1].toInt() and 0xFF)
                offset += 2
                if (naluSize == 0 || offset + naluSize > payloadEnd) return null to null
                val subType = rtpPayload[offset].toInt() and 0x1F
                if (subType == 7) {
                    foundSps = Base64.getEncoder().encodeToString(rtpPayload.copyOfRange(offset, offset + naluSize))
                } else if (subType == 8) {
                    foundPps = Base64.getEncoder().encodeToString(rtpPayload.copyOfRange(offset, offset + naluSize))
                }
                offset += naluSize
            }
        }

        return foundSps to foundPps
    }
}

class RtpParameterSetCollector {
    private var source: List<Byte>? = null
    private var sps: String? = null
    private var pps: String? = null

    fun offer(packet: ByteArray): Pair<String, String>? {
        if (packet.size < 13 || (packet[0].toInt() and 0xC0) != 0x80) return null
        val packetSource = listOf((packet[1].toInt() and 0x7F).toByte()) + packet.slice(8..11)
        if (packetSource != source) {
            source = packetSource
            sps = null
            pps = null
        }
        val (newSps, newPps) = RtpPacketParser.extractParameterSets(packet)
        if (newSps != null) {
            if (sps != null && sps != newSps) pps = null
            sps = newSps
        }
        if (newPps != null) pps = newPps
        val currentSps = sps ?: return null
        val currentPps = pps ?: return null
        return currentSps to currentPps
    }
}

