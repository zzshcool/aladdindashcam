package com.aladdin.dashcam.protocol

import com.aladdin.dashcam.model.DashCamDeviceStatus
import com.aladdin.dashcam.model.DeviceMode
import com.aladdin.dashcam.model.RecordingStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class GPSocketResponse {
    data class Ack(
        val mode: Byte,
        val command: Byte,
        val payload: ByteArray
    ) : GPSocketResponse() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ack) return false
            if (mode != other.mode) return false
            if (command != other.command) return false
            return payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = mode.toInt()
            result = 31 * result + command.toInt()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    data class Nak(
        val mode: Byte,
        val command: Byte,
        val errorCode: Int
    ) : GPSocketResponse()

    data class Error(
        val message: String
    ) : GPSocketResponse()
}

object GPSocketProtocol {
    private val TAG_BYTES = "GPSOCKET".toByteArray(Charsets.US_ASCII)
    const val TYPE_CMD: Short = 1
    const val TYPE_ACK: Short = 2
    const val TYPE_NAK: Short = 3

    // Modes
    const val MODE_DEVICE: Byte = 0x00
    const val MODE_RECORD: Byte = 0x01
    const val MODE_CAPTURE: Byte = 0x02
    const val MODE_PLAYBACK: Byte = 0x03
    const val MODE_PARAM: Byte = 0x04
    const val MODE_VENDOR: Byte = 0xFF.toByte()

    // Commands
    const val CMD_GET_STATUS: Byte = 0x01
    const val CMD_GET_PARAM_FILE: Byte = 0x02
    const val CMD_RESTART_STREAM: Byte = 0x04
    const val CMD_RECORD_TOGGLE: Byte = 0x00
    const val CMD_CAPTURE_PHOTO: Byte = 0x00
    const val CMD_PLAYBACK_START: Byte = 0x00
    const val CMD_PLAYBACK_PAUSE: Byte = 0x01
    const val CMD_GET_FILE_COUNT: Byte = 0x02
    const val CMD_GET_NAME_LIST: Byte = 0x03
    const val CMD_GET_THUMBNAIL: Byte = 0x04
    const val CMD_GET_RAW_DATA: Byte = 0x05
    const val CMD_PLAYBACK_STOP: Byte = 0x06
    const val CMD_GET_PARAM: Byte = 0x00
    const val CMD_SET_PARAM: Byte = 0x01
    const val CMD_VENDOR_TIME: Byte = 0x00

    // Parameter IDs per Spec 25.7 & Menu.xml (VR-01)
    const val PARAM_RESOLUTION = 0x0000 // 錄影解析度
    const val PARAM_EXPOSURE = 0x0001   // 曝光值
    const val PARAM_LOOP_RECORDING = 0x0003 // 循環錄影
    const val PARAM_WATERMARK = 0x0004  // 浮水印

    // Error Codes (Spec 25.10)
    const val ERR_SERVER_IS_BUSY = 0xFFFF
    const val ERR_INVALID_CMD = 0xFFFE
    const val ERR_TIMEOUT = 0xFFFD
    const val ERR_MODE_ERROR = 0xFFFC
    const val ERR_NO_STORAGE = 0xFFFB
    const val ERR_WRITE_FAIL = 0xFFFA
    const val ERR_GET_FILE_LIST_FAIL = 0xFFF9
    const val ERR_GET_THUMBNAIL_FAIL = 0xFFF8
    const val ERR_FULL_STORAGE = 0xFFF7
    const val ERR_NO_FILE = 0xFFF3

    fun buildPacket(type: Short, mode: Byte, command: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val buffer = ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(TAG_BYTES)
        buffer.putShort(type)
        buffer.put(mode)
        buffer.put(command)
        if (payload.isNotEmpty()) {
            buffer.put(payload)
        }
        return buffer.array()
    }

    fun buildGetStatusPacket(): ByteArray =
        buildPacket(TYPE_CMD, MODE_DEVICE, CMD_GET_STATUS)

    fun buildRecordTogglePacket(): ByteArray =
        buildPacket(TYPE_CMD, MODE_RECORD, CMD_RECORD_TOGGLE)

    fun buildCapturePacket(): ByteArray =
        buildPacket(TYPE_CMD, MODE_CAPTURE, CMD_CAPTURE_PHOTO)

    fun buildGetFileCountPacket(): ByteArray =
        buildPacket(TYPE_CMD, MODE_PLAYBACK, CMD_GET_FILE_COUNT)

    fun buildGetNameListPacket(flag: Byte = 0, cursor: Short = 0): ByteArray {
        val payload = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(flag)
        payload.putShort(cursor)
        return buildPacket(TYPE_CMD, MODE_PLAYBACK, CMD_GET_NAME_LIST, payload.array())
    }

    fun buildGetParameterPacket(paramId: Int): ByteArray {
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(paramId).array()
        return buildPacket(TYPE_CMD, MODE_PARAM, CMD_GET_PARAM, payload)
    }

