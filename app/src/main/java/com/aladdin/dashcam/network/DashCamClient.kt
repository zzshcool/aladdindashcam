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
    val sessionId: Long = 0L,
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
    private val statusSampleCounter = java.util.concurrent.atomic.AtomicLong(0L)

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
    private var unsupportedThumbnailSessionId: Long = 0L
    private val commandLock = Any()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _deviceStatus = MutableStateFlow(DashCamDeviceStatus())
    val deviceStatus: StateFlow<DashCamDeviceStatus> = _deviceStatus.asStateFlow()

    /**
     * Updates bound network and invalidates obsolete session if network changed (C04 / N03)
     */
    fun bindNetwork(network: Network?) {
        synchronized(commandLock) {
            if (this.boundNetwork != network) {
                if (network != null) isPoweredOffByUser.set(false)
                val newEpoch = epochCounter.incrementAndGet()
                targetEpoch = newEpoch
                Log.i(TAG, "Network changed from ${this.boundNetwork} to $network, epoch: $newEpoch, invalidating existing session")
                this.boundNetwork = network
                disconnectInternal()
                _targetIdentity.value = TargetIdentity(
                    requestedHost = currentHost,
                    activeHost = null,
                    epoch = newEpoch,
                    state = TargetState.DISCONNECTED
                )
            }
        }
    }

    /**
     * Safely probes candidate gateway to verify whether it is an actual GeneralPlus dashcam (N02)
     */
    suspend fun probeDevice(candidateHost: String, network: Network): Result<Boolean> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            if (connectedHost == candidateHost && connectedNetwork == network && socket?.isConnected == true && socket?.isClosed == false) {
                return@synchronized runCatching { queryStatusInternal() != null }
            }
            disconnectInternal()
            var testSocket: Socket? = null
            try {
                testSocket = Socket()
                network.bindSocket(testSocket)
                testSocket.connect(InetSocketAddress(candidateHost, port), 2000)
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
                    Result.success(true)
                } else {
                    Result.failure(IllegalStateException("Connected endpoint is not a GeneralPlus dashcam"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                try {
                    testSocket?.close()
                } catch (_: Exception) {}
            }
        }
    }

    private val isPollingPaused = AtomicBoolean(false)
    private val isPoweredOffByUser = AtomicBoolean(false)

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
                if (isPollingPaused.get() || isPoweredOffByUser.get()) {
                    delay(1000)
                    continue
                }
                try {
                    val (status, sessionId) = synchronized(commandLock) {
                        try {
                            ensureConnected()
                            queryStatusWithSessionInternal()
                        } catch (error: Exception) {
                            disconnectInternal()
                            throw error
                        }
                    }
                    synchronized(commandLock) {
                        // R4-02: Validate session generation before committing state to prevent stale responses from overriding new session
                        if (sessionId != 0L && sessionId == currentSessionId) {
                            if (status != null && (status.mode == DeviceMode.PLAYBACK ||
                                        status.recordingStatus != RecordingStatus.UNKNOWN)) {
                                _deviceStatus.value = status.copy(sampleSequence = statusSampleCounter.incrementAndGet())
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
            check(!isPoweredOffByUser.get()) { "記錄器已接收關機請求；請待 Wi-Fi 重新連線" }
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
                sessionId = newSession,
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
                    sessionId = 0L,
                    state = TargetState.DISCONNECTED
                )
                markStatusExpired()
            }
        }
    }

    /**
     * Unified send and receive with strict pair and ACK/NAK checking (C01)
     */
    private fun sendAndReceive(expectedMode: Byte, expectedCmd: Byte, packet: ByteArray, onRequestSent: (() -> Unit)? = null): GPSocketResponse {
        val out = outputStream ?: throw java.io.IOException("Socket output stream is null")
        return try {
            out.write(packet)
            out.flush()
            onRequestSent?.invoke()
            val response = readResponse(expectedMode, expectedCmd)
            com.aladdin.dashcam.media.Diagnostics.record("control.${expectedMode.toInt() and 255}.${expectedCmd.toInt() and 255}", response.javaClass.simpleName)
            if (response is GPSocketResponse.Error) disconnectInternal()
            response
        } catch (error: java.io.IOException) {
            com.aladdin.dashcam.media.Diagnostics.record("control.failure", error.javaClass.simpleName)
            disconnectInternal()
            throw error
        }
    }

    private fun readResponse(expectedMode: Byte, expectedCmd: Byte): GPSocketResponse {
        val inStream = inputStream ?: throw java.io.IOException("Socket input stream is null")
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
                val hexStr = resp.payload.joinToString("") { "%02X".format(it) }
                Log.i("SD_RAW_PACKET", "GET_STATUS payload (len=${resp.payload.size}): $hexStr")
                GPSocketProtocol.parseDeviceStatus(resp.payload)
            } else {
                Log.w("SD_RAW_PACKET", "GET_STATUS response is not Ack: $resp")
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
                if (status != null && (status.mode == DeviceMode.PLAYBACK ||
                            status.recordingStatus != RecordingStatus.UNKNOWN)) {
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
    suspend fun takePhoto(expectedEpoch: Long = targetEpoch): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            if (expectedEpoch != targetEpoch) {
                return@withContext Result.failure(IllegalStateException("Device changed before capture"))
            }
            try {
                ensureConnected()
                check(expectedEpoch == targetEpoch) { "Device changed while opening capture session" }
                val before = queryStatusInternal() ?: error("Cannot verify recorder state before capture")
                check(before.mode == DeviceMode.RECORD || before.mode == DeviceMode.CAPTURE) { "Recorder is not in a capture-compatible mode" }
                val req = GPSocketProtocol.buildCapturePacket()
                var resp = sendAndReceive(GPSocketProtocol.MODE_CAPTURE, GPSocketProtocol.CMD_CAPTURE_PHOTO, req)
                if (resp is GPSocketResponse.Nak && resp.errorCode == GPSocketProtocol.ERR_MODE_ERROR && before.mode == DeviceMode.RECORD) {
                    check(before.recordingStatus != RecordingStatus.UNKNOWN) { "Cannot switch to capture with unknown recording state" }
                    val wasRecording = before.recordingStatus == RecordingStatus.RECORDING
                    Log.i(TAG, "Capture requires photo work mode; recording=$wasRecording")
                    try {
                        if (wasRecording) setRecordingInternal(false)
                        setWorkModeInternal(DeviceMode.CAPTURE)
                        resp = sendAndReceive(GPSocketProtocol.MODE_CAPTURE, GPSocketProtocol.CMD_CAPTURE_PHOTO, req)
                        if (resp is GPSocketResponse.Ack) Thread.sleep(500)
                    } finally {
                        try {
                            check(expectedEpoch == targetEpoch) { "Device changed during capture" }
                            val current = queryStatusInternal() ?: error("Cannot read recorder state after capture")
                            if (current.mode != before.mode) setWorkModeInternal(before.mode)
                            if (wasRecording) resumeRecordingInternal() else setRecordingInternal(false)
                            Log.i(TAG, "Capture state restored: mode=${_deviceStatus.value.mode}, recording=${_deviceStatus.value.recordingStatus}")
                        } catch (restoreError: Exception) {
                            throw IllegalStateException("Photo may have been saved, but recording restoration is unconfirmed. Check the recorder.", restoreError)
                        }
                    }
                }

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

    suspend fun powerOff(expectedEpoch: Long): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            if (expectedEpoch != targetEpoch) {
                return@withContext Result.failure(IllegalStateException("設備已切換，請重新確認"))
            }
            var attempted = false
            try {
                ensureConnected()
                check(expectedEpoch == targetEpoch) { "設備已切換，請重新確認" }
                isPoweredOffByUser.set(true)
                attempted = true
                val response = sendAndReceive(GPSocketProtocol.MODE_DEVICE, GPSocketProtocol.CMD_POWER_OFF,
                    GPSocketProtocol.buildPowerOffPacket())
                if (response is GPSocketResponse.Ack) {
                    disconnectInternal()
                    Result.success(Unit)
                } else {
                    disconnectInternal()
                    Result.failure(IllegalStateException("記錄器未確認關機：$response"))
                }
            } catch (e: Exception) {
                if (attempted) disconnectInternal()
                Result.failure(e)
            }
        }
    }

    /**
     * Synchronizes phone's local time to dashcam RTC via GPVENDOR packet (Chapter 25.9, C01)
     */
    suspend fun syncRtc(expectedEpoch: Long? = null): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            try {
                if (expectedEpoch != null && targetEpoch != expectedEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch changed from $expectedEpoch to $targetEpoch"))
                }
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
                val countReply = sendAndReceive(GPSocketProtocol.MODE_PLAYBACK, GPSocketProtocol.CMD_GET_FILE_COUNT, GPSocketProtocol.buildGetFileCountPacket())
                if (countReply !is GPSocketResponse.Ack || countReply.payload.size != 2) {
                    return@withContext Result.failure(java.io.IOException("Invalid file-count response"))
                }
                val expectedCount = (countReply.payload[0].toInt() and 0xFF) or ((countReply.payload[1].toInt() and 0xFF) shl 8)
                val items = linkedMapOf<Int, MediaItem>()
                var cursor = 0
                while (items.size < expectedCount) {
                    if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("File-list query cancelled")
                    if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                        return@withContext Result.failure(IllegalStateException("Target changed during file-list query"))
                    }
                    val req = GPSocketProtocol.buildGetNameListPacket(flag = if (items.isEmpty()) 1 else 0, cursor = cursor.toShort())
                    when (val resp = sendAndReceive(GPSocketProtocol.MODE_PLAYBACK, GPSocketProtocol.CMD_GET_NAME_LIST, req)) {
                        is GPSocketResponse.Ack -> {
                            val page = GPSocketProtocol.parseFileList(resp.payload)
                            if (page.isEmpty()) throw java.io.IOException("File list ended before reported count (${items.size}/$expectedCount)")
                            val previousSize = items.size
                            page.forEach { items[it.deviceIndex] = it }
                            if (items.size == previousSize) throw java.io.IOException("File-list cursor did not advance")
                            cursor = page.last().deviceIndex
                        }
                        is GPSocketResponse.Nak -> return@withContext Result.failure(java.io.IOException("GetNameList NAK: ${resp.errorCode}"))
                        is GPSocketResponse.Error -> return@withContext Result.failure(java.io.IOException(resp.message))
                    }
                }
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target changed after file-list query"))
                }
                Log.i(TAG, "Hardware file list: ${items.size}/$expectedCount entries")
                Result.success(items.values.toList())
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
    /**
     * Starts RTSP streaming playback for recorded file (deviceIndex) via CMD_PLAYBACK_START (Spec 25.8)
     */
    private var playbackRestoreState: Triple<Long, DeviceMode, Boolean>? = null
    private var albumRestoreState: Triple<Long, DeviceMode, Boolean>? = null
    private var activePlaybackIndex: Int? = null

    private fun setWorkModeInternal(mode: DeviceMode) {
        check(sendAndReceive(GPSocketProtocol.MODE_DEVICE, GPSocketProtocol.CMD_SET_MODE,
            GPSocketProtocol.buildSetModePacket(mode)) is GPSocketResponse.Ack) { "Device rejected mode $mode" }
        Thread.sleep(300)
        val status = queryStatusInternal() ?: error("Cannot verify device mode")
        check(status.mode == mode) { "Device mode mismatch: expected $mode, actual ${status.mode}" }
        _deviceStatus.value = status
    }

    private fun setRecordingInternal(recording: Boolean) {
        val status = queryStatusInternal() ?: error("無法確認錄影狀態")
        check(status.recordingStatus != RecordingStatus.UNKNOWN) { "錄影狀態未知" }
        if ((status.recordingStatus == RecordingStatus.RECORDING) != recording) {
            check(sendAndReceive(GPSocketProtocol.MODE_RECORD, GPSocketProtocol.CMD_RECORD_TOGGLE,
                GPSocketProtocol.buildRecordTogglePacket()) is GPSocketResponse.Ack) { "切換錄影失敗" }
            Thread.sleep(800)
        }
        val actual = queryStatusInternal() ?: error("無法回讀錄影狀態")
        _deviceStatus.value = actual
        check((actual.recordingStatus == RecordingStatus.RECORDING) == recording) { "錄影狀態未符合要求" }
    }

    private fun resumeRecordingInternal() {
        val current = queryStatusInternal() ?: error("Cannot verify recording before restore")
        check(current.mode == DeviceMode.RECORD) { "Cannot resume recording outside record mode" }
        if (current.recordingStatus == RecordingStatus.STOPPED) {
            check(sendAndReceive(GPSocketProtocol.MODE_RECORD, GPSocketProtocol.CMD_RECORD_TOGGLE,
                GPSocketProtocol.buildRecordTogglePacket()) is GPSocketResponse.Ack) { "Cannot resume recording" }
            Thread.sleep(800)
        }
        val restoreDeadline = System.nanoTime() + 35_000_000_000L
        var restored = queryStatusInternal() ?: error("Cannot verify recording restoration")
        while (restored.recordingStatus == RecordingStatus.STOPPED && System.nanoTime() < restoreDeadline) {
            Thread.sleep(2_000)
            restored = queryStatusInternal() ?: error("Cannot verify recording restoration")
        }
        _deviceStatus.value = restored
        check(restored.recordingStatus == RecordingStatus.RECORDING) { "Recording did not resume" }
    }

    suspend fun enterAlbumBrowsing(expectedEpoch: Long): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            try {
                check(expectedEpoch == targetEpoch) { "Device changed before opening album" }
                ensureConnected()
                check(expectedEpoch == targetEpoch) { "Device changed while opening album" }
                val current = queryStatusInternal() ?: error("Cannot read device mode before opening album")
                check(current.mode != DeviceMode.UNKNOWN) { "Cannot identify device mode before opening album" }
                val active = albumRestoreState?.takeIf { it.first == targetEpoch }
                if (active != null) {
                    check(current.mode == DeviceMode.PLAYBACK) { "Album mode transition incomplete" }
                    return@withContext Result.success(Unit)
                }
                albumRestoreState = Triple(targetEpoch, current.mode, current.recordingStatus == RecordingStatus.RECORDING)
                if (current.mode == DeviceMode.RECORD && current.recordingStatus == RecordingStatus.RECORDING) {
                    setRecordingInternal(false)
                }
                if (current.mode != DeviceMode.PLAYBACK) setWorkModeInternal(DeviceMode.PLAYBACK)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enter album browsing", e)
                val restore = albumRestoreState?.takeIf { it.first == expectedEpoch && it.first == targetEpoch }
                if (restore != null) {
                    try {
                        val current = queryStatusInternal() ?: error("Cannot read device mode after album failure")
                        if (current.mode != restore.second) setWorkModeInternal(restore.second)
                        if (restore.second == DeviceMode.RECORD && restore.third) resumeRecordingInternal()
                        albumRestoreState = null
                    } catch (restoreError: Exception) {
                        e.addSuppressed(restoreError)
                    }
                }
                Result.failure(e)
            }
        }
    }

    suspend fun exitAlbumBrowsing(expectedEpoch: Long): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            val restore = albumRestoreState?.takeIf { it.first == expectedEpoch && it.first == targetEpoch }
                ?: return@withContext Result.success(Unit)
            try {
                ensureConnected()
                check(expectedEpoch == targetEpoch) { "Device changed while closing album" }
                val current = queryStatusInternal() ?: error("Cannot read device mode when closing album")
                if (activePlaybackIndex != null && current.mode == DeviceMode.PLAYBACK) {
                    check(sendAndReceive(GPSocketProtocol.MODE_PLAYBACK, GPSocketProtocol.CMD_PLAYBACK_STOP,
                        GPSocketProtocol.buildPlaybackStopPacket()) is GPSocketResponse.Ack) { "Cannot stop album playback" }
                    activePlaybackIndex = null
                    playbackRestoreState = null
                }
                if (restore.second != DeviceMode.UNKNOWN && current.mode != restore.second) {
                    setWorkModeInternal(restore.second)
                }
                if (restore.second == DeviceMode.RECORD && restore.third) resumeRecordingInternal()
                Log.i(TAG, "Album exit verified: mode=${_deviceStatus.value.mode}, recording=${_deviceStatus.value.recordingStatus}")
                albumRestoreState = null
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore recording after album browsing", e)
                Result.failure(e)
            }
        }
    }

    /** 僅由使用者確認後呼叫；逾時不重送格式化動作。 */
    suspend fun formatStorage(expectedEpoch: Long): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            try {
                check(expectedEpoch == targetEpoch) { "設備已切換" }
                ensureConnected()
                check(expectedEpoch == targetEpoch) { "設備已切換" }
                val before = queryStatusInternal() ?: error("無法確認記錄器狀態")
                check(before.sdCardReady) { "記憶卡尚未就緒" }
                check(before.mode == DeviceMode.RECORD || before.mode == DeviceMode.MENU) { "請先離開影片回放" }
                val wasRecording = before.recordingStatus == RecordingStatus.RECORDING
                if (wasRecording) {
                    setRecordingInternal(false)
                }
                setWorkModeInternal(DeviceMode.MENU)
                try {
                    val activeSocket = socket ?: error("連線已中斷")
                    val timeout = activeSocket.soTimeout
                    val response = try {
                        activeSocket.soTimeout = 20_000
                        sendAndReceive(GPSocketProtocol.MODE_PARAM, GPSocketProtocol.CMD_SET_PARAM,
                            GPSocketProtocol.buildFormatStoragePacket())
                    } finally {
                        if (!activeSocket.isClosed) activeSocket.soTimeout = timeout
                    }
                    check(response is GPSocketResponse.Ack) { "記錄器未接受格式化：$response" }
                    Thread.sleep(2000)
                    val count = sendAndReceive(GPSocketProtocol.MODE_PLAYBACK, GPSocketProtocol.CMD_GET_FILE_COUNT,
                        GPSocketProtocol.buildGetFileCountPacket())
                    check(count is GPSocketResponse.Ack && count.payload.contentEquals(byteArrayOf(0, 0))) {
                        "格式化已送出，但尚未確認記憶卡清空；請重新讀取檢查，勿重複操作"
                    }
                    check(queryStatusInternal()?.sdCardReady == true) { "格式化後記憶卡尚未就緒" }
                    Result.success(Unit)
                } finally {
                    if (expectedEpoch == targetEpoch) {
                        setWorkModeInternal(before.mode)
                        if (wasRecording) setRecordingInternal(true)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun startPlayback(deviceIndex: Int, expectedEpoch: Long? = null, onRequestSent: (() -> Unit)? = null): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                return@withContext Result.failure(IllegalStateException("Target epoch expired before startPlayback (expected $expectedEpoch, current $targetEpoch)"))
            }
            try {
                ensureConnected()
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during connect (expected $expectedEpoch, current $targetEpoch)"))
                }
                val status = queryStatusInternal() ?: error("Cannot read mode before playback")
                if (playbackRestoreState?.first != targetEpoch) {
                    playbackRestoreState = Triple(targetEpoch, status.mode, status.recordingStatus == RecordingStatus.RECORDING)
                    activePlaybackIndex = null
                }
                if (activePlaybackIndex != null && status.mode == DeviceMode.PLAYBACK) {
                    check(sendAndReceive(GPSocketProtocol.MODE_PLAYBACK, GPSocketProtocol.CMD_PLAYBACK_STOP,
                        GPSocketProtocol.buildPlaybackStopPacket()) is GPSocketResponse.Ack) { "Cannot stop previous recording playback" }
                    activePlaybackIndex = null
                }
                if (status.mode == DeviceMode.RECORD && status.recordingStatus == RecordingStatus.RECORDING) {
                    check(sendAndReceive(GPSocketProtocol.MODE_RECORD, GPSocketProtocol.CMD_RECORD_TOGGLE,
                        GPSocketProtocol.buildRecordTogglePacket()) is GPSocketResponse.Ack) { "Cannot pause recording for playback" }
                    Thread.sleep(800)
                    check(queryStatusInternal()?.recordingStatus == RecordingStatus.STOPPED) { "Recording did not stop for playback" }
                }
                if (status.mode != DeviceMode.PLAYBACK) setWorkModeInternal(DeviceMode.PLAYBACK)
                val req = GPSocketProtocol.buildPlaybackStartPacket(deviceIndex)
                val playbackSocket = socket ?: error("Playback socket unavailable")
                val previousTimeout = playbackSocket.soTimeout
                if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("Playback cancelled")
                val resp = try {
                    playbackSocket.soTimeout = 15_000
                    sendAndReceive(GPSocketProtocol.MODE_PLAYBACK, GPSocketProtocol.CMD_PLAYBACK_START, req, onRequestSent)
                } finally {
                    if (!playbackSocket.isClosed) playbackSocket.soTimeout = previousTimeout
                }
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during startPlayback response wait (expected $expectedEpoch, current $targetEpoch)"))
                }
                when (resp) {
                    is GPSocketResponse.Ack -> {
                        Log.i(TAG, "StartPlayback accepted by hardware for index $deviceIndex")
                        activePlaybackIndex = deviceIndex
                        Result.success(Unit)
                    }
                    is GPSocketResponse.Nak -> {
                        Result.failure(Exception("StartPlayback NAK: 0x${resp.errorCode.toString(16).uppercase()}"))
                    }
                    is GPSocketResponse.Error -> {
                        Result.failure(Exception(resp.message))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start playback for index $deviceIndex", e)
                Result.failure(e)
            }
        }
    }

    suspend fun togglePlaybackPause(deviceIndex: Int, expectedEpoch: Long): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            try {
                check(expectedEpoch == targetEpoch && activePlaybackIndex == deviceIndex) { "播放來源已切換，請重新開啟影片" }
                ensureConnected()
                check(queryStatusInternal()?.mode == DeviceMode.PLAYBACK) { "設備已離開回放模式" }
                val response = sendAndReceive(GPSocketProtocol.MODE_PLAYBACK, GPSocketProtocol.CMD_PLAYBACK_PAUSE,
                    GPSocketProtocol.buildPlaybackPausePacket())
                check(response is GPSocketResponse.Ack) { "暫停狀態未確認：$response" }
                Result.success(Unit)
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }

    /**
     * Stops RTSP playback and restores live stream readiness via CMD_PLAYBACK_STOP
     */
    suspend fun stopPlayback(expectedEpoch: Long? = null): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            val restore = playbackRestoreState?.takeIf { it.first == targetEpoch }
                ?: return@withContext Result.success(Unit)
            if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                return@withContext Result.failure(IllegalStateException("Target epoch expired before stopPlayback (expected $expectedEpoch, current $targetEpoch)"))
            }
            try {
                ensureConnected()
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during connect (expected $expectedEpoch, current $targetEpoch)"))
                }
                val req = GPSocketProtocol.buildPlaybackStopPacket()
                val resp = sendAndReceive(GPSocketProtocol.MODE_PLAYBACK, GPSocketProtocol.CMD_PLAYBACK_STOP, req)
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during stopPlayback response wait (expected $expectedEpoch, current $targetEpoch)"))
                }
                when (resp) {
                    is GPSocketResponse.Ack -> {
                        Log.i(TAG, "StopPlayback accepted by hardware")
                        if (restore.second != DeviceMode.UNKNOWN && restore.second != DeviceMode.PLAYBACK) {
                            setWorkModeInternal(restore.second)
                            if (restore.third && restore.second == DeviceMode.RECORD) {
                                resumeRecordingInternal()
                            }
                        }
                        playbackRestoreState = null
                        activePlaybackIndex = null
                        Result.success(Unit)
                    }
                    is GPSocketResponse.Nak -> {
                        Result.failure(Exception("StopPlayback NAK: 0x${resp.errorCode.toString(16).uppercase()}"))
                    }
                    is GPSocketResponse.Error -> {
                        Result.failure(Exception(resp.message))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop playback", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Fetches file thumbnail from hardware via CMD_GET_THUMBNAIL (0x04)
     */
    suspend fun getThumbnail(deviceIndex: Int, expectedEpoch: Long? = null): Result<ByteArray> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                return@withContext Result.failure(IllegalStateException("Target epoch expired before getThumbnail (expected $expectedEpoch, current $targetEpoch)"))
            }
            try {
                ensureConnected()
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during connect (expected $expectedEpoch, current $targetEpoch)"))
                }
                if (unsupportedThumbnailSessionId == currentSessionId) {
                    return@withContext Result.failure(IllegalStateException("Thumbnail command is unsupported in this session"))
                }
                val thumbnailSocket = socket ?: error("Thumbnail socket unavailable")
                val previousTimeout = thumbnailSocket.soTimeout
                val req = GPSocketProtocol.buildGetThumbnailPacket(deviceIndex)
                try {
                    thumbnailSocket.soTimeout = 10_000
                    val image = java.io.ByteArrayOutputStream()
                    val deadline = System.nanoTime() + THUMBNAIL_TIMEOUT_NS
                    var response = sendAndReceive(GPSocketProtocol.MODE_PLAYBACK, GPSocketProtocol.CMD_GET_THUMBNAIL, req)
                    var complete = false
                    while (!complete) {
                        if (expectedEpoch != null && expectedEpoch != targetEpoch) error("Device changed during thumbnail transfer")
                        when (response) {
                            is GPSocketResponse.Ack -> {
                                if (response.payload.isEmpty()) {
                                    check(image.size() > 0) { "Thumbnail transfer ended without image data" }
                                    complete = true
                                } else {
                                    check(image.size() + response.payload.size <= MAX_THUMBNAIL_BYTES) { "Thumbnail exceeds 2 MiB" }
                                    image.write(response.payload)
                                }
                            }
                            is GPSocketResponse.Nak -> {
                                val message = "GetThumbnail NAK: 0x${response.errorCode.toString(16).uppercase()}"
                                if (image.size() > 0) error(message)
                                if (response.errorCode == GPSocketProtocol.ERR_INVALID_CMD) {
                                    unsupportedThumbnailSessionId = currentSessionId
                                }
                                Log.w(TAG, message)
                                return@withContext Result.failure(IllegalStateException(message))
                            }
                            is GPSocketResponse.Error -> error(response.message)
                        }
                        if (!complete) {
                            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                            check(remainingMs > 0) { "Thumbnail transfer timed out" }
                            thumbnailSocket.soTimeout = minOf(10_000L, remainingMs).toInt().coerceAtLeast(1)
                            response = readResponse(GPSocketProtocol.MODE_PLAYBACK, GPSocketProtocol.CMD_GET_THUMBNAIL)
                        }
                    }
                    Result.success(image.toByteArray())
                } finally {
                    if (!thumbnailSocket.isClosed) thumbnailSocket.soTimeout = previousTimeout
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get thumbnail for index $deviceIndex", e)
                disconnectInternal()
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        }
    }

    /**
     * Deletes media file from hardware SD card via CMD_DELETE_FILE (0x08)
     */
    suspend fun deleteFile(deviceIndex: Int, expectedEpoch: Long? = null): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                return@withContext Result.failure(IllegalStateException("Target epoch expired before deleteFile (expected $expectedEpoch, current $targetEpoch)"))
            }
            try {
                ensureConnected()
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during connect (expected $expectedEpoch, current $targetEpoch)"))
                }
                val req = GPSocketProtocol.buildDeleteFilePacket(deviceIndex)
                val resp = sendAndReceive(GPSocketProtocol.MODE_PLAYBACK, GPSocketProtocol.CMD_DELETE_FILE, req)
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during deleteFile response wait (expected $expectedEpoch, current $targetEpoch)"))
                }
                when (resp) {
                    is GPSocketResponse.Ack -> {
                        Log.i(TAG, "Delete file accepted by hardware for index $deviceIndex")
                        Result.success(Unit)
                    }
                    is GPSocketResponse.Nak -> {
                        Result.failure(Exception("DeleteFile NAK: 0x${resp.errorCode.toString(16).uppercase()}"))
                    }
                    is GPSocketResponse.Error -> {
                        Result.failure(Exception(resp.message))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete file for index $deviceIndex", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Toggles recording audio microphone via CMD_RECORD_AUDIO (0x01)
     */
    suspend fun toggleAudio(expectedEpoch: Long? = null): Result<Unit> = withContext(Dispatchers.IO) {
        synchronized(commandLock) {
            if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                return@withContext Result.failure(IllegalStateException("Target epoch expired before toggleAudio (expected $expectedEpoch, current $targetEpoch)"))
            }
            try {
                ensureConnected()
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during connect (expected $expectedEpoch, current $targetEpoch)"))
                }
                val req = GPSocketProtocol.buildRecordAudioTogglePacket()
                val resp = sendAndReceive(GPSocketProtocol.MODE_RECORD, GPSocketProtocol.CMD_RECORD_AUDIO, req)
                if (expectedEpoch != null && expectedEpoch != targetEpoch) {
                    return@withContext Result.failure(IllegalStateException("Target epoch expired during toggleAudio response wait (expected $expectedEpoch, current $targetEpoch)"))
                }
                when (resp) {
                    is GPSocketResponse.Ack -> {
                        Log.i(TAG, "Audio toggle accepted by hardware")
                        Result.success(Unit)
                    }
                    is GPSocketResponse.Nak -> {
                        Result.failure(Exception("ToggleAudio NAK: 0x${resp.errorCode.toString(16).uppercase()}"))
                    }
                    is GPSocketResponse.Error -> {
                        Result.failure(Exception(resp.message))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle audio", e)
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
        private const val MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024
        private const val THUMBNAIL_TIMEOUT_NS = 30_000_000_000L
    }
}
