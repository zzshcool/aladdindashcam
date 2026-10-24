package com.aladdin.dashcam.media

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/** One local-file session: FFmpeg packets/audio, hardware video and an AudioTrack media clock. */
internal class HardwarePlaybackEngine(
    private val original: View,
    private val firstFrame: () -> Unit,
    private val progress: (Long, Long, Long) -> Unit,
    private val ended: () -> Unit,
    private val failed: (Throwable) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val surfaceReady = CompletableDeferred<Surface>()
    private val pendingSeek = AtomicLong(-1)
    private var worker: Job? = null
    private var textureRemoved = false
    private var outputSurface: Surface? = null
    @Volatile private var textureDetached = false
    @Volatile private var renderedPtsUs = -1L
    @Volatile private var closed = false
    @Volatile private var paused = false
    @Volatile var positionMs = 0L; private set
    @Volatile private var frames = 0L
    private var videoWidth = 0
    private var videoHeight = 0
    val texture = TextureView(original.context)
    private val parent = original.parent as FrameLayout
    private val layoutObserver = android.view.ViewTreeObserver.OnGlobalLayoutListener { syncLayout() }

    init {
        texture.setOnClickListener { original.performClick() }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                if (!closed) { outputSurface = Surface(value); surfaceReady.complete(outputSurface!!); fit() }
            }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) = fit()
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                textureDetached = true
                if (!closed) { close(); failed(IllegalStateException("Hardware video surface lost")) }
                return false
            }
        }
        parent.addView(texture, parent.indexOfChild(original) + 1, FrameLayout.LayoutParams(original.layoutParams as FrameLayout.LayoutParams))
        original.visibility = View.GONE
        texture.keepScreenOn = true
        parent.viewTreeObserver.addOnGlobalLayoutListener(layoutObserver)
    }

    private fun syncLayout() {
        if (closed) return
        val source = original.layoutParams as FrameLayout.LayoutParams
        val current = texture.layoutParams as FrameLayout.LayoutParams
        if (source.width != current.width || source.height != current.height || source.gravity != current.gravity ||
            source.leftMargin != current.leftMargin || source.rightMargin != current.rightMargin ||
            source.topMargin != current.topMargin || source.bottomMargin != current.bottomMargin ||
            source.marginStart != current.marginStart || source.marginEnd != current.marginEnd) {
            texture.layoutParams = FrameLayout.LayoutParams(source)
        }
    }

    private fun fit() {
        if (texture.width == 0 || texture.height == 0 || videoWidth == 0 || videoHeight == 0) return
        val source = videoWidth.toFloat() / videoHeight
        val view = texture.width.toFloat() / texture.height
        val sx = if (source < view) source / view else 1f
        val sy = if (source > view) view / source else 1f
        texture.setTransform(Matrix().apply { setScale(sx, sy, texture.width / 2f, texture.height / 2f) })
    }

    suspend fun start(source: String, startMs: Long) {
        val surface = withTimeout(3000) { surfaceReady.await() }
        val initialized = CompletableDeferred<Unit>()
        worker = scope.launch {
            var completed = false
            var reader: FfmpegPacketReader? = null
            var codec: MediaCodec? = null
            var track: AudioTrack? = null
            val ownedTexture = texture.surfaceTexture
            try {
                val input = FfmpegPacketReader(source).also { reader = it }
                check(android.os.Build.VERSION.SDK_INT >= 29) { "Verified hardware decoder selection requires Android 10" }
                val format = input.videoFormat()
                val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                    !it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.any { type -> type.equals(input.mime, true) } &&
                        it.getCapabilitiesForType(input.mime).isFormatSupported(format)
                }?.name ?: error("No compatible hardware video decoder")
                val decoder = MediaCodec.createByCodecName(name).also { codec = it }
                decoder.configure(format, surface, null, 0)
                var first = true
                decoder.setOnFrameRenderedListener({ _, pts, _ ->
                    if (!closed) { renderedPtsUs = pts; frames++; if (first) { first = false; firstFrame() } }
                }, main)
                decoder.start()
                withContext(Dispatchers.Main) {
                    val rotated = input.rotation == 90 || input.rotation == 270
                    videoWidth = if (rotated) input.height else input.width
                    videoHeight = if (rotated) input.width else input.height
                    fit()
                }
                Log.i("HardwarePlayback", "FFmpeg demux -> $name hardware=true ${input.width}x${input.height} audio=${input.sampleRate}/${input.channels}")
                initialized.complete(Unit)
                var targetUs = startMs.coerceAtLeast(0) * 1000
                if (targetUs > 0) input.seek(targetUs)
                var baseNs = SystemClock.elapsedRealtimeNanos() - targetUs * 1000
                var audioStartUs = -1L
                var audioWrittenFrames = 0L
                var audioLastUs = targetUs
                var eofClockNs = 0L
                var eofClockUs = 0L
                var lastHead = -1L
                var lastHeadAt = SystemClock.elapsedRealtimeNanos()
                var pauseAt = 0L
                var seekPreview = false
                var nextClockReport = SystemClock.elapsedRealtime() + 2000
                var pending: LongArray? = null
                var eof = false
                var inputEnded = false
                var outputEnded = false
                var outputIndex = -1
                val outputInfo = MediaCodec.BufferInfo()
                var lastReport = 0L
                var lastActivity = SystemClock.elapsedRealtime()
                fun audioTrack(): AudioTrack {
                    track?.let { return it }
                    val channel = if (input.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    val minimum = AudioTrack.getMinBufferSize(input.sampleRate, channel, AudioFormat.ENCODING_PCM_16BIT)
                    check(minimum > 0)
                    return AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(input.sampleRate).setChannelMask(channel).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(minimum * 4).setTransferMode(AudioTrack.MODE_STREAM).build().also { track = it }
                }
                fun clockUs(): Long {
                    val now = SystemClock.elapsedRealtimeNanos()
                    val audio = track
                    if (audio != null && audioStartUs >= 0) {
                        val head = audio.playbackHeadPosition.toLong() and 0xffffffffL
                        val media = audioStartUs + head * 1_000_000 / input.sampleRate
                        if (head != lastHead) { lastHead = head; lastHeadAt = now }
                        if (eof && head >= audioWrittenFrames) {
                            if (eofClockNs == 0L) { eofClockNs = now; eofClockUs = media }
                            return eofClockUs + (now - eofClockNs) / 1000
                        }
                        check(now - lastHeadAt < 10_000_000_000L) { "Audio playback clock stalled" }
                        return media
                    }
                    return (now - baseNs) / 1000
                }
                while (isActive && !closed) {
                    val seek = pendingSeek.getAndSet(-1)
                    if (seek >= 0) {
                        targetUs = seek
                        seekPreview = paused
                        renderedPtsUs = -1
                        input.seek(targetUs); decoder.flush()
                        track?.pause(); track?.flush(); track?.release(); track = null
                        audioStartUs = -1; audioWrittenFrames = 0; audioLastUs = targetUs
                        baseNs = SystemClock.elapsedRealtimeNanos() - targetUs * 1000
                        pending = null; eof = false; inputEnded = false; outputEnded = false; outputIndex = -1
                        eofClockNs = 0; lastHead = -1; pauseAt = 0
                        lastActivity = SystemClock.elapsedRealtime()
                        Log.i("HardwarePlayback", "Seek targetUs=$targetUs")
                    }
                    if (paused && !seekPreview) {
                        if (pauseAt == 0L) { pauseAt = SystemClock.elapsedRealtimeNanos(); track?.pause() }
                        lastActivity = SystemClock.elapsedRealtime(); lastHeadAt = SystemClock.elapsedRealtimeNanos()
                        delay(10); continue
                    }
                    if (pauseAt != 0L) {
                        baseNs += SystemClock.elapsedRealtimeNanos() - pauseAt; pauseAt = 0
                        if (audioStartUs >= 0) track?.play()
                        lastHeadAt = SystemClock.elapsedRealtimeNanos()
                    }
                    var activity = false
                    if (outputIndex < 0 && !outputEnded) {
                        outputIndex = decoder.dequeueOutputBuffer(outputInfo, 0)
                        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outputIndex = -1
                    }
                    if (outputIndex >= 0) {
                        val pts = outputInfo.presentationTimeUs
                        val isEnd = outputInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (pts < targetUs || (isEnd && outputInfo.size == 0)) {
                            decoder.releaseOutputBuffer(outputIndex, false); outputIndex = -1; outputEnded = isEnd
                            activity = true
                        } else {
                            val delta = pts - clockUs()
                            if (delta <= 20_000 || seekPreview) {
                                decoder.releaseOutputBuffer(outputIndex, System.nanoTime() + delta.coerceAtLeast(0) * 1000)
                                outputIndex = -1; outputEnded = isEnd; activity = true
                                seekPreview = false
                            }
                        }
                    }
                    if (pending == null && !eof) {
                        pending = input.read()
                        if (pending == null) eof = true
                        else activity = true
                    }
                    val packet = pending
                    if (packet != null) {
                        if (packet[0] == 0L) {
                            val index = decoder.dequeueInputBuffer(0)
                            if (index >= 0) {
                                val buffer = decoder.getInputBuffer(index)!!
                                check(buffer.remaining() >= input.packet.remaining()) { "Hardware input packet exceeds codec capacity" }
                                buffer.put(input.packet)
                                decoder.queueInputBuffer(index, 0, packet[1].toInt(), packet[2].coerceAtLeast(0), 0)
                                pending = null; activity = true
                            }
                        } else {
                            val bytesPerFrame = input.channels * 2
                            if (input.packet.position() == 0 && packet[2] < targetUs) {
                                val skip = ((targetUs - packet[2]) * input.sampleRate / 1_000_000 * bytesPerFrame).coerceAtMost(packet[1]).toInt()
                                input.packet.position(skip - skip % bytesPerFrame)
                            }
                            if (input.packet.hasRemaining()) {
                                val audio = audioTrack()
                                val before = input.packet.position()
                                val count = audio.write(input.packet, input.packet.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                                check(count >= 0) { "AudioTrack write failed: $count" }
                                if (count > 0) {
                                    if (audioStartUs < 0) {
                                        audioStartUs = packet[2] + before / bytesPerFrame * 1_000_000L / input.sampleRate
                                        lastHeadAt = SystemClock.elapsedRealtimeNanos(); if (!paused) audio.play()
                                    }
                                    audioWrittenFrames += count / bytesPerFrame
                                    audioLastUs = packet[2] + input.packet.position() / bytesPerFrame * 1_000_000L / input.sampleRate
                                    activity = true
                                }
                            }
                            if (!input.packet.hasRemaining()) pending = null
                        }
                    }
                    if (eof && !inputEnded) {
                        val index = decoder.dequeueInputBuffer(0)
                        if (index >= 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true; activity = true
                        }
                    }
                    val now = SystemClock.elapsedRealtime()
                    val position = clockUs().coerceAtLeast(targetUs)
                    positionMs = position / 1000
                    if (now - lastReport >= 250) {
                        lastReport = now
                        main.post { if (!closed) progress(positionMs, input.durationUs / 1000, frames) }
                    }
                    if (now >= nextClockReport) {
                        nextClockReport = now + 2000
                        Log.d("HardwarePlayback", "Clock audioUs=$position videoUs=$renderedPtsUs deltaUs=${if (renderedPtsUs >= 0) renderedPtsUs - position else 0}")
                    }
                    if (outputEnded && (track == null || position >= audioLastUs)) {
                        completed = true
                        positionMs = input.durationUs / 1000
                        main.post {
                            if (!closed) {
                                texture.keepScreenOn = false
                                progress(positionMs, input.durationUs / 1000, frames)
                                ended()
                            }
                        }; break
                    }
                    if (activity) lastActivity = now
                    check(now - lastActivity < 10_000) { "Hardware video playback stalled" }
                    if (!activity) delay(2) else yield()
                }
            } catch (cancelled: CancellationException) {
                initialized.cancel(cancelled)
            } catch (error: Throwable) {
                if (!initialized.isCompleted) initialized.completeExceptionally(error)
                else main.post { if (!closed) failed(error) }
            } finally {
                runCatching { track?.pause() }; runCatching { track?.flush() }; runCatching { track?.release() }
                runCatching { codec?.stop() }; runCatching { codec?.release() }; reader?.close()
                surface.release()
                main.post {
                    if (closed || !completed) {
                        removeTexture()
                        if (textureDetached) ownedTexture?.release()
                    }
                }
            }
        }
        initialized.await()
    }
    fun pause(value: Boolean) { paused = value }
    fun seek(positionMs: Long): Boolean { if (closed) return false; pendingSeek.set(positionMs.coerceAtLeast(0) * 1000); return true }
    private fun removeTexture() {
        if (textureRemoved) return
        textureRemoved = true
        texture.surfaceTextureListener = null
        parent.removeView(texture)
    }
    fun close() {
        if (closed) return
        closed = true; texture.keepScreenOn = false
        if (parent.viewTreeObserver.isAlive) parent.viewTreeObserver.removeOnGlobalLayoutListener(layoutObserver)
        scope.cancel()
        if (worker == null || worker?.isCompleted == true) {
            outputSurface?.release()
            val retained = texture.surfaceTexture
            removeTexture()
            if (textureDetached) retained?.release()
            original.visibility = View.VISIBLE
        }
    }
}
