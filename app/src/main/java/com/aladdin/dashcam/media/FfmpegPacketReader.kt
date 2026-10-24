package com.aladdin.dashcam.media

import android.media.MediaFormat
import java.nio.ByteBuffer

/** FFmpeg owns demuxing and audio decoding; compressed video goes to MediaCodec. */
internal class FfmpegPacketReader(source: String) : AutoCloseable {
    companion object {
        init { System.loadLibrary("ffmpeg"); System.loadLibrary("dashcam_export") }
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
    private var handle = nativeOpen(source)
    private val info = nativeInfo(handle)
    val width = info[0].toInt()
    val height = info[1].toInt()
    val durationUs = info[2]
    val sampleRate = info[4].toInt()
    val channels = info[5].toInt()
    val rotation = info[6].toInt()
    val mime = if (info[3] == 0L) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
    val packet = ByteBuffer.allocateDirect(4 * 1024 * 1024)

    fun videoFormat(): MediaFormat {
        val format = MediaFormat.createVideoFormat(mime, width, height)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, packet.capacity())
        format.setInteger(MediaFormat.KEY_ROTATION, rotation)
        val config = nativeConfig(handle)
        if (config.isNotEmpty()) {
            if (config[0] == 1.toByte()) {
                val csd = java.io.ByteArrayOutputStream()
                var offset = 0
                fun nal() {
                    require(offset + 2 <= config.size)
                    val length = ((config[offset].toInt() and 255) shl 8) or (config[offset + 1].toInt() and 255)
                    offset += 2
                    require(offset + length <= config.size)
                    csd.write(START_CODE); csd.write(config, offset, length); offset += length
                }
                if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                    require(config.size >= 7)
                    offset = 6
                    repeat(config[5].toInt() and 31) { nal() }
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(csd.toByteArray())); csd.reset()
                    require(offset < config.size)
                    val count = config[offset++].toInt() and 255
                    repeat(count) { nal() }
                    format.setByteBuffer("csd-1", ByteBuffer.wrap(csd.toByteArray()))
                } else {
                    require(config.size >= 23)
                    offset = 23
                    repeat(config[22].toInt() and 255) {
                        require(offset + 3 <= config.size)
                        offset++
                        val count = ((config[offset].toInt() and 255) shl 8) or (config[offset + 1].toInt() and 255)
                        offset += 2
                        repeat(count) { nal() }
                    }
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(csd.toByteArray()))
                }
            } else format.setByteBuffer("csd-0", ByteBuffer.wrap(config))
        }
        return format
    }
    fun read(): LongArray? { packet.clear(); return nativeRead(handle, packet)?.also { packet.limit(it[1].toInt()) } }
    fun seek(positionUs: Long) = nativeSeek(handle, positionUs)
    override fun close() { if (handle != 0L) { nativeClose(handle); handle = 0 } }
    private external fun nativeOpen(source: String): Long
    private external fun nativeInfo(handle: Long): LongArray
    private external fun nativeConfig(handle: Long): ByteArray
    private external fun nativeRead(handle: Long, buffer: ByteBuffer): LongArray?
    private external fun nativeSeek(handle: Long, positionUs: Long)
    private external fun nativeClose(handle: Long)
}
