package com.aladdin.dashcam.media

import android.net.Network
import com.aladdin.dashcam.model.MediaItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the recorder command/decoder ordering for one remote playback session. */
class RemotePlaybackSession(
    private val acquire: () -> Boolean,
    private val release: () -> Unit,
    private val network: () -> Network?,
    private val duration: suspend (MediaItem) -> Long,
    private val restart: suspend () -> Result<Unit>,
    private val start: suspend (MediaItem, () -> Unit) -> Result<Unit>,
    private val stop: suspend () -> Result<Unit>,
    private val source: () -> String
) {
    enum class State { IDLE, LOADING, PLAYING, PAUSED, ENDED, INTERRUPTED, DISCONNECTED, CLOSING }
    private val mutex = Mutex()
    private var player: NativeFfmpegPlayer? = null
    private val _state = MutableStateFlow(State.IDLE)
    val state = _state.asStateFlow()

    suspend fun open(item: MediaItem, decoder: NativeFfmpegPlayer, preferHardware: Boolean = false) = mutex.withLock {
        closeInternal().getOrThrow()
        val wifi = network() ?: error("Recorder Wi-Fi disconnected")
        val length = duration(item)
        check(acquire()) { "Recorder is busy transferring media" }
        player = decoder
        _state.value = State.LOADING
        try {
            restart().getOrThrow()
            coroutineScope {
                val sent = CompletableDeferred<Unit>()
                val command = async(Dispatchers.IO) {
                    val result = start(item) { sent.complete(Unit) }
                    result.exceptionOrNull()?.let { sent.completeExceptionally(it) }
                    result.getOrThrow()
                }
                withTimeout(20_000) { sent.await() }
                decoder.start(source(), streaming = true, targetNetwork = wifi, knownDurationMs = length, preferHardware = preferHardware)
                command.await()
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) { closeInternal() }
            _state.value = if (network() == null) State.DISCONNECTED else State.INTERRUPTED
            throw error
        }
    }

    fun rendered() { if (_state.value == State.LOADING) _state.value = State.PLAYING }
    fun paused(value: Boolean) { _state.value = if (value) State.PAUSED else State.PLAYING }
    fun ended() { _state.value = State.ENDED }
    fun interrupted() { _state.value = if (network() == null) State.DISCONNECTED else State.INTERRUPTED }
    suspend fun close(): Result<Unit> = withContext(NonCancellable) { mutex.withLock { closeInternal() } }
    private suspend fun closeInternal(): Result<Unit> {
        val decoder = player ?: return Result.success(Unit)
        _state.value = State.CLOSING
        return try {
            val result = stop()
            delay(300) // OEM GoPlus stop command grace period before naStop.
            result
        } finally {
            decoder.stop()
            player = null
            release()
            _state.value = State.IDLE
        }
    }
}
