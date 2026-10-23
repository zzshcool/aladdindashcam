package com.generalplus.ffmpegLib;

import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class ffmpegWrapper implements GLSurfaceView.Renderer {
    public static final int CODEC_ID_H264 = 28;
    public static final int CODEC_ID_MJPEG = 8;
    public static final int CODEC_ID_NONE = 0;
    public static final int CUSTOM_PACKET_AUDIO = 1;
    public static final int CUSTOM_PACKET_VIDEO = 0;
    public static final int DISPLAY_SCALE_FILL = 1;
    public static final int DISPLAY_SCALE_FIT = 0;
    public static final int DISPLAY_SCALE_STRETCH = 2;
    public static final int EXTRACTOR_BUSY = 1;
    public static final int EXTRACTOR_DECODEFAILED = 3;
    public static final int EXTRACTOR_NOSUCHFRAME = 4;
    public static final int EXTRACTOR_OK = 0;
    public static final int EXTRACTOR_READFILEFAILED = 2;
    public static final int FFMPEG_STATUS_BUFFERING = 4;
    public static final int FFMPEG_STATUS_PLAYING = 0;
    public static final int FFMPEG_STATUS_SAVESNAPSHOTCOMPLETE = 2;
    public static final int FFMPEG_STATUS_SAVEVIDEOCOMPLETE = 3;
    public static final int FFMPEG_STATUS_STOPPED = 1;
    public static final String LOW_LOADING_TRANSCODE_OPTIONS = "qmin=15;qmax=35;b=400000;g=15;bf=0;refs=2;weightp=simple;level=2.2;x264-params=lookahead-threads=3:subme=4:chroma_qp_offset=0";
    private static final String TAG = "ffmpegWrapper";
    private static ffmpegWrapper m_Instance;
    private static Handler m_NowViewHandler;
    public GL10 gl10;

    public enum eDisplayScale {
        E_DisplayScale_Fit,
        E_DisplayScale_Fill,
        E_DisplayScale_Stretch
    }

    public enum eEncodeContainer {
        E_EncodeContainer_MP4,
        E_EncodeContainer_AVI
    }

    public enum eFFMPEG_ERRCODE {
        FFMPEGPLAYER_NOERROR,
        FFMPEGPLAYER_INITMEDIAFAILED,
        FFMPEGPLAYER_MEDIAISPLAYING,
        FFMPEGPLAYER_CREATESAVESTREAMFAILED,
        FFMPEGPLAYER_SAVESNAPSHOTFAILED,
        FFMPEGPLAYER_SAVEVIDEOFAILED
    }

    public enum ePlayerStatus {
        E_PlayerStatus_Stoped,
        E_PlayerStatus_Playing,
        E_PlayerStatus_Stoping
    }

    private static boolean isLibraryLoaded = false;

    static {
        try {
            Log.i(TAG, "Trying to load ffmpeg.so ...");
            System.loadLibrary("ffmpeg");
            isLibraryLoaded = true;
            Log.i(TAG, "Successfully loaded ffmpeg.so");
        } catch (Throwable e10) {
            Log.e(TAG, "Cannot load ffmpeg.so ...", e10);
            isLibraryLoaded = false;
        }
    }

    public static boolean isSupported() {
        return isLibraryLoaded;
    }

    public ffmpegWrapper() {
        m_Instance = this;
    }

    public static synchronized ffmpegWrapper getInstance() {
        if (m_Instance == null) {
            m_Instance = new ffmpegWrapper();
        }
        return m_Instance;
    }

    public static native int naCustomProtocol(String str, String str2, String str3);

    public static native int naDrawFrame();

    public static native int naExtractFrame(String str, String str2, long j10);

    public static native ffDecodeFrame naGetDecodeFrame();

    public static native long naGetDuration();

    public static native long naGetFrameCnt();

    public static native long naGetPosition();

    public static native long naGetRevSizeCnt();

    public static native int naGetStreamCodecID();

    public static native String naGetVideoInfo(String str);

    public static native int[] naGetVideoRes();

    public static native int naInitAndPlay(String str, String str2);

    public static native int naInitDrawFrame();

    public static native int naPause();

    public static native int naPlay();

    public static native int naPushCustomPacket(byte[] bArr, int i10, int i11, long j10);

    public static native int naResume();

    public static native int naSaveSnapshot(String str);

    public static native int naSaveVideo(String str);

    public static native int naSeek(long j10);

    public static native int naSetBufferingTime(long j10);

    public static native int naSetCovertDecodeFrameFormat(int i10);

    public static native int naSetDebugMessage(boolean z10);

    public static native int naSetDecodeOptions(String str);

    public static native int naSetEncodeByLocalTime(boolean z10);

    public static native int naSetForceToTranscode(boolean z10);

    public static native int naSetRepeat(boolean z10);

    public static native int naSetScaleMode(int i10);

    public static native int naSetStreaming(boolean z10);

    public static native int naSetTransCodeOptions(String str);

    public static native int naSetZoomInRatio(float f10);

    public static native int naSetup(int i10, int i11);

    public static native int naStatus();

    public static native int naStop();

    public static native int naStopSaveVideo();

    public void SetViewHandler(Handler handler) {
        m_NowViewHandler = handler;
    }

    public void StatusChange(int i10) {
        if (m_NowViewHandler != null) {
            Message message = new Message();
            message.what = i10;
            m_NowViewHandler.sendMessage(message);
        }
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public void onDrawFrame(GL10 gl10) {
        try {
            naDrawFrame();
        } catch (Throwable t) {
            Log.e(TAG, "Error in naDrawFrame", t);
        }
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public void onSurfaceChanged(GL10 gl10, int i10, int i11) {
        Log.i(TAG, "onSurfaceChanged: " + i10 + "x" + i11);
        try {
            naSetup(i10, i11);
        } catch (Throwable t) {
            Log.e(TAG, "Error in naSetup", t);
        }
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public void onSurfaceCreated(GL10 gl10, EGLConfig eGLConfig) {
        Log.i(TAG, "onSurfaceCreated");
        this.gl10 = gl10;
        try {
            naInitDrawFrame();
        } catch (Throwable t) {
            Log.e(TAG, "Error in naInitDrawFrame", t);
        }
    }
}
