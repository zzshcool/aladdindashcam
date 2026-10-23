#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>
extern "C" {
#include "libavformat/avformat.h"
#include "libavcodec/avcodec.h"
#include "libavutil/audio_fifo.h"
#include "libavutil/imgutils.h"
#include "libavutil/opt.h"
#include "libswscale/swscale.h"
#include "libswresample/swresample.h"
}

namespace {
void checked(int code) {
    if (code >= 0) return;
    char error[AV_ERROR_MAX_STRING_SIZE]; av_strerror(code, error, sizeof(error));
    throw std::runtime_error(error);
}
struct Utf {
    JNIEnv* env; jstring value; const char* str;
    Utf(JNIEnv* e, jstring v): env(e), value(v), str(e->GetStringUTFChars(v, nullptr)) {}
    ~Utf() { env->ReleaseStringUTFChars(value, str); }
};
struct Export {
    AVFormatContext *input = nullptr, *output = nullptr;
    AVCodecContext *vd = nullptr, *ve = nullptr, *ad = nullptr, *ae = nullptr;
    AVStream *vs = nullptr, *as = nullptr;
    AVFrame *decoded = av_frame_alloc(), *yuv = av_frame_alloc(), *rgba = av_frame_alloc();
    SwsContext *toRgba = nullptr, *toYuv = nullptr;
    SwrContext *resampler = nullptr;
    AVAudioFifo *fifo = nullptr;
    int vi = -1, ai = -1;
    int64_t startUs = 0, endUs = 0, originUs = 0, audioPts = AV_NOPTS_VALUE;
    int64_t videoPts = AV_NOPTS_VALUE;
    bool audioStarted = false, header = false;
    ~Export() {
        av_frame_free(&decoded); av_frame_free(&yuv); av_frame_free(&rgba);
        avcodec_free_context(&vd); avcodec_free_context(&ve); avcodec_free_context(&ad); avcodec_free_context(&ae);
        sws_freeContext(toRgba); sws_freeContext(toYuv); swr_free(&resampler);
        if (fifo) av_audio_fifo_free(fifo);
        avformat_close_input(&input);
        if (output) { if (output->pb) avio_closep(&output->pb); avformat_free_context(output); }
    }
    AVCodecContext* decoder(int index) {
        auto par = input->streams[index]->codecpar;
        auto codec = avcodec_find_decoder(par->codec_id);
        if (!codec) throw std::runtime_error("Decoder unavailable");
        auto ctx = avcodec_alloc_context3(codec);
        if (!ctx) throw std::bad_alloc();
        try { checked(avcodec_parameters_to_context(ctx, par)); checked(avcodec_open2(ctx, codec, nullptr)); }
        catch (...) { avcodec_free_context(&ctx); throw; }
        return ctx;
    }
    void writePackets(AVCodecContext* encoder, AVStream* stream) {
        AVPacket packet; av_init_packet(&packet); packet.data = nullptr; packet.size = 0;
        for (;;) {
            const int result = avcodec_receive_packet(encoder, &packet);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) break;
            checked(result);
            av_packet_rescale_ts(&packet, encoder->time_base, stream->time_base);
            packet.stream_index = stream->index; packet.pos = -1;
            int written = av_interleaved_write_frame(output, &packet);
            av_packet_unref(&packet); checked(written);
        }
    }
    void send(AVCodecContext* encoder, AVStream* stream, AVFrame* frame) {
        checked(avcodec_send_frame(encoder, frame)); writePackets(encoder, stream);
    }
    void encodeAudio(bool flush) {
        const int block = ae->frame_size > 0 ? ae->frame_size : 1024;
        while (av_audio_fifo_size(fifo) >= block || (flush && av_audio_fifo_size(fifo) > 0)) {
            AVFrame* frame = av_frame_alloc();
            if (!frame) throw std::bad_alloc();
            try {
                frame->nb_samples = block; frame->format = ae->sample_fmt;
                frame->channel_layout = ae->channel_layout; frame->sample_rate = ae->sample_rate;
                checked(av_frame_get_buffer(frame, 0));
                const int available = std::min(block, av_audio_fifo_size(fifo));
                checked(av_audio_fifo_read(fifo, reinterpret_cast<void**>(frame->data), available));
                if (available < block) checked(av_samples_set_silence(frame->data, available, block - available, ae->channels, ae->sample_fmt));
                frame->pts = audioPts; audioPts += block;
                send(ae, as, frame);
            } catch (...) { av_frame_free(&frame); throw; }
            av_frame_free(&frame);
        }
    }
    void audioFrame(AVFrame* frame, int64_t time) {
        const int64_t frameEnd = time + av_rescale(frame->nb_samples, 1000000, ad->sample_rate);
        if (frameEnd <= startUs || time >= endUs) return;
        if (!resampler) {
            int64_t layout = ad->channel_layout ? ad->channel_layout : av_get_default_channel_layout(ad->channels);
            resampler = swr_alloc_set_opts(nullptr, ae->channel_layout, ae->sample_fmt, ae->sample_rate,
                layout, ad->sample_fmt, ad->sample_rate, 0, nullptr);
            if (!resampler) throw std::bad_alloc();
            checked(swr_init(resampler));
        }
        const int skip = static_cast<int>(av_rescale(std::max<int64_t>(0, startUs - time), ad->sample_rate, 1000000));
        const int count = std::min(frame->nb_samples - skip,
            static_cast<int>(av_rescale(std::max<int64_t>(0, endUs - time), ad->sample_rate, 1000000)) - skip);
        if (count <= 0) return;
        if (!audioStarted) {
            audioPts = av_rescale(std::max<int64_t>(0, time - startUs), ae->sample_rate, 1000000);
            audioStarted = true;
        }
        const int planes = av_sample_fmt_is_planar(ad->sample_fmt) ? ad->channels : 1;
        const int bytes = av_get_bytes_per_sample(ad->sample_fmt) * (planes == 1 ? ad->channels : 1);
        std::vector<const uint8_t*> source(planes);
        for (int p = 0; p < planes; ++p) source[p] = frame->extended_data[p] + skip * bytes;
        const int capacity = swr_get_out_samples(resampler, count);
        uint8_t** converted = nullptr;
        checked(av_samples_alloc_array_and_samples(&converted, nullptr, ae->channels, capacity, ae->sample_fmt, 0));
        try {
            int samples = swr_convert(resampler, converted, capacity, source.data(), count); checked(samples);
            checked(av_audio_fifo_realloc(fifo, av_audio_fifo_size(fifo) + samples));
            checked(av_audio_fifo_write(fifo, reinterpret_cast<void**>(converted), samples));
            encodeAudio(false);
        } catch (...) { av_freep(&converted[0]); av_freep(&converted); throw; }
        av_freep(&converted[0]); av_freep(&converted);
    }
};
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aladdin_dashcam_media_VideoExporter_nativeExport(JNIEnv* env, jobject, jstring source, jstring destination,
        jlong startMs, jlong endMs, jboolean overlay, jboolean limit720p, jobject callback) {
    Utf in(env, source), out(env, destination);
    try {
        Export x;
        if ((avcodec_version() >> 16) != 58 || (avformat_version() >> 16) != 58)
            throw std::runtime_error("Unsupported FFmpeg ABI");
        av_register_all();
        checked(avformat_open_input(&x.input, in.str, nullptr, nullptr));
        checked(avformat_find_stream_info(x.input, nullptr));
        x.vi = av_find_best_stream(x.input, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0); checked(x.vi);
        x.ai = av_find_best_stream(x.input, AVMEDIA_TYPE_AUDIO, -1, x.vi, nullptr, 0);
        x.originUs = x.input->start_time == AV_NOPTS_VALUE ? 0 : x.input->start_time;
        x.startUs = startMs * 1000 + x.originUs; x.endUs = endMs * 1000 + x.originUs;
        if (startMs < 0 || endMs <= startMs) throw std::runtime_error("Invalid clip range");
        x.vd = x.decoder(x.vi);
        checked(avformat_alloc_output_context2(&x.output, nullptr, "mp4", out.str));
        auto codec = avcodec_find_encoder_by_name("libx264");
        if (!codec) codec = avcodec_find_encoder(AV_CODEC_ID_H264);
        if (!codec) throw std::runtime_error("H.264 encoder unavailable");
        x.ve = avcodec_alloc_context3(codec); if (!x.ve) throw std::bad_alloc();
        const double scale = !limit720p ? 1.0 : x.vd->width >= x.vd->height ?
            std::min(1.0, std::min(1280.0 / x.vd->width, 720.0 / x.vd->height)) :
            std::min(1.0, std::min(720.0 / x.vd->width, 1280.0 / x.vd->height));
        x.ve->width = std::max(2, static_cast<int>(x.vd->width * scale) / 2 * 2);
        x.ve->height = std::max(2, static_cast<int>(x.vd->height * scale) / 2 * 2);
        x.ve->pix_fmt = AV_PIX_FMT_YUV420P; x.ve->time_base = {1, 90000};
        x.ve->framerate = av_guess_frame_rate(x.input, x.input->streams[x.vi], nullptr);
        x.ve->bit_rate = 4000000; x.ve->gop_size = 30; x.ve->max_b_frames = 0;
        if (x.output->oformat->flags & AVFMT_GLOBALHEADER) x.ve->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
        AVDictionary* options = nullptr;
        av_dict_set(&options, "preset", "veryfast", 0); av_dict_set(&options, "crf", "23", 0);
        const int opened = avcodec_open2(x.ve, codec, &options); av_dict_free(&options); checked(opened);
        x.vs = avformat_new_stream(x.output, nullptr); if (!x.vs) throw std::bad_alloc();
        x.vs->time_base = x.ve->time_base;
        checked(avcodec_parameters_from_context(x.vs->codecpar, x.ve));
        av_dict_copy(&x.vs->metadata, x.input->streams[x.vi]->metadata, 0);
        int matrixSize = 0;
        auto matrix = av_stream_get_side_data(x.input->streams[x.vi], AV_PKT_DATA_DISPLAYMATRIX, &matrixSize);
        if (matrix && matrixSize > 0) {
            auto copied = av_stream_new_side_data(x.vs, AV_PKT_DATA_DISPLAYMATRIX, matrixSize);
            if (!copied) throw std::bad_alloc();
            memcpy(copied, matrix, matrixSize);
        }
        if (x.ai >= 0) {
            x.ad = x.decoder(x.ai);
            auto aac = avcodec_find_encoder(AV_CODEC_ID_AAC);
            if (!aac) throw std::runtime_error("AAC encoder unavailable");
            x.ae = avcodec_alloc_context3(aac); if (!x.ae) throw std::bad_alloc();
            x.ae->channel_layout = AV_CH_LAYOUT_STEREO; x.ae->channels = 2;
            x.ae->sample_rate = 48000; x.ae->sample_fmt = AV_SAMPLE_FMT_FLTP;
            x.ae->bit_rate = 128000; x.ae->time_base = {1, 48000};
            if (x.output->oformat->flags & AVFMT_GLOBALHEADER) x.ae->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
            checked(avcodec_open2(x.ae, aac, nullptr));
            x.as = avformat_new_stream(x.output, nullptr); if (!x.as) throw std::bad_alloc();
            x.as->time_base = x.ae->time_base; checked(avcodec_parameters_from_context(x.as->codecpar, x.ae));
            x.fifo = av_audio_fifo_alloc(x.ae->sample_fmt, x.ae->channels, 4096); if (!x.fifo) throw std::bad_alloc();
        }
        checked(avio_open(&x.output->pb, out.str, AVIO_FLAG_WRITE));
        AVDictionary* mux = nullptr; av_dict_set(&mux, "movflags", "+faststart", 0);
        const int written = avformat_write_header(x.output, &mux); av_dict_free(&mux); checked(written); x.header = true;
        x.yuv->format = x.ve->pix_fmt; x.yuv->width = x.ve->width; x.yuv->height = x.ve->height;
        checked(av_frame_get_buffer(x.yuv, 32));
        x.rgba->format = AV_PIX_FMT_RGBA; x.rgba->width = x.ve->width; x.rgba->height = x.ve->height;
        checked(av_frame_get_buffer(x.rgba, 32));
        auto cls = env->GetObjectClass(callback);
        auto cancelled = env->GetMethodID(cls, "isCancelled", "()Z");
        auto progress = env->GetMethodID(cls, "onProgress", "(J)V");
        auto render = env->GetMethodID(cls, "renderOverlay", "(JII)Landroid/graphics/Bitmap;");
        if (!cancelled || !progress || !render) throw std::runtime_error("Export callback unavailable");
        checked(av_seek_frame(x.input, -1, x.startUs, AVSEEK_FLAG_BACKWARD));
        avcodec_flush_buffers(x.vd); if (x.ad) avcodec_flush_buffers(x.ad);
        int64_t lastProgress = -1, overlaySecond = -1;
        std::vector<uint8_t> overlayPixels;
        uint32_t overlayWidth = 0, overlayHeight = 0;
        int frames = 0;
        auto consume = [&](bool video) {
            AVCodecContext* decoder = video ? x.vd : x.ad;
            const int index = video ? x.vi : x.ai;
            for (;;) {
                int result = avcodec_receive_frame(decoder, x.decoded);
                if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) break;
                checked(result);
                int64_t pts = x.decoded->best_effort_timestamp;
                if (pts == AV_NOPTS_VALUE) { av_frame_unref(x.decoded); continue; }
                const int64_t time = av_rescale_q(pts, x.input->streams[index]->time_base, AV_TIME_BASE_Q);
                if (!video) { x.audioFrame(x.decoded, time); av_frame_unref(x.decoded); continue; }
                if (time < x.startUs || time >= x.endUs) { av_frame_unref(x.decoded); continue; }
                checked(av_frame_make_writable(x.rgba)); checked(av_frame_make_writable(x.yuv));
                x.toRgba = sws_getCachedContext(x.toRgba, x.decoded->width, x.decoded->height,
                    static_cast<AVPixelFormat>(x.decoded->format), x.ve->width, x.ve->height, AV_PIX_FMT_RGBA, SWS_BILINEAR, nullptr, nullptr, nullptr);
                if (!x.toRgba) throw std::bad_alloc();
                sws_scale(x.toRgba, x.decoded->data, x.decoded->linesize, 0, x.decoded->height, x.rgba->data, x.rgba->linesize);
                const int64_t positionMs = (time - x.originUs) / 1000;
                if (overlay && positionMs / 1000 != overlaySecond) {
                    overlaySecond = positionMs / 1000;
                    auto bitmap = env->CallObjectMethod(callback, render, static_cast<jlong>(positionMs), x.ve->width, x.ve->height);
                    if (env->ExceptionCheck()) throw std::runtime_error("Overlay rendering failed");
                    overlayPixels.clear(); overlayWidth = 0; overlayHeight = 0;
                    if (bitmap) {
                        AndroidBitmapInfo info; void* pixels = nullptr;
                        if (AndroidBitmap_getInfo(env, bitmap, &info) != 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
                            AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0) throw std::runtime_error("Overlay bitmap unavailable");
                        overlayWidth = std::min(info.width, static_cast<uint32_t>(x.ve->width));
                        overlayHeight = std::min(info.height, static_cast<uint32_t>(x.ve->height));
                        overlayPixels.resize(overlayWidth * overlayHeight * 4);
                        for (uint32_t row = 0; row < overlayHeight; ++row)
                            memcpy(overlayPixels.data() + row * overlayWidth * 4, static_cast<uint8_t*>(pixels) + row * info.stride, overlayWidth * 4);
                        AndroidBitmap_unlockPixels(env, bitmap); env->DeleteLocalRef(bitmap);
                    }
                }
                for (uint32_t row = 0; row < overlayHeight; ++row) {
                    auto dest = x.rgba->data[0] + row * x.rgba->linesize[0];
                    auto src = overlayPixels.data() + row * overlayWidth * 4;
                    for (uint32_t col = 0; col < overlayWidth; ++col) {
                        const int alpha = src[col * 4 + 3];
                        for (int channel = 0; channel < 3; ++channel)
                            dest[col * 4 + channel] = static_cast<uint8_t>(std::min(255, src[col * 4 + channel] + dest[col * 4 + channel] * (255 - alpha) / 255));
                    }
                }
                x.toYuv = sws_getCachedContext(x.toYuv, x.ve->width, x.ve->height, AV_PIX_FMT_RGBA,
                    x.ve->width, x.ve->height, x.ve->pix_fmt, SWS_BILINEAR, nullptr, nullptr, nullptr);
                if (!x.toYuv) throw std::bad_alloc();
                sws_scale(x.toYuv, x.rgba->data, x.rgba->linesize, 0, x.ve->height, x.yuv->data, x.yuv->linesize);
                const auto outputPts = av_rescale_q(time - x.startUs, AV_TIME_BASE_Q, x.ve->time_base);
                if (x.videoPts == AV_NOPTS_VALUE || outputPts > x.videoPts) {
                    x.yuv->pts = outputPts; x.videoPts = outputPts; x.send(x.ve, x.vs, x.yuv); ++frames;
                }
                if (positionMs - lastProgress >= 250) { env->CallVoidMethod(callback, progress, static_cast<jlong>(positionMs - startMs)); lastProgress = positionMs; }
                av_frame_unref(x.decoded);
            }
        };
        AVPacket packet; av_init_packet(&packet); packet.data = nullptr; packet.size = 0;
        bool videoDone = false, audioDone = x.ai < 0;
        try {
            while (av_read_frame(x.input, &packet) >= 0) {
                if (env->CallBooleanMethod(callback, cancelled) || env->ExceptionCheck()) throw std::runtime_error("Export cancelled");
                const bool video = packet.stream_index == x.vi;
                const bool audio = packet.stream_index == x.ai;
                if (video || audio) {
                    const int64_t packetPts = packet.pts == AV_NOPTS_VALUE ? packet.dts : packet.pts;
                    if (packetPts != AV_NOPTS_VALUE && av_rescale_q(packetPts, x.input->streams[packet.stream_index]->time_base, AV_TIME_BASE_Q) >= x.endUs + 1000000) {
                        if (video) videoDone = true; else audioDone = true;
                    } else {
                        auto decoder = video ? x.vd : x.ad;
                        const int sent = avcodec_send_packet(decoder, &packet);
                        if (sent == AVERROR(EAGAIN)) { consume(video); checked(avcodec_send_packet(decoder, &packet)); }
                        else checked(sent);
                        consume(video);
                    }
                }
                av_packet_unref(&packet);
                if (videoDone && audioDone) break;
            }
        } catch (...) { av_packet_unref(&packet); throw; }
        checked(avcodec_send_packet(x.vd, nullptr)); consume(true);
        if (x.ad) { checked(avcodec_send_packet(x.ad, nullptr)); consume(false); x.encodeAudio(true); x.send(x.ae, x.as, nullptr); }
        if (!frames) throw std::runtime_error("No video frames in selected range");
        x.send(x.ve, x.vs, nullptr);
        checked(av_write_trailer(x.output));
        return env->NewStringUTF("");
    } catch (const std::exception& error) {
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(error.what());
    }
}
