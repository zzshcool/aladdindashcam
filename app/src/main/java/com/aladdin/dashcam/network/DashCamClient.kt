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
import com.aladdin.dashcam.model.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.net.Socket
import java.util.Calendar

enum class TargetState {
    DISCONNECTED,
    CONNECTING,
    READY
}

data class TargetIdentity(
    val requestedHost: String,
    val activeHost: String?,
    val epoch: Long,
    val state: TargetState
)

class DashCamClient(
    val host: String = "192.168.25.1",
    val port: Int = 8081,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    @Volatile
    var currentHost: String = host
        private set

    @Volatile
    var activeHost: String? = null
        private set

    @Volatile
    private var targetEpoch: Long = 1L
    private val epochCounter = java.util.concurrent.atomic.AtomicLong(1L)

    private val _targetIdentity = MutableStateFlow(
        TargetIdentity(
            requestedHost = host,
            activeHost = null,
            epoch = 1L,
            state = TargetState.DISCONNECTED
        )
    )
    val targetIdentity: StateFlow<TargetIdentity> = _targetIdentity.asStateFlow()

    @Volatile
    private var currentSessionId: Long = 0L
    private val sessionCounter = java.util.concurrent.atomic.AtomicLong(1L)

    /**
     * Updates target host atomically in lock, increments target epoch, and invalidates obsolete session (R5-01)
     */
    suspend fun updateHost(newHost: String): Long = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            val newEpoch = epochCounter.incrementAndGet()
            targetEpoch = newEpoch
            currentHost = newHost
            activeHost = null
            disconnectInternal()
            _targetIdentity.value = TargetIdentity(
                requestedHost = newHost,
                activeHost = null,
                epoch = newEpoch,
                state = TargetState.CONNECTING
            )
            Log.i(TAG, "Device host updated to $newHost (epoch: $newEpoch), synchronously invalidating session")
            newEpoch
        }
    }

    @Volatile
    private var boundNetwork: Network? = null
    @Volatile
    private var connectedNetwork: Network? = null
    @Volatile
    private var connectedHost: String? = null

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

    private val isPollingPaused = AtomicBoolean(false)

    fun pausePolling() {
        isPollingPaused.set(true)
        disconnectInternal()
        Log.i(TAG, "DashCamClient polling paused and socket disconnected for exclusivity")
    }

    fun resumePolling() {
        isPollingPaused.set(false)
        Log.i(TAG, "DashCamClient polling resumed")
    }

    fun start() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0
            while (isActive) {
                if (isPollingPaused.get()) {
                    delay(1000)
                    continue
                }
                try {
                    ensureConnected()
                    val (status, sessionId) = queryStatusWithSessionInternal()
                    synchronized(commandLock) {
                        // R4-02: Validate session generation before committing state to prevent stale responses from overriding new session
                        if (sessionId != 0L && sessionId == currentSessionId) {
                            if (status != null && status.recordingStatus != RecordingStatus.UNKNOWN) {
                                _deviceStatus.value = status
                                _connectionStatus.value = ConnectionStatus.CONNECTED
                                consecutiveFailures = 0
                            } else {
                                markStatusExpired()
                            }
                        } else {
                            Log.w(TAG, "Discarding obsolete status read from session $sessionId (current active session is $currentSessionId)")
                        }
                    }
                } catch (e: Exception) {
                    consecutiveFailures++
                    Log.w(TAG, "Communication cycle warning (fail #$consecutiveFailures): ${e.javaClass.simpleName} - ${e.message}")
                    disconnectInternal()
                }
                val delayMs = if (consecutiveFailures == 0) {
                    2500L
                } else {
                    minOf(2500L * (1L shl minOf(consecutiveFailures - 1, 3)), 15000L)
                }
                delay(delayMs)
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
                if (connectedNetwork == currentTargetNetwork && connectedHost == currentHost) {
                    return
                }
                disconnectInternal()
            }

            _connectionStatus.value = ConnectionStatus.CONNECTING
            val newSocket = Socket()
            currentTargetNetwork.bindSocket(newSocket)
            Log.i(TAG, "Successfully bound new socket to dedicated Wi-Fi network $currentTargetNetwork")

            val newSession = sessionCounter.incrementAndGet()
            newSocket.connect(InetSocketAddress(currentHost, port), 3000)
            newSocket.soTimeout = 4000
            socket = newSocket
            connectedNetwork = currentTargetNetwork
            connectedHost = currentHost
            currentSessionId = newSession
            inputStream = newSocket.getInputStream()
            outputStream = newSocket.getOutputStream()
            activeHost = currentHost
            _targetIdentity.value = TargetIdentity(
                requestedHost = currentHost,
                activeHost = currentHost,
                epoch = targetEpoch,
                state = TargetState.READY
            )
            Log.i(TAG, "Connected to dashcam at $currentHost:$port (sessionId: $newSession, epoch: $targetEpoch)")
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
                connectedHost = null
                activeHost = null
                currentSessionId = 0L
                _targetIdentity.value = TargetIdentity(
                    requestedHost = currentHost,
                    activeHost = null,
                    epoch = targetEpoch,
                    state = TargetState.DISCONNECTED
                )
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

        val rawResp = GPSocketProtocol.decodeResponse(header, dataBytes)
        val resp = GPSocketProtocol.validateResponsePair(expectedMode, expectedCmd, rawResp)
        when (resp) {
            is GPSocketResponse.Ack -> {
                // Matched ACK
            }
            is GPSocketResponse.Nak -> {
                Log.w(TAG, "Device returned NAK: mode=0x${resp.mode.toString(16).uppercase()}, cmd=0x${resp.command.toString(16).uppercase()}, errorCode=0x${resp.errorCode.toString(16).uppercase()}")
            }
            is GPSocketResponse.Error -> {
                Log.e(TAG, "Protocol decoding or pair mismatch error: ${resp.message}")
            }
        }
        return resp
    }

    private fun queryStatusWithSessionInternal(): Pair<DashCamDeviceStatus?, Long> {
        synchronized(commandLock) {
            val req = GPSocketProtocol.buildGetStatusPacket()
            val resp = sendAndReceive(GPSocketProtocol.MODE_DEVICE, GPSocketProtocol.CMD_GET_STATUS, req)
            val status = if (resp is GPSocketResponse.Ack) {
                GPSocketProtocol.parseDeviceStatus(resp.payload)
            } else {
                null
            }
            return Pair(status, currentSessionId)
        }
    }

    private fun queryStatusInternal(): DashCamDeviceStatus? {
        return queryStatusWithSessionInternal().first
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
     * Sends CMD_RESTART_STREAM (0x04) on control port 8081 to trigger live video stream output (Spec 25.4)
     */
    suspend fun restartStreaming(): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            try {
                ensureConnected()
                val req = GPSocketProtocol.buildRestartStreamPacket()
                val resp = sendAndReceive(GPSocketProtocol.MODE_DEVICE, GPSocketProtocol.CMD_RESTART_STREAM, req)
                when (resp) {
                    is GPSocketResponse.Ack -> {
                        Log.i(TAG, "Restart streaming accepted by hardware (ACK)")
                        Result.success(Unit)
                    }
                    is GPSocketResponse.Nak -> {
                        val errMsg = "Restart streaming NAK error: 0x${resp.errorCode.toString(16).uppercase()}"
                        Log.w(TAG, errMsg)
                        Result.failure(Exception(errMsg))
                    }
                    is GPSocketResponse.Error -> {
                        Result.failure(Exception(resp.message))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting streaming", e)
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
     * Fetches file count from hardware with epoch validation (Spec 25.8, C05)
     */
    suspend fun getFileCount(expectedEpoch: Long? = null): Result<Int> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                return@withContext Result.failure(IllegalStateException("Target epoch expired before getFileCount (expected $expectedEpoch, current $targetEpoch)"))
            }
            try {
                ensureConnected()
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during connect (expected $expectedEpoch, current $targetEpoch)"))
                }
                val req = GPSocketProtocol.buildGetFileCountPacket()
                val resp = sendAndReceive(GPSocketProtocol.MODE_PLAYBACK, GPSocketProtocol.CMD_GET_FILE_COUNT, req)
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during getFileCount wait (expected $expectedEpoch, current $targetEpoch)"))
                }
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
     * Fetches real media items list from hardware via CMD_GET_NAME_LIST (Spec 25.8, VR-08)
     */
    suspend fun getFileList(expectedEpoch: Long? = null): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                return@withContext Result.failure(IllegalStateException("Target epoch expired before getFileList (expected $expectedEpoch, current $targetEpoch)"))
            }
            try {
                ensureConnected()
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during connect (expected $expectedEpoch, current $targetEpoch)"))
                }
                val req = GPSocketProtocol.buildGetNameListPacket()
                val resp = sendAndReceive(GPSocketProtocol.MODE_PLAYBACK, GPSocketProtocol.CMD_GET_NAME_LIST, req)
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during getFileList wait (expected $expectedEpoch, current $targetEpoch)"))
                }
                when (resp) {
                    is GPSocketResponse.Ack -> {
                        val items = GPSocketProtocol.parseFileList(resp.payload)
                        Log.i(TAG, "Hardware returned real file list: ${items.size} items")
                        Result.success(items)
                    }
                    is GPSocketResponse.Nak -> {
                        val msg = "GetNameList NAK: 0x${resp.errorCode.toString(16).uppercase()}"
                        Log.w(TAG, msg)
                        Result.failure(Exception(msg))
                    }
                    is GPSocketResponse.Error -> {
                        Result.failure(Exception(resp.message))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get file list", e)
                disconnectInternal()
                Result.failure(e)
            }
        }
    }

    /**
     * Aborts any in-flight raw data transfer or blocked socket read immediately (C1).
     * Closes the active socket to interrupt blocking I/O, forcing commandLock release
     * and socket reconnect on next command.
     */
    fun abortActiveTransfer() {
        try {
            socket?.close()
        } catch (_: Exception) {}
    }

    /**
     * Downloads raw file from dashcam via CMD_GET_RAW_DATA with multi-ACK streaming (Spec 25.8, M4)
     */
    suspend fun downloadRawData(
        deviceIndex: Int,
        destinationFile: java.io.File,
        expectedEpoch: Long? = null,
        onProgress: (Long) -> Unit = {}
    ): Result<Long> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                return@withContext Result.failure(IllegalStateException("Target epoch expired before download (expected $expectedEpoch, current $targetEpoch)"))
            }
            val tempFile = java.io.File(destinationFile.parentFile, "${destinationFile.name}.tmp")
            try {
                ensureConnected()
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during connect (expected $expectedEpoch, current $targetEpoch)"))
                }
                val out = outputStream ?: throw java.io.IOException("Socket output stream is null")
                val inStream = inputStream ?: throw java.io.IOException("Socket input stream is null")

                val req = GPSocketProtocol.buildGetRawDataPacket(deviceIndex)
                out.write(req)
                out.flush()

                var totalBytes = 0L
                if (tempFile.exists()) tempFile.delete()

                try {
                    java.io.FileOutputStream(tempFile).use { fileOut ->
                        while (true) {
                            if (!coroutineContext.isActive) {
                                throw kotlinx.coroutines.CancellationException("Download cancelled by caller")
                            }

                            val header = ByteArray(12)
                            readFully(inStream, header)

                            if (!coroutineContext.isActive) {
                                throw kotlinx.coroutines.CancellationException("Download cancelled by caller")
                            }

                            val lenBytes = ByteArray(2)
                            readFully(inStream, lenBytes)
                            val lenOrError = ((lenBytes[1].toInt() and 0xFF) shl 8) or (lenBytes[0].toInt() and 0xFF)

                            val type = ((header[9].toInt() and 0xFF) shl 8) or (header[8].toInt() and 0xFF)
                            if (type == GPSocketProtocol.TYPE_NAK.toInt()) {
                                throw java.io.IOException("Download returned NAK error 0x${lenOrError.toString(16).uppercase()}")
                            }

                            if (lenOrError == 0) {
                                // Empty payload ACK marks the end of transmission (Spec 25.8)
                                Log.i(TAG, "Download completed with empty ACK. Total bytes: $totalBytes")
                                break
                            }

                            val chunk = ByteArray(lenOrError)
                            readFully(inStream, chunk)

                            if (!coroutineContext.isActive) {
                                throw kotlinx.coroutines.CancellationException("Download cancelled by caller")
                            }

                            fileOut.write(chunk)
                            totalBytes += lenOrError
                            onProgress(totalBytes)
                        }
                        fileOut.flush()
                    }

                    if (!coroutineContext.isActive) {
                        throw kotlinx.coroutines.CancellationException("Download cancelled before file rename")
                    }

                    if (tempFile.renameTo(destinationFile)) {
                        Log.i(TAG, "Successfully downloaded file to ${destinationFile.absolutePath} ($totalBytes bytes)")
                        Result.success(totalBytes)
                    } else {
                        Result.failure(java.io.IOException("Failed to rename temp file to ${destinationFile.absolutePath}"))
                    }
                } finally {
                    if (tempFile.exists() && !destinationFile.exists()) {
                        tempFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed or aborted for deviceIndex $deviceIndex", e)
                if (tempFile.exists()) tempFile.delete()
                // Spec 25.8: On abortion/failure, close socket to prevent residual bytes from corrupting subsequent commands
                disconnectInternal()
                if (e is kotlinx.coroutines.CancellationException) {
                    throw e
                }
                Result.failure(e)
            }
        }
    }

    /**
     * Reads parameter from hardware with epoch validation (C06)
     */
    suspend fun getParameter(paramId: Int, expectedEpoch: Long? = null): Result<ByteArray> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                return@withContext Result.failure(IllegalStateException("Target epoch expired before getParameter (expected $expectedEpoch, current $targetEpoch)"))
            }
            try {
                ensureConnected()
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during connect (expected $expectedEpoch, current $targetEpoch)"))
                }
                val req = GPSocketProtocol.buildGetParameterPacket(paramId)
                val resp = sendAndReceive(GPSocketProtocol.MODE_PARAM, GPSocketProtocol.CMD_GET_PARAM, req)
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during getParameter wait (expected $expectedEpoch, current $targetEpoch)"))
                }
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
     * Sets parameter on hardware with atomic epoch validation before sending and upon response (R5-01)
     */
    suspend fun setParameter(paramId: Int, value: ByteArray, expectedEpoch: Long? = null): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                return@withContext Result.failure(IllegalStateException("Target epoch expired before setParameter (expected $expectedEpoch, current $targetEpoch)"))
            }
            try {
                ensureConnected()
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during connect (expected $expectedEpoch, current $targetEpoch)"))
                }
                val req = GPSocketProtocol.buildSetParameterPacket(paramId, value)
                val resp = sendAndReceive(GPSocketProtocol.MODE_PARAM, GPSocketProtocol.CMD_SET_PARAM, req)
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during setParameter response wait (expected $expectedEpoch, current $targetEpoch)"))
                }
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
