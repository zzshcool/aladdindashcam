package com.aladdin.dashcam.media

import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import com.generalplus.ffmpegLib.ffmpegWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class NativeFfmpegPlayer(
    private val glSurfaceView: GLSurfaceView,
    private val onFirstFrameRendered: (() -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "NativeFfmpegPlayer"
        val isSupported: Boolean get() = ffmpegWrapper.isSupported()
    }

    private val isPlaying = AtomicBoolean(false)
    private var currentUrl: String? = null
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var isRendererSet = false

    private val viewHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                ffmpegWrapper.FFMPEG_STATUS_PLAYING -> {
                    Log.i(TAG, "Native player status: PLAYING")
                    onFirstFrameRendered?.invoke()
                }
                ffmpegWrapper.FFMPEG_STATUS_BUFFERING -> {
                    Log.i(TAG, "Native player status: BUFFERING")
                }
                ffmpegWrapper.FFMPEG_STATUS_STOPPED -> {
                    Log.i(TAG, "Native player status: STOPPED")
                }
            }
        }
    }

    init {
        try {
            if (isSupported) {
                glSurfaceView.setEGLContextClientVersion(2)
                glSurfaceView.setRenderer(ffmpegWrapper.getInstance())
                glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                ffmpegWrapper.getInstance().SetViewHandler(viewHandler)
                isRendererSet = true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize GLSurfaceView renderer", e)
            isRendererSet = false
        }
    }

    fun start(rtspUrl: String) {
        if (!isSupported || !isRendererSet) {
            onError?.invoke("Native ffmpeg engine is not supported or failed to initialize")
            return
        }

        stop()

        currentUrl = rtspUrl
        Log.i(TAG, "Starting native ffmpeg stream: $rtspUrl")

        try {
            glSurfaceView.onResume()
            ffmpegWrapper.naSetStreaming(true)
            ffmpegWrapper.naSetBufferingTime(40L)
            ffmpegWrapper.naSetScaleMode(ffmpegWrapper.DISPLAY_SCALE_FIT)

            val ret = ffmpegWrapper.naInitAndPlay(rtspUrl, "timeout=-1;stimeout=-1;probesize=4096")
            Log.i(TAG, "naInitAndPlay returned: $ret")
            isPlaying.set(true)

            monitorJob?.cancel()
            monitorJob = scope.launch(Dispatchers.IO) {
                var firstFrameDetected = false
                var attempts = 0
                while (isActive && isPlaying.get()) {
                    delay(300)
                    attempts++
                    val status = ffmpegWrapper.naStatus()
                    val frameCnt = ffmpegWrapper.naGetFrameCnt()
                    if (!firstFrameDetected && (status == ffmpegWrapper.ePlayerStatus.E_PlayerStatus_Playing.ordinal || frameCnt > 0)) {
                        firstFrameDetected = true
                        Log.i(TAG, "First frame detected! frameCnt=$frameCnt, status=$status")
                        launch(Dispatchers.Main) {
                            onFirstFrameRendered?.invoke()
                        }
                    }
                    if (attempts > 30 && !firstFrameDetected) {
                        Log.w(TAG, "No frames received after 10 seconds of streaming")
                        launch(Dispatchers.Main) {
                            onError?.invoke("串流逾時未收到影格")
                        }
                        break
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error starting native stream", e)
            onError?.invoke("Native stream error: ${e.message}")
        }
    }

    fun stop() {
        if (!isPlaying.getAndSet(false)) return
        Log.i(TAG, "Stopping native ffmpeg stream")
        monitorJob?.cancel()
        monitorJob = null
        try {
            glSurfaceView.onPause()
            ffmpegWrapper.naStop()
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping native ffmpeg stream", e)
        }
    }

    fun pause() {
        try {
            glSurfaceView.onPause()
            ffmpegWrapper.naPause()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in pause", e)
        }
    }

    fun resume() {
        try {
            glSurfaceView.onResume()
            ffmpegWrapper.naResume()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in resume", e)
        }
    }

    fun saveSnapshot(destPath: String): Boolean {
        return try {
            val ret = ffmpegWrapper.naSaveSnapshot(destPath)
            ret == 0
        } catch (e: Throwable) {
            Log.e(TAG, "Error saving snapshot via native ffmpeg", e)
            false
        }
    }

    fun release() {
        stop()
    }
}
