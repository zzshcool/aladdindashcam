package com.aladdin.dashcam.protocol

import com.aladdin.dashcam.model.DashCamDeviceStatus
import com.aladdin.dashcam.model.DeviceMode
import com.aladdin.dashcam.model.MediaItem
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

    /**
     * Validates that the received response matches the expected request mode and command (C01 / VR-02 / R3-03).
     * If an ACK has a mismatched mode or command, it is converted to GPSocketResponse.Error.
     */
    fun validateResponsePair(expectedMode: Byte, expectedCmd: Byte, response: GPSocketResponse): GPSocketResponse {
        return when (response) {
            is GPSocketResponse.Ack -> {
                if (response.mode != expectedMode || response.command != expectedCmd) {
                    GPSocketResponse.Error(
                        "Mismatched ACK pair: expected mode=0x${expectedMode.toString(16).uppercase()}, cmd=0x${expectedCmd.toString(16).uppercase()}, got mode=0x${response.mode.toString(16).uppercase()}, cmd=0x${response.command.toString(16).uppercase()}"
                    )
                } else {
                    response
                }
            }
            else -> response
        }
    }

    // Modes
    const val MODE_DEVICE: Byte = 0x00
    const val MODE_RECORD: Byte = 0x01
    const val MODE_CAPTURE: Byte = 0x02
    const val MODE_PLAYBACK: Byte = 0x03
    const val MODE_PARAM: Byte = 0x04
    const val MODE_VENDOR: Byte = 0xFF.toByte()

    // Commands
    const val CMD_SET_MODE: Byte = 0x00
    const val CMD_GET_STATUS: Byte = 0x01
    const val CMD_GET_PARAM_FILE: Byte = 0x02
    const val CMD_POWER_OFF: Byte = 0x03
    const val CMD_RESTART_STREAM: Byte = 0x04
    const val CMD_RECORD_TOGGLE: Byte = 0x00
    const val CMD_RECORD_AUDIO: Byte = 0x01
    const val CMD_CAPTURE_PHOTO: Byte = 0x00
    const val CMD_PLAYBACK_START: Byte = 0x00
    const val CMD_PLAYBACK_PAUSE: Byte = 0x01
    const val CMD_GET_FILE_COUNT: Byte = 0x02
    const val CMD_GET_NAME_LIST: Byte = 0x03
    const val CMD_GET_THUMBNAIL: Byte = 0x04
    const val CMD_GET_RAW_DATA: Byte = 0x05
    const val CMD_PLAYBACK_STOP: Byte = 0x06
    const val CMD_DELETE_FILE: Byte = 0x08
    const val CMD_GET_PARAM: Byte = 0x00
    const val CMD_SET_PARAM: Byte = 0x01
    const val CMD_VENDOR_TIME: Byte = 0x00

    // Parameter IDs per Spec 25.7 & Menu.xml (VR-01)
    const val PARAM_RESOLUTION = 0x0000 // 錄影解析度
    const val PARAM_EXPOSURE = 0x0001   // 曝光補償
    const val PARAM_LOOP_RECORDING = 0x0003 // 循環錄影
    const val PARAM_WATERMARK = 0x0107  // 時間浮水印 (Menu.xml 0x00000107 Date/Time)
    const val PARAM_CAPTURE_RESOLUTION = 0x0100 // 圖片解析度 (256)
    const val PARAM_CAPTURE_QUALITY = 0x0102   // 圖片畫質 (258 per Menu.xml)
    const val PARAM_VERSION = 0x0209           // 韌體版本 (521)
    const val PARAM_FORMAT_STORAGE = 0x0207 // Generalplus 原廠 Format_ID，動作不帶值

    fun buildFormatStoragePacket(): ByteArray = buildSetParameterPacket(PARAM_FORMAT_STORAGE, byteArrayOf())

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

    fun buildPowerOffPacket(): ByteArray =
        buildPacket(TYPE_CMD, MODE_DEVICE, CMD_POWER_OFF)

    fun buildRecordTogglePacket(): ByteArray =
        buildPacket(TYPE_CMD, MODE_RECORD, CMD_RECORD_TOGGLE)

    fun buildRecordAudioTogglePacket(): ByteArray =
        buildPacket(TYPE_CMD, MODE_RECORD, CMD_RECORD_AUDIO)

    fun buildRestartStreamPacket(): ByteArray =
        buildPacket(TYPE_CMD, MODE_DEVICE, CMD_RESTART_STREAM)

    fun buildSetModePacket(mode: DeviceMode): ByteArray {
        require(mode != DeviceMode.UNKNOWN)
        return buildPacket(TYPE_CMD, MODE_DEVICE, CMD_SET_MODE, byteArrayOf(mode.raw.toByte()))
    }

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

    fun buildGetRawDataPacket(deviceIndex: Int): ByteArray {
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(deviceIndex.toShort())
        return buildPacket(TYPE_CMD, MODE_PLAYBACK, CMD_GET_RAW_DATA, payload.array())
    }

    fun buildPlaybackStartPacket(deviceIndex: Int): ByteArray {
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(deviceIndex.toShort())
        return buildPacket(TYPE_CMD, MODE_PLAYBACK, CMD_PLAYBACK_START, payload.array())
    }

    fun buildPlaybackPausePacket(): ByteArray =
        buildPacket(TYPE_CMD, MODE_PLAYBACK, CMD_PLAYBACK_PAUSE)

    fun buildPlaybackStopPacket(): ByteArray {
        val payload = byteArrayOf(0)
        return buildPacket(TYPE_CMD, MODE_PLAYBACK, CMD_PLAYBACK_STOP, payload)
    }

    fun buildGetThumbnailPacket(deviceIndex: Int): ByteArray {
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(deviceIndex.toShort())
        return buildPacket(TYPE_CMD, MODE_PLAYBACK, CMD_GET_THUMBNAIL, payload.array())
    }

    fun buildDeleteFilePacket(deviceIndex: Int): ByteArray {
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(deviceIndex.toShort())
        return buildPacket(TYPE_CMD, MODE_PLAYBACK, CMD_DELETE_FILE, payload.array())
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
        // A02: byte 3 bit 7 為裝置回報之 SD 檔案刪除/管理支援旗標 (canDeleteFile)，不等於完整卡片健康或容量就緒
        val sdCardReady = (byte3 and 0x80) != 0
        val isCharging = (byte3 and 0x01) != 0

        val recordResolutionIndex = payload[4].toInt() and 0xFF

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        // V1: 原廠 APK 證實 bytes 5~8 為錄影計時/已錄秒數 (times = rawRecordSeconds * 1000)，非剩餘記憶卡容量
        val rawRecordSeconds = buffer.getInt(5).toLong() and 0xFFFFFFFFL

        val captureResolutionIndex = payload[9].toInt() and 0xFF
        val availablePhotos = buffer.getInt(10).toLong() and 0xFFFFFFFFL

        // A02: 依據原廠 o6/b.java:56，短狀態封包 (長度 < 16) 韌體未回報 SD 卡在位與健康狀態
        var sdCardState = -1 // -1: 韌體未回報詳細狀態
        var sdFreeBytes = -1L
        var sdTotalBytes = -1L

        if (payload.size >= 16) {
            val rawSdState = payload[15].toInt() and 0xFF
            sdCardState = when (rawSdState) {
                0 -> 0 // 無卡
                1 -> 1 // 正常
                2 -> 2 // 寫保護/異常
                else -> rawSdState
            }
            if (rawSdState == 1 && payload.size >= 24) {
                // OEM o6/b.java:49 + utils/c.k: bytes 16..19 free KB, bytes 20..23 capacity KB (Big Endian)
                val freeKb = (((payload[16].toLong() and 0xFF) shl 24) or
                              ((payload[17].toLong() and 0xFF) shl 16) or
                              ((payload[18].toLong() and 0xFF) shl 8) or
                              (payload[19].toLong() and 0xFF)) and 0xFFFFFFFFL
                val totalKb = (((payload[20].toLong() and 0xFF) shl 24) or
                               ((payload[21].toLong() and 0xFF) shl 16) or
                               ((payload[22].toLong() and 0xFF) shl 8) or
                               (payload[23].toLong() and 0xFF)) and 0xFFFFFFFFL
                sdFreeBytes = freeKb * 1024L
                sdTotalBytes = totalKb * 1024L
            }
        }

        return DashCamDeviceStatus(
            mode = mode,
            recordingStatus = recordingStatus,
            audioEnabled = audioEnabled,
            rtspFlag = rtspFlag,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            sdCardReady = sdCardReady,
            recordResolutionIndex = recordResolutionIndex,
            rawRecordSeconds = rawRecordSeconds,
            captureResolutionIndex = captureResolutionIndex,
            availablePhotos = availablePhotos,
            sdCardState = sdCardState,
            sdFreeBytes = sdFreeBytes,
            sdTotalBytes = sdTotalBytes,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
    }

    data class FileTypeMapping(
        val prefix: String,
        val ext: String,
        val isVideo: Boolean,
        val isEmergency: Boolean
    )

    fun resolveFileType(typeChar: Char): FileTypeMapping {
        return when (typeChar) {
            'A' -> FileTypeMapping("MOVI", "mov", isVideo = true, isEmergency = false)
            'L' -> FileTypeMapping("LOCK", "mov", isVideo = true, isEmergency = true)
            'm' -> FileTypeMapping("MOVI", "mp4", isVideo = true, isEmergency = false)
            'l' -> FileTypeMapping("LOCK", "mp4", isVideo = true, isEmergency = true)
            's' -> FileTypeMapping("SOSO", "mp4", isVideo = true, isEmergency = false)
            'J' -> FileTypeMapping("PICT", "jpg", isVideo = false, isEmergency = false)
            'V' -> FileTypeMapping("MOVI", "avi", isVideo = true, isEmergency = false)
            'K' -> FileTypeMapping("LOCK", "avi", isVideo = true, isEmergency = true)
            'O' -> FileTypeMapping("SOSO", "avi", isVideo = true, isEmergency = false)
            'T' -> FileTypeMapping("LAPS", "mov", isVideo = true, isEmergency = false)
            't' -> FileTypeMapping("LAPS", "mp4", isVideo = true, isEmergency = false)
            'P' -> FileTypeMapping("PIR", "mov", isVideo = true, isEmergency = false)
            'C' -> FileTypeMapping("COMP", "mov", isVideo = true, isEmergency = false)
            else -> when (typeChar.code) {
                1 -> FileTypeMapping("MOVI", "mp4", isVideo = true, isEmergency = false)
                2 -> FileTypeMapping("PICT", "jpg", isVideo = false, isEmergency = false)
                3 -> FileTypeMapping("LOCK", "mp4", isVideo = true, isEmergency = true)
                else -> FileTypeMapping("MOVI", "mp4", isVideo = true, isEmergency = false)
            }
        }
    }

    /**
     * Parses real media items list from GetNameList ACK payload per Specification 25.8 & OEM CamWrapper.GP22_DEFAULT_MAPPING_STR
     */
    fun parseFileList(payload: ByteArray): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        if (payload.isEmpty()) return items

        val count = payload[0].toInt() and 0xFF
        if (count == 0) {
            require(payload.size == 1) { "Empty file-list page contains trailing data" }
            return items
        }
        require((payload.size - 1) % count == 0) { "Truncated file-list page" }
        val stride = (payload.size - 1) / count
        require(stride >= 13) { "File-list record is shorter than 13 bytes" }
        var offset = 1
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) {
            buf.position(offset)
            val typeByte = buf.get()
            val typeChar = typeByte.toInt().toChar()
            val mapping = resolveFileType(typeChar)
            val deviceIndex = buf.short.toInt() and 0xFFFF
            val yearByte = buf.get().toInt() and 0xFF
            val month = buf.get().toInt() and 0xFF
            val day = buf.get().toInt() and 0xFF
            val hour = buf.get().toInt() and 0xFF
            val minute = buf.get().toInt() and 0xFF
            val second = buf.get().toInt() and 0xFF
            val rawSizeKb = buf.int.toLong() and 0xFFFFFFFFL

            val year = if (yearByte < 100) 2000 + yearByte else 1900 + yearByte

            val hasValidDate = (year in 2020..2040 && month in 1..12 && day in 1..31 &&
                    hour in 0..23 && minute in 0..59 && second in 0..59)

            val dateStr = if (hasValidDate) {
                String.format(java.util.Locale.US, "%04d%02d%02d%02d%02d%02d", year, month, day, hour, minute, second)
            } else {
                ""
            }

            val timestamp = if (hasValidDate) {
                try {
                    val sdf = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply {
                        isLenient = false
                    }
                    sdf.parse(dateStr)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            } else {
                0L
            }

            // Vidure 標準檔名命名格式: yyyyMMddHHmmss_PREFIX0001.EXT (或無時間時 PREFIX0001.EXT)
            val baseName = String.format(java.util.Locale.US, "%s%04d.%s", mapping.prefix, deviceIndex, mapping.ext)
            val fileName = if (dateStr.isNotEmpty()) "${dateStr}_${baseName}" else baseName

            // AM01 / 原廠 c.java:268: 硬體回傳 rawSize 單位為 KB，真實二進位 bytes 為 rawSize * 1024
            val sizeBytes = rawSizeKb * 1024L

            items.add(
                MediaItem(
                    id = "DEV_$deviceIndex",
                    fileName = fileName,
                    sizeBytes = sizeBytes,
                    durationSeconds = 0,
                    timestampMillis = timestamp,
                    isVideo = mapping.isVideo,
                    isEmergency = mapping.isEmergency,
                    isDownloaded = false,
                    deviceIndex = deviceIndex
                )
            )
            offset += stride
        }
        return items
    }
}
