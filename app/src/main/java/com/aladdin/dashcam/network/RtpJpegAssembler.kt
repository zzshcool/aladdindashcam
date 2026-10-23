package com.aladdin.dashcam.network

import java.io.ByteArrayOutputStream

/** Generalplus RTP/JPEG carries the complete JPEG, including SOI/EOI, after its JPEG header. */
internal class RtpJpegAssembler(private val maxFrameBytes: Int = 4 * 1024 * 1024) {
    private val frame = ByteArrayOutputStream()
    private var timestamp = -1L
    private var source = -1L
    private var nextSequence = -1
    private var assembling = false

    fun offer(packet: ByteArray, length: Int = packet.size): ByteArray? {
        if (length < 20 || length > packet.size || packet[0].toInt() and 0xc0 != 0x80 ||
            packet[1].toInt() and 0x7f != 26) return null
        fun byte(offset: Int) = packet[offset].toInt() and 255
        fun uint(offset: Int) = (offset until offset + 4).fold(0L) { value, i -> (value shl 8) or byte(i).toLong() }
        var header = 12 + (byte(0) and 15) * 4
        if (header + 8 > length) return null
        if (byte(0) and 16 != 0) {
            if (header + 4 > length) return null
            header += 4 + ((byte(header + 2) shl 8) or byte(header + 3)) * 4
            if (header + 8 > length) return null
        }
        val padding = if (byte(0) and 32 != 0) byte(length - 1) else 0
        if (padding > length - header - 8 || (byte(0) and 32 != 0 && padding == 0)) return null
        val end = length - padding
        val offset = (byte(header + 1) shl 16) or (byte(header + 2) shl 8) or byte(header + 3)
        val sequence = (byte(2) shl 8) or byte(3)
        val currentTimestamp = uint(4)
        val currentSource = uint(8)
        val data = header + 8
        if (offset == 0) {
            frame.reset()
            timestamp = currentTimestamp
            source = currentSource
            assembling = data + 2 <= end && byte(data) == 255 && byte(data + 1) == 216
            nextSequence = sequence
        }
        if (!assembling) return null
        if (sequence != nextSequence || timestamp != currentTimestamp || source != currentSource ||
            offset != frame.size() || frame.size().toLong() + end - data > maxFrameBytes) {
            assembling = false
            frame.reset()
            return null
        }
        frame.write(packet, data, end - data)
        nextSequence = (sequence + 1) and 65535
        if (byte(1) and 128 == 0) return null
        assembling = false
        val jpeg = frame.toByteArray()
        frame.reset()
        return jpeg.takeIf { it.size >= 4 && it[it.size - 2].toInt() and 255 == 255 && it.last().toInt() and 255 == 217 }
    }
}
