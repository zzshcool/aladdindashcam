package com.aladdin.dashcam.media

import com.aladdin.dashcam.R
import android.net.ConnectivityManager
import android.net.Network
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import com.generalplus.ffmpegLib.ffmpegWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import android.graphics.BitmapFactory
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class NativeFfmpegPlayer(
    private val glSurfaceView: GLSurfaceView,
    private val onFirstFrameRendered: (() -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null,
    private val onProgress: ((Long, Long) -> Unit)? = null,
    private val onEnded: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "NativeFfmpegPlayer"
        private val nativeLock = Any()
        @Volatile private var owner: NativeFfmpegPlayer? = null
        val isSupported: Boolean get() = ffmpegWrapper.isSupported()
    }

    @Volatile private var generation = 0L
    @Volatile private var active = false
    @Volatile private var sourceReady = false
    @Volatile private var paused = false
    @Volatile private var initialFrameCount = 0L
    private var released = false
    private val firstFrameDrawn = AtomicBoolean(false)
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var rendererReady = false
    private var network: Network? = null
    private var previousNetwork: Network? = null
    private val connectivity = glSurfaceView.context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    val isPlaying: Boolean get() = active && !paused
    val hasSource: Boolean get() = active
    val needsRestart: Boolean get() = owner !== this || !active ||
        synchronized(nativeLock) { ffmpegWrapper.naStatus() == ffmpegWrapper.ePlayerStatus.E_PlayerStatus_Stoped.ordinal }

    private val viewHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (owner !== this@NativeFfmpegPlayer || !active || released) return
            if (msg.what == ffmpegWrapper.FFMPEG_STATUS_STOPPED && firstFrameDrawn.get() && !paused) {
                finishPlayback()
            }
        }
    }

    init {
        if (isSupported) {
            try {
                glSurfaceView.setEGLContextClientVersion(2)
                glSurfaceView.setRenderer(object : GLSurfaceView.Renderer {
                    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
                        if (owner === this@NativeFfmpegPlayer) ffmpegWrapper.getInstance().onSurfaceCreated(gl, config)
                    }

                    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
                        if (owner === this@NativeFfmpegPlayer) ffmpegWrapper.getInstance().onSurfaceChanged(gl, width, height)
                    }

                    override fun onDrawFrame(gl: GL10) {
                        val token = generation
                        if (owner !== this@NativeFfmpegPlayer || !active || !sourceReady) return
                        try {
                            ffmpegWrapper.naDrawFrame()
                            val frames = ffmpegWrapper.naGetFrameCnt()
                            if (frames > 0 && frames != initialFrameCount && firstFrameDrawn.compareAndSet(false, true)) {
                                viewHandler.post {
                                    if (isCurrent(token)) {
                                        Log.i(TAG, "First decoded frame drawn, generation=$token")
                                        onFirstFrameRendered?.invoke()
                                    }
                                }
                            }
                        } catch (error: Throwable) {
                            viewHandler.post { if (isCurrent(token)) fail(glSurfaceView.context.getString(R.string.player_render_failed, error.message)) }
                        }
                    }
                })
                glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                rendererReady = true
                glSurfaceView.onPause()
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to initialize renderer", error)
            }
        }
    }

    suspend fun start(source: String, streaming: Boolean = true, targetNetwork: Network? = null,
                      startPositionMs: Long = 0L, knownDurationMs: Long = 0L) = withContext(Dispatchers.Main.immediate) {
        if (released || !isSupported || !rendererReady) {
            onError?.invoke(glSurfaceView.context.getString(R.string.player_unavailable))
            return@withContext
        }
        owner?.stop()
        val token = ++generation
        owner = this@NativeFfmpegPlayer
        active = true
        sourceReady = false
        paused = false
        firstFrameDrawn.set(false)
        try {
            if (targetNetwork != null) {
                previousNetwork = connectivity.boundNetworkForProcess
                check(connectivity.bindProcessToNetwork(targetNetwork)) { glSurfaceView.context.getString(R.string.player_wifi_failed) }
                network = targetNetwork
            }
            ffmpegWrapper.getInstance().SetViewHandler(viewHandler)
            glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            glSurfaceView.onResume()
            val result = withContext(Dispatchers.IO) {
                synchronized(nativeLock) {
                    if (!isCurrent(token)) return@synchronized null
                    ffmpegWrapper.naSetStreaming(streaming)
                    ffmpegWrapper.naSetBufferingTime(if (streaming) 40L else 0L)
                    ffmpegWrapper.naSetScaleMode(ffmpegWrapper.DISPLAY_SCALE_FIT)
                    initialFrameCount = ffmpegWrapper.naGetFrameCnt()
                    ffmpegWrapper.naInitAndPlay(source, if (streaming) "timeout=-1;stimeout=-1;probesize=4096" else "")
                }
            }
            if (!isCurrent(token)) return@withContext
            check(result == ffmpegWrapper.eFFMPEG_ERRCODE.FFMPEGPLAYER_NOERROR.ordinal) { glSurfaceView.context.getString(R.string.player_open_failed_code, result) }
            sourceReady = true
            if (!streaming && startPositionMs > 0L) seekTo(startPositionMs)
            Log.i(TAG, "FFmpeg source opened: streaming=$streaming, generation=$token")
            monitorJob = scope.launch {
                var lastFrameCount = -1L
                var lastProgressAt = SystemClock.elapsedRealtime()
                var samples = 0
                var furthestPositionMs = 0L
                while (isActive && isCurrent(token)) {
                    delay(250)
                    if (paused) {
                        lastProgressAt = SystemClock.elapsedRealtime()
                        continue
                    }
                    val sample = withContext(Dispatchers.IO) {
                        synchronized(nativeLock) {
                            if (!isCurrent(token)) null else Triple(ffmpegWrapper.naGetFrameCnt(),
                                ffmpegWrapper.naGetPosition() / 1000L, ffmpegWrapper.naGetDuration() / 1000L)
                        }
                    } ?: break
                    if (!isCurrent(token)) break
                    if (sample.first != lastFrameCount) {
                        lastFrameCount = sample.first
                        lastProgressAt = SystemClock.elapsedRealtime()
                    }
                    val durationMs = if (sample.third > 0L) sample.third else knownDurationMs
                    furthestPositionMs = maxOf(furthestPositionMs, sample.second)
                    onProgress?.invoke(sample.second.coerceAtLeast(0L), durationMs.coerceAtLeast(0L))
                    if (++samples % 8 == 0) Log.d(TAG, "Progress generation=$token frames=${sample.first} positionMs=${sample.second}")
                    if (firstFrameDrawn.get() && durationMs > 0L && furthestPositionMs >= durationMs - 1000L &&
                        SystemClock.elapsedRealtime() - lastProgressAt > 1000L) {
                        onProgress?.invoke(durationMs, durationMs)
                        finishPlayback()
                        break
                    }
                    if (SystemClock.elapsedRealtime() - lastProgressAt > 10_000L) {
                        fail(glSurfaceView.context.getString(R.string.player_stalled))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (isCurrent(token)) stop()
            throw cancelled
        } catch (error: Throwable) {
            if (isCurrent(token)) fail(error.message ?: glSurfaceView.context.getString(R.string.player_open_failed))
        }
    }

    suspend fun seekTo(positionMs: Long): Boolean {
        val token = generation
        return withContext(Dispatchers.IO) {
            synchronized(nativeLock) {
                isCurrent(token) && ffmpegWrapper.naSeek(positionMs.coerceAtLeast(0L) * 1000L) == 0
            }
        }
    }

    fun setPaused(value: Boolean, controlSource: Boolean = true) {
        if (owner !== this || !active || paused == value) return
        if (controlSource) synchronized(nativeLock) {
            if (value) ffmpegWrapper.naPause() else ffmpegWrapper.naResume()
        }
        paused = value
        glSurfaceView.renderMode = if (value) GLSurfaceView.RENDERMODE_WHEN_DIRTY else GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    suspend fun saveSnapshot(file: File): Result<File> = withContext(Dispatchers.IO) {
        val token = generation
        try {
            synchronized(nativeLock) {
                check(isCurrent(token) && sourceReady && firstFrameDrawn.get()) { "No decoded video frame" }
                check(ffmpegWrapper.naSaveSnapshot(file.absolutePath) == 0) { "FFmpeg snapshot failed" }
            }
            val deadline = SystemClock.elapsedRealtime() + 5000L
            var previousLength = -1L
            while (SystemClock.elapsedRealtime() < deadline) {
                check(isCurrent(token)) { "Video source changed during snapshot" }
                delay(100)
                val length = file.length()
                val completeJpeg = length >= 2 && length == previousLength && java.io.RandomAccessFile(file, "r").use {
                    it.seek(length - 2); it.readUnsignedShort() == 0xffd9
                }
                if (completeJpeg) {
                    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                    if (bitmap != null) { bitmap.recycle(); return@withContext Result.success(file) }
                }
                previousLength = length
            }
            error("FFmpeg snapshot did not complete")
        } catch (error: Exception) {
            file.delete()
            if (error is CancellationException) throw error
            Result.failure(error)
        }
    }

    private fun isCurrent(token: Long) = owner === this && active && generation == token

    private fun fail(message: String) {
        Log.w(TAG, message)
        stop()
        onError?.invoke(message)
    }

    private fun finishPlayback() {
        stop()
        onEnded?.invoke()
    }

    fun stop() {
        ++generation
        active = false
        sourceReady = false
        monitorJob?.cancel()
        monitorJob = null
        if (rendererReady) glSurfaceView.onPause()
        if (owner !== this) return
        try {
            synchronized(nativeLock) {
                ffmpegWrapper.getInstance().SetViewHandler(null)
                ffmpegWrapper.naSetBufferingTime(0L)
                ffmpegWrapper.naStop()
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Error stopping FFmpeg", error)
        } finally {
            viewHandler.removeCallbacksAndMessages(null)
            if (network != null && connectivity.boundNetworkForProcess == network) {
                connectivity.bindProcessToNetwork(previousNetwork)
            }
            network = null
            previousNetwork = null
            owner = null
        }
    }

    fun release() {
        stop()
        released = true
        scope.cancel()
        viewHandler.removeCallbacksAndMessages(null)
    }
}