    fun buildSetParameterPacket(paramId: Int, value: ByteArray): ByteArray {
        val payload = ByteBuffer.allocate(4 + 1 + value.size).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(paramId)
        payload.put(value.size.toByte())
        payload.put(value)
        return buildPacket(TYPE_CMD, MODE_PARAM, CMD_SET_PARAM, payload.array())
    }

    /**
     * Builds RTC synchronization vendor packet (17 bytes payload)
     * Format per specification 25.9:
     * 0..7: ASCII "GPVENDOR"
     * 8..9: LE16 vendor cmd = 0
     * 10..11: LE16 year
     * 12: month
     * 13: day
     * 14: hour
     * 15: minute
     * 16: second
     */
    fun buildVendorTimePacket(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): ByteArray {
        val vendorTag = "GPVENDOR".toByteArray(Charsets.US_ASCII)
        val payload = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(vendorTag)
        payload.putShort(0.toShort()) // vendor command 0 = set time
        payload.putShort(year.toShort())
        payload.put(month.toByte())
        payload.put(day.toByte())
        payload.put(hour.toByte())
        payload.put(minute.toByte())
        payload.put(second.toByte())
        return buildPacket(TYPE_CMD, MODE_VENDOR, CMD_VENDOR_TIME, payload.array())
    }

    fun decodeResponse(header: ByteArray, dataBytes: ByteArray): GPSocketResponse {
        if (header.size < 12) {
            return GPSocketResponse.Error("Header length ${header.size} is less than 12")
        }
        val tag = String(header, 0, 8, Charsets.US_ASCII)
        if (tag != "GPSOCKET") {
            return GPSocketResponse.Error("Invalid tag: $tag")
        }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(8)
        val type = buf.short
        val mode = buf.get()
        val command = buf.get()

        if (dataBytes.size < 2) {
            return GPSocketResponse.Error("Insufficient data for length/error field (${dataBytes.size} bytes)")
        }

        val dataBuf = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        val lenOrError = dataBuf.short.toInt() and 0xFFFF

        return when (type) {
            TYPE_ACK -> {
                val expectedPayloadLen = lenOrError
                val actualPayloadLen = dataBytes.size - 2
                if (actualPayloadLen < expectedPayloadLen) {
                    return GPSocketResponse.Error(
                        "Payload truncated: expected $expectedPayloadLen, got $actualPayloadLen"
                    )
                }
                val payload = ByteArray(expectedPayloadLen)
                System.arraycopy(dataBytes, 2, payload, 0, expectedPayloadLen)
                GPSocketResponse.Ack(mode, command, payload)
            }
            TYPE_NAK -> {
                GPSocketResponse.Nak(mode, command, lenOrError)
            }
            else -> {
                GPSocketResponse.Error("Unknown packet type: $type")
            }
        }
    }

    fun parseDeviceStatus(payload: ByteArray): DashCamDeviceStatus {
        if (payload.size < 14) {
            return DashCamDeviceStatus(
                mode = DeviceMode.UNKNOWN,
                recordingStatus = RecordingStatus.UNKNOWN,
                lastUpdatedTimestamp = 0L
            )
        }

        val modeRaw = payload[0].toInt() and 0xFF
        val mode = DeviceMode.fromRaw(modeRaw)

        val byte1 = payload[1].toInt() and 0xFF
        val isRecBit = (byte1 and 0x01) != 0
        // VR-07: byte 1 bit 1 為音訊旗標，實際極性（1=開或0=開）待實機比對，此處僅讀取原始位元
        val audioEnabled = (byte1 and 0x02) != 0

        val recordingStatus = if (mode == DeviceMode.RECORD) {
            if (isRecBit) RecordingStatus.RECORDING else RecordingStatus.STOPPED
        } else {
            RecordingStatus.UNKNOWN
        }

        val byte2 = payload[2].toInt() and 0xFF
        val rtspFlag = (byte2 and 0x80) != 0
        val batteryLevel = byte2 and 0x7F // level 0~4

        val byte3 = payload[3].toInt() and 0xFF
        // VR-07: byte 3 bit 7 為裝置回報之 SD 檔案刪除/管理支援旗標，不等於 SD 卡完整寫入健康
        val sdCardReady = (byte3 and 0x80) != 0
        val isCharging = (byte3 and 0x01) != 0

        val recordResolutionIndex = payload[4].toInt() and 0xFF

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val remainingSeconds = buffer.getInt(5).toLong() and 0xFFFFFFFFL

        val captureResolutionIndex = payload[9].toInt() and 0xFF
        val availablePhotos = buffer.getInt(10).toLong() and 0xFFFFFFFFL

        return DashCamDeviceStatus(
            mode = mode,
            recordingStatus = recordingStatus,
            audioEnabled = audioEnabled,
            rtspFlag = rtspFlag,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            sdCardReady = sdCardReady,
            recordResolutionIndex = recordResolutionIndex,
            remainingRecordingSeconds = remainingSeconds,
            captureResolutionIndex = captureResolutionIndex,
            availablePhotos = availablePhotos,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
    }
}
