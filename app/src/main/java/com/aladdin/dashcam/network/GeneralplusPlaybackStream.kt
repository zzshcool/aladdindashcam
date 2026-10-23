package com.aladdin.dashcam.network

import android.net.Network
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only RTSP/RTP. The caller remains the sole owner of the recorder's 8081 command session. */
class GeneralplusPlaybackStream(private val network: Network, private val host: String) {
    private val closed = AtomicBoolean(false)
    @Volatile private var control: Socket? = null
    @Volatile private var video: DatagramSocket? = null
    @Volatile private var rtcp: DatagramSocket? = null
    @Volatile private var audio: DatagramSocket? = null
    @Volatile private var audioRtcp: DatagramSocket? = null
    @Volatile private var sound: AudioTrack? = null

    @Synchronized
    fun close() {
        closed.set(true)
        video?.close()
        rtcp?.close()
        audio?.close()
        audioRtcp?.close()
        val player = sound
        sound = null
        try { player?.pause() } catch (_: IllegalStateException) { }
        try { player?.release() } catch (_: IllegalStateException) { }
        try { control?.close() } catch (_: IOException) { }
    }

    suspend fun play(
        onSourceRequested: suspend () -> Unit,
        onAudioAvailable: suspend (Boolean) -> Unit = {},
        onJpeg: suspend (ByteArray) -> Unit
    ) = withContext(Dispatchers.IO) {
        check(!closed.get()) { "Playback already closed" }
        try {
            allocatePorts().also { video = it.first; rtcp = it.second }
            onSourceRequested()
            val socket = network.socketFactory.createSocket()
            control = socket
            check(!closed.get())
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, 8080), 3000)
            socket.soTimeout = 5000
            val output = socket.getOutputStream()
            val reader = RtspFramingReader(socket.getInputStream())
            val base = "rtsp://$host:8080/?action=stream"
            var cseq = 0
            fun request(method: String, uri: String = base, headers: String = ""): RtspFrame.Text {
                output.write(("$method $uri RTSP/1.0\r\nCSeq: ${++cseq}\r\nUser-Agent: AladdinDashCam-Playback\r\n$headers\r\n").toByteArray(Charsets.US_ASCII))
                output.flush()
                val response = reader.nextFrame() as? RtspFrame.Text ?: throw IOException("Missing $method response")
                if (!response.is200Ok) throw IOException("$method rejected: ${response.firstLine}")
                return response
            }
            request("OPTIONS")
            val description = request("DESCRIBE", headers = "Accept: application/sdp\r\n")
            val sdp = description.body.toString(Charsets.UTF_8)
            var inVideo = false
            var jpeg = false
            var track: String? = null
            var inAudio = false
            var pcma = false
            var audioTrack: String? = null
            for (line in sdp.lineSequence().map { it.trim() }) {
                if (line.startsWith("m=")) {
                    inVideo = line.startsWith("m=video ")
                    inAudio = line.startsWith("m=audio ")
                }
                if (inVideo && line.startsWith("a=rtpmap:26 JPEG/90000", ignoreCase = true)) jpeg = true
                if (inVideo && line.startsWith("a=control:")) track = line.substringAfter("a=control:")
                if (inAudio && line.contains("PCMA/8000", ignoreCase = true)) pcma = true
                if (inAudio && line.startsWith("a=control:")) audioTrack = line.substringAfter("a=control:")
            }
            check(jpeg && !track.isNullOrBlank()) { "記錄器未提供 JPEG 錄影檔回放，拒絕以直播替代" }
            val ports = "${video!!.localPort}-${rtcp!!.localPort}"
            val setup = request("SETUP", resolveRtspControlUri(base, track), "Transport: RTP/AVP/UDP;unicast;client_port=$ports\r\n")
            val session = setup.getHeader("Session")?.substringBefore(';') ?: throw IOException("Missing playback session")
            val transport = setup.getHeader("Transport") ?: throw IOException("Missing playback transport")
            check(transport.contains("client_port=$ports") && !transport.contains("/TCP", ignoreCase = true)) { "Unexpected playback transport: $transport" }
            if (pcma && !audioTrack.isNullOrBlank()) {
                try {
                    allocatePorts().also { audio = it.first; audioRtcp = it.second }
                    val audioPorts = "${audio!!.localPort}-${audioRtcp!!.localPort}"
                    request("SETUP", resolveRtspControlUri(base, audioTrack),
                        "Session: $session\r\nTransport: RTP/AVP/UDP;unicast;client_port=$audioPorts\r\n")
                    val format = AudioFormat.Builder().setSampleRate(8000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    sound = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                        .setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(maxOf(1600, AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))).build()
                    sound!!.play()
                } catch (e: Exception) {
                    Log.w("GeneralplusPlayback", "Playback audio unavailable: ${e.message}")
                    synchronized(this@GeneralplusPlaybackStream) {
                        try { sound?.release() } catch (_: IllegalStateException) { }
                        sound = null
                    }
                    audio?.close()
                    audioRtcp?.close()
                }
            }
            if (closed.get()) throw IOException("Playback closed")
            request("PLAY", headers = "Session: $session\r\nRange: npt=0.000-\r\n")
            onAudioAvailable(sound != null)
            Log.i("GeneralplusPlayback", "JPEG/UDP playback started, ports=$ports")
            coroutineScope {
                val audioReceive = sound?.let { player ->
                    launch {
                        val bytes = ByteArray(65535)
                        val packet = DatagramPacket(bytes, bytes.size)
                        var audioPackets = 0
                        try {
                            while (isActive && !closed.get()) {
                                try {
                                    packet.length = bytes.size
                                    audio!!.receive(packet)
                                    if (packet.address.hostAddress == host) {
                                        val samples = decodeGeneralplusPcma(bytes, packet.length)
                                        if (samples.isNotEmpty()) {
                                            var written = 0
                                            while (written < samples.size && isActive && !closed.get()) {
                                                val count = player.write(samples, written, samples.size - written)
                                                if (count <= 0) throw IOException("Playback audio output failed: $count")
                                                written += count
                                            }
                                            if (written == samples.size && (++audioPackets == 1 || audioPackets % 500 == 0)) {
                                                Log.i("GeneralplusPlayback", "Wrote $audioPackets PCMA audio packets")
                                            }
                                        }
                                    }
                                } catch (_: SocketTimeoutException) { }
                            }
                        } catch (e: Exception) {
                            if (!closed.get()) Log.w("GeneralplusPlayback", "Audio reception stopped: ${e.message}")
                        }
                    }
                }
                val frames = Channel<ByteArray>(Channel.CONFLATED)
                val receive = launch {
                    try {
                        val assembler = RtpJpegAssembler()
                        val bytes = ByteArray(65535)
                        val packet = DatagramPacket(bytes, bytes.size)
                        var lastDataAt = SystemClock.elapsedRealtime()
                        var count = 0
                        while (isActive && !closed.get()) {
                            try {
                                packet.length = bytes.size
                                video!!.receive(packet)
                                if (packet.address.hostAddress != host) continue
                                lastDataAt = SystemClock.elapsedRealtime()
                                assembler.offer(bytes, packet.length)?.let {
                                    frames.trySend(it)
                                    if (++count % 60 == 0) Log.i("GeneralplusPlayback", "Received $count complete JPEG frames")
                                }
                            } catch (_: SocketTimeoutException) {
                                if (SystemClock.elapsedRealtime() - lastDataAt > 15_000) throw IOException("錄影檔影像傳輸已停止")
                            }
                        }
                    } finally { frames.close() }
                }
                try { for (frame in frames) onJpeg(frame) } finally {
                    video?.close()
                    receive.cancel()
                    audio?.close()
                    audioReceive?.cancel()
                }
            }
        } finally { close() }
    }

    private fun allocatePorts(): Pair<DatagramSocket, DatagramSocket> {
        repeat(20) {
            if (closed.get()) throw IOException("Playback closed")
            val data = DatagramSocket(null)
            data.bind(InetSocketAddress(0))
            if (data.localPort % 2 != 0 || data.localPort == 65535) { data.close(); return@repeat }
            var report: DatagramSocket? = null
            try {
                report = DatagramSocket(data.localPort + 1)
                network.bindSocket(data)
                network.bindSocket(report)
                data.soTimeout = 1000
                data.receiveBufferSize = 1024 * 1024
                if (closed.get()) { close(); throw IOException("Playback closed") }
                return data to report
            } catch (e: Exception) {
                data.close()
                report?.close()
                if (closed.get()) throw e
            }
        }
        throw IOException("無法配置回放 UDP 通道")
    }
}

internal fun decodeGeneralplusPcma(packet: ByteArray, length: Int = packet.size): ShortArray {
    if (length < 12 || length > packet.size || packet[0].toInt() and 0xc0 != 0x80 ||
        packet[1].toInt() and 127 !in setOf(0, 8)) return ShortArray(0)
    fun byte(offset: Int) = packet[offset].toInt() and 255
    var start = 12 + (byte(0) and 15) * 4
    if (start > length) return ShortArray(0)
    if (byte(0) and 16 != 0) {
        if (start + 4 > length) return ShortArray(0)
        start += 4 + ((byte(start + 2) shl 8) or byte(start + 3)) * 4
    }
    val padding = if (byte(0) and 32 != 0) byte(length - 1) else 0
    val end = length - padding
    if (start > end || (byte(0) and 32 != 0 && padding == 0)) return ShortArray(0)
    return ShortArray(end - start) { index ->
        val value = byte(start + index) xor 0x55
        val segment = (value and 0x70) ushr 4
        val mantissa = (value and 15) shl 4
        val magnitude = if (segment == 0) mantissa + 8 else (mantissa + 0x108) shl (segment - 1)
        (if (value and 128 != 0) magnitude else -magnitude).toShort()
    }
}
