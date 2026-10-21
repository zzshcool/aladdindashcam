package com.aladdin.dashcam.network

import android.net.Network
import android.util.Log
import com.aladdin.dashcam.model.ConnectionStatus
import com.aladdin.dashcam.model.DashCamDeviceStatus
import com.aladdin.dashcam.model.DeviceMode
import com.aladdin.dashcam.model.RecordingStatus
import com.aladdin.dashcam.protocol.GPSocketProtocol
import com.aladdin.dashcam.protocol.GPSocketResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Calendar

class DashCamClient(
    val host: String = "192.168.25.1",
    val port: Int = 8081,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    @Volatile
    private var boundNetwork: Network? = null
    @Volatile
    private var connectedNetwork: Network? = null

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var pollingJob: Job? = null
    private val commandLock = Any()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _deviceStatus = MutableStateFlow(DashCamDeviceStatus())
    val deviceStatus: StateFlow<DashCamDeviceStatus> = _deviceStatus.asStateFlow()

    /**
     * Updates bound network and invalidates obsolete session if network changed (C04)
     */
    fun bindNetwork(network: Network?) {
        synchronized(commandLock) {
            if (this.boundNetwork != network) {
                Log.i(TAG, "Network changed from ${this.boundNetwork} to $network, invalidating existing session")
                this.boundNetwork = network
                if (socket != null && connectedNetwork != network) {
                    disconnectInternal()
                }
            }
        }
    }

    fun start() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    ensureConnected()
                    val status = queryStatusInternal()
                    if (status != null && status.recordingStatus != RecordingStatus.UNKNOWN) {
                        _deviceStatus.value = status
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                    } else {
                        markStatusExpired()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Communication cycle warning: ${e.javaClass.simpleName} - ${e.message}")
                    disconnectInternal()
                }
                delay(2500)
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        scope.launch(Dispatchers.IO) {
            disconnectInternal()
        }
    }

    private fun markStatusExpired() {
        // C07: Invalidate stale recording and battery info on disconnection or read failure
        _deviceStatus.value = DashCamDeviceStatus(
            mode = DeviceMode.UNKNOWN,
            recordingStatus = RecordingStatus.UNKNOWN,
            lastUpdatedTimestamp = 0L
        )
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    private fun ensureConnected() {
        synchronized(commandLock) {
            val currentTargetNetwork = boundNetwork ?: run {
                Log.w(TAG, "Cannot connect: waiting for dedicated Wi-Fi network (boundNetwork is null)")
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                throw IllegalStateException("Wi-Fi network not bound yet")
            }
            if (socket != null && socket!!.isConnected && !socket!!.isClosed) {
                if (connectedNetwork == currentTargetNetwork) {
                    return
                }
                disconnectInternal()
            }

            _connectionStatus.value = ConnectionStatus.CONNECTING
            val newSocket = Socket()
            currentTargetNetwork.bindSocket(newSocket)
            Log.i(TAG, "Successfully bound new socket to dedicated Wi-Fi network $currentTargetNetwork")

            newSocket.connect(InetSocketAddress(host, port), 3000)
            newSocket.soTimeout = 4000
            socket = newSocket
            connectedNetwork = currentTargetNetwork
            inputStream = newSocket.getInputStream()
            outputStream = newSocket.getOutputStream()
            Log.i(TAG, "Connected to dashcam at $host:$port")
        }
    }

    private fun disconnectInternal() {
        synchronized(commandLock) {
            try {
                inputStream?.close()
                outputStream?.close()
                socket?.close()
            } catch (e: Exception) {
                // Ignore socket close exception
            } finally {
                inputStream = null
                outputStream = null
                socket = null
                connectedNetwork = null
                markStatusExpired()
            }
        }
    }

    /**
     * Unified send and receive with strict pair and ACK/NAK checking (C01)
     */
    private fun sendAndReceive(expectedMode: Byte, expectedCmd: Byte, packet: ByteArray): GPSocketResponse {
        val out = outputStream ?: throw java.io.IOException("Socket output stream is null")
        val inStream = inputStream ?: throw java.io.IOException("Socket input stream is null")

        out.write(packet)
        out.flush()

        val header = ByteArray(12)
        readFully(inStream, header)

        val lenBytes = ByteArray(2)
        readFully(inStream, lenBytes)
        val lenOrError = ((lenBytes[1].toInt() and 0xFF) shl 8) or (lenBytes[0].toInt() and 0xFF)

        // Read remaining payload if ACK with positive length
        val dataBytes = if (header[8].toInt() == GPSocketProtocol.TYPE_ACK.toInt() && lenOrError > 0) {
            val payload = ByteArray(lenOrError)
            readFully(inStream, payload)
            lenBytes + payload
        } else {
            lenBytes
        }

        val resp = GPSocketProtocol.decodeResponse(header, dataBytes)
        return when (resp) {
            is GPSocketResponse.Ack -> {
                if (resp.mode != expectedMode || resp.command != expectedCmd) {
                    val errMsg = "Mismatched ACK pair: expected mode=0x${expectedMode.toString(16).uppercase()}, cmd=0x${expectedCmd.toString(16).uppercase()}, got mode=0x${resp.mode.toString(16).uppercase()}, cmd=0x${resp.command.toString(16).uppercase()}"
                    Log.e(TAG, errMsg)
                    GPSocketResponse.Error(errMsg)
                } else {
                    resp
                }
            }
            is GPSocketResponse.Nak -> {
                Log.w(TAG, "Device returned NAK: mode=0x${resp.mode.toString(16).uppercase()}, cmd=0x${resp.command.toString(16).uppercase()}, errorCode=0x${resp.errorCode.toString(16).uppercase()}")
                resp
            }
            is GPSocketResponse.Error -> {
                Log.e(TAG, "Protocol decoding error: ${resp.message}")
                resp
            }
        }
    }

    private fun queryStatusInternal(): DashCamDeviceStatus? {
        synchronized(commandLock) {
            val req = GPSocketProtocol.buildGetStatusPacket()
            val resp = sendAndReceive(GPSocketProtocol.MODE_DEVICE, GPSocketProtocol.CMD_GET_STATUS, req)
            return if (resp is GPSocketResponse.Ack) {
                GPSocketProtocol.parseDeviceStatus(resp.payload)
            } else {
                null
            }
        }
    }

    suspend fun queryStatus(): DashCamDeviceStatus? = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            try {
                ensureConnected()
                val status = queryStatusInternal()
                if (status != null && status.recordingStatus != RecordingStatus.UNKNOWN) {
                    _deviceStatus.value = status
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                } else {
                    markStatusExpired()
                }
                status
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query status", e)
                disconnectInternal()
                null
            }
        }
    }

    /**
     * Ensures dashcam is in the desired recording state safely (Chapter 25.5, C02)
     */
    suspend fun ensureRecording(desiredRecording: Boolean): Boolean = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            try {
                ensureConnected()
                val currentStatus = queryStatusInternal() ?: run {
                    Log.w(TAG, "Cannot ensure recording: failed to retrieve current status")
                    return@withContext false
                }

                // C02: If current status is UNKNOWN, never blindly toggle!
                if (currentStatus.recordingStatus == RecordingStatus.UNKNOWN) {
                    Log.e(TAG, "Cannot ensure recording: device recording status is UNKNOWN. Prohibiting blind toggle.")
                    return@withContext false
                }

                val isCurrentlyRecording = currentStatus.recordingStatus == RecordingStatus.RECORDING
                if (isCurrentlyRecording == desiredRecording) {
                    Log.i(TAG, "Recording state already matches desired state: $desiredRecording")
                    return@withContext true
                }

                Log.i(TAG, "Sending record toggle to transition towards $desiredRecording")
                val req = GPSocketProtocol.buildRecordTogglePacket()
                val resp = sendAndReceive(GPSocketProtocol.MODE_RECORD, GPSocketProtocol.CMD_RECORD_TOGGLE, req)

                if (resp !is GPSocketResponse.Ack) {
                    Log.e(TAG, "Record toggle command rejected by hardware: $resp")
                    return@withContext false
                }

                // Verify by reading back status
                Thread.sleep(800)
                val updatedStatus = queryStatusInternal()
                if (updatedStatus != null && updatedStatus.recordingStatus != RecordingStatus.UNKNOWN) {
                    _deviceStatus.value = updatedStatus
                    val succeeded = (updatedStatus.recordingStatus == RecordingStatus.RECORDING) == desiredRecording
                    Log.i(TAG, "Recording verification readback: expected $desiredRecording, actual isRec=${updatedStatus.recordingStatus}, result=$succeeded")
                    return@withContext succeeded
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error in ensureRecording", e)
                disconnectInternal()
                false
            }
        }
    }

    /**
     * Takes a photo with proper NAK error handling (C01)
     */
    suspend fun takePhoto(): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            try {
                ensureConnected()
                val req = GPSocketProtocol.buildCapturePacket()
                val resp = sendAndReceive(GPSocketProtocol.MODE_CAPTURE, GPSocketProtocol.CMD_CAPTURE_PHOTO, req)

                when (resp) {
                    is GPSocketResponse.Ack -> {
                        Log.i(TAG, "Capture photo accepted by hardware")
                        Result.success(Unit)
                    }
                    is GPSocketResponse.Nak -> {
                        val errMsg = "Capture photo NAK error: 0x${resp.errorCode.toString(16).uppercase()}"
                        Log.w(TAG, errMsg)
                        Result.failure(Exception(errMsg))
                    }
                    is GPSocketResponse.Error -> {
                        Result.failure(Exception(resp.message))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error taking photo", e)
                disconnectInternal()
                Result.failure(e)
            }
        }
    }

    /**
     * Synchronizes phone's local time to dashcam RTC via GPVENDOR packet (Chapter 25.9, C01)
     */
    suspend fun syncRtc(): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            try {
                ensureConnected()
                val cal = Calendar.getInstance()
                val req = GPSocketProtocol.buildVendorTimePacket(
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH),
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    cal.get(Calendar.SECOND)
                )

                val resp = sendAndReceive(GPSocketProtocol.MODE_VENDOR, GPSocketProtocol.CMD_VENDOR_TIME, req)
                when (resp) {
                    is GPSocketResponse.Ack -> {
                        Log.i(TAG, "RTC sync accepted by hardware (ACK)")
                        Result.success(Unit)
                    }
                    is GPSocketResponse.Nak -> {
                        val errMsg = "RTC sync NAK error: 0x${resp.errorCode.toString(16).uppercase()}"
                        Log.w(TAG, errMsg)
                        Result.failure(Exception(errMsg))
                    }
                    is GPSocketResponse.Error -> {
                        Result.failure(Exception(resp.message))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync RTC", e)
                disconnectInternal()
                Result.failure(e)
            }
        }
    }

    /**
     * Fetches file count from hardware (C05 real album preparation)
     */
    suspend fun getFileCount(): Result<Int> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            try {
                ensureConnected()
                val req = GPSocketProtocol.buildGetFileCountPacket()
                val resp = sendAndReceive(GPSocketProtocol.MODE_PLAYBACK, GPSocketProtocol.CMD_GET_FILE_COUNT, req)
                if (resp is GPSocketResponse.Ack && resp.payload.size >= 2) {
                    val count = ((resp.payload[1].toInt() and 0xFF) shl 8) or (resp.payload[0].toInt() and 0xFF)
                    Log.i(TAG, "Hardware reported file count: $count")
                    Result.success(count)
                } else if (resp is GPSocketResponse.Nak) {
                    Result.failure(Exception("GetFileCount returned NAK: 0x${resp.errorCode.toString(16).uppercase()}"))
                } else {
                    Result.failure(Exception("Invalid response for GetFileCount: $resp"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get file count", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Reads parameter from hardware (C06)
     */
    suspend fun getParameter(paramId: Int): Result<ByteArray> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            try {
                ensureConnected()
                val req = GPSocketProtocol.buildGetParameterPacket(paramId)
                val resp = sendAndReceive(GPSocketProtocol.MODE_PARAM, GPSocketProtocol.CMD_GET_PARAM, req)
                when (resp) {
                    is GPSocketResponse.Ack -> {
                        Result.success(resp.payload)
                    }
                    is GPSocketResponse.Nak -> {
                        Result.failure(Exception("GetParameter NAK: 0x${resp.errorCode.toString(16).uppercase()}"))
                    }
                    is GPSocketResponse.Error -> {
                        Result.failure(Exception(resp.message))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get parameter $paramId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Sets parameter on hardware and returns ACK/NAK result (C06)
     */
    suspend fun setParameter(paramId: Int, value: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            try {
                ensureConnected()
                val req = GPSocketProtocol.buildSetParameterPacket(paramId, value)
                val resp = sendAndReceive(GPSocketProtocol.MODE_PARAM, GPSocketProtocol.CMD_SET_PARAM, req)
                when (resp) {
                    is GPSocketResponse.Ack -> {
                        Log.i(TAG, "SetParameter accepted by hardware: paramId=0x${paramId.toString(16).uppercase()}")
                        Result.success(Unit)
                    }
                    is GPSocketResponse.Nak -> {
                        val msg = "SetParameter NAK: 0x${resp.errorCode.toString(16).uppercase()}"
                        Log.w(TAG, msg)
                        Result.failure(Exception(msg))
                    }
                    is GPSocketResponse.Error -> {
                        Result.failure(Exception(resp.message))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set parameter $paramId", e)
                Result.failure(e)
            }
        }
    }

    private fun readFully(inStream: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = inStream.read(buffer, offset, buffer.size - offset)
            if (read < 0) {
                throw java.io.EOFException("Unexpected EOF while reading socket")
            }
            offset += read
        }
    }

    companion object {
        private const val TAG = "DashCamClient"
    }
}
