package com.aladdin.dashcam.media

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
                        if (owner !== this@NativeFfmpegPlayer || !active || !sourceReady || paused) return
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
                            viewHandler.post { if (isCurrent(token)) fail("影片渲染失敗：${error.message}") }
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
            onError?.invoke("此裝置無法載入 FFmpeg 播放引擎")
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
                check(connectivity.bindProcessToNetwork(targetNetwork)) { "無法綁定記錄器 Wi-Fi" }
                network = targetNetwork
            }
            ffmpegWrapper.getInstance().SetViewHandler(viewHandler)
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
            check(result == ffmpegWrapper.eFFMPEG_ERRCODE.FFMPEGPLAYER_NOERROR.ordinal) { "FFmpeg 開啟失敗：$result" }
            sourceReady = true
            if (!streaming && startPositionMs > 0L) seekTo(startPositionMs)
            Log.i(TAG, "FFmpeg source opened: streaming=$streaming, generation=$token")
            monitorJob = scope.launch {
                var lastFrameCount = -1L
                var lastProgressAt = SystemClock.elapsedRealtime()
                var samples = 0
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
                    onProgress?.invoke(sample.second.coerceAtLeast(0L), durationMs.coerceAtLeast(0L))
                    if (++samples % 8 == 0) Log.d(TAG, "Progress generation=$token frames=${sample.first} positionMs=${sample.second}")
                    if (!streaming && durationMs > 0L && sample.second >= durationMs - 1000L &&
                        SystemClock.elapsedRealtime() - lastProgressAt > 1000L) {
                        finishPlayback()
                        break
                    }
                    if (SystemClock.elapsedRealtime() - lastProgressAt > 10_000L) {
                        fail("影片影格已中斷，請重新播放")
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (isCurrent(token)) stop()
            throw cancelled
        } catch (error: Throwable) {
            if (isCurrent(token)) fail(error.message ?: "影片開啟失敗")
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
