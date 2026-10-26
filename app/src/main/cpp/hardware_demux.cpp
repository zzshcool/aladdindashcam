#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <chrono>
#include <thread>
#include <vector>
#include <android/log.h>
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "HardwareDemux", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "HardwareDemux", __VA_ARGS__)
extern "C" {
#include "libavformat/avformat.h"
#include "libavcodec/avcodec.h"
#include "libavutil/channel_layout.h"
#include "libavutil/display.h"
#include "libswresample/swresample.h"
}
namespace {
void checkStep(const char* step, int result) {
    if (result >= 0) return;
    char text[AV_ERROR_MAX_STRING_SIZE]; av_strerror(result, text, sizeof(text));
    LOGE("%s failed: %d (%s)", step, result, text);
    throw std::runtime_error(std::string(step) + " failed: " + text);
}
void check(int result) { checkStep("Operation", result); }
struct Reader {
    AVFormatContext* input = nullptr;
    AVCodecContext* audio = nullptr;
    AVFrame* frame = av_frame_alloc();
    AVPacket* packet = av_packet_alloc();
    SwrContext* convert = nullptr;
    int videoIndex = -1, audioIndex = -1, nalLength = 0;
    bool eof = false;
    int64_t origin = 0;
    std::atomic<bool> interrupted{false};
    ~Reader() {
        swr_free(&convert); avcodec_free_context(&audio);
        av_frame_free(&frame); av_packet_free(&packet); avformat_close_input(&input);
    }
    int64_t time(int64_t value, int stream) {
        return value == AV_NOPTS_VALUE ? 0 : av_rescale_q(value, input->streams[stream]->time_base, AV_TIME_BASE_Q) - origin;
    }
};
static int interruptCallback(void* opaque) {
    auto* r = reinterpret_cast<Reader*>(opaque);
    if (!r) return 0;
    return r->interrupted.load(std::memory_order_relaxed) ? 1 : 0;
}
void failure(JNIEnv* env, const std::exception& error) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what()); }
jlongArray info(JNIEnv* env, std::initializer_list<jlong> values) {
    auto result = env->NewLongArray(values.size()); env->SetLongArrayRegion(result, 0, values.size(), values.begin()); return result;
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_aladdin_dashcam_media_FfmpegPacketReader_nativeOpen(JNIEnv* env, jobject, jstring path) {
    const char* name = env->GetStringUTFChars(path, nullptr);
    try {
        auto reader = std::make_unique<Reader>(); av_register_all();
        reader->input = avformat_alloc_context();
        if (!reader->input) throw std::bad_alloc();
        reader->input->interrupt_callback.callback = interruptCallback;
        reader->input->interrupt_callback.opaque = reader.get();
        AVDictionary* opts = nullptr;
        const bool isNetwork = std::strstr(name, "://") != nullptr;
        if (isNetwork) {
            av_dict_set(&opts, "stimeout", "5000000", 0);
            av_dict_set(&opts, "buffer_size", "4194304", 0);
            av_dict_set(&opts, "max_delay", "500000", 0);
            av_dict_set(&opts, "probesize", "524288", 0);
            av_dict_set(&opts, "analyzeduration", "1000000", 0);
        }
        int result = AVERROR_UNKNOWN;
        const int maxAttempts = isNetwork ? 10 : 1;
        for (int attempt = 0; attempt < maxAttempts && !reader->interrupted.load(std::memory_order_relaxed); ++attempt) {
            AVDictionary* openOpts = nullptr;
            if (opts) av_dict_copy(&openOpts, opts, 0);
            result = avformat_open_input(&reader->input, name, nullptr, &openOpts);
            av_dict_free(&openOpts);
            LOGD("avformat_open_input attempt=%d result=%d", attempt, result);
            if (result >= 0) break;
            if (attempt + 1 < maxAttempts) {
                avformat_close_input(&reader->input);
                reader->input = avformat_alloc_context();
                if (!reader->input) throw std::bad_alloc();
                reader->input->interrupt_callback.callback = interruptCallback;
                reader->input->interrupt_callback.opaque = reader.get();
                std::this_thread::sleep_for(std::chrono::milliseconds(200));
            }
        }
        av_dict_free(&opts);
        env->ReleaseStringUTFChars(path, name); name = nullptr; checkStep("avformat_open_input", result);
        checkStep("avformat_find_stream_info", avformat_find_stream_info(reader->input, nullptr));
        reader->videoIndex = av_find_best_stream(reader->input, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
        checkStep("av_find_best_stream_video", reader->videoIndex);
        auto video = reader->input->streams[reader->videoIndex]->codecpar;
        LOGD("Video stream info: codec_id=%d (%s) %dx%d", video->codec_id, avcodec_get_name(video->codec_id), video->width, video->height);
        if (video->codec_id != AV_CODEC_ID_H264 && video->codec_id != AV_CODEC_ID_HEVC) {
            char msg[128];
            snprintf(msg, sizeof(msg), "Hardware video format unsupported: %s (id=%d)", avcodec_get_name(video->codec_id), video->codec_id);
            throw std::runtime_error(msg);
        }
        if (video->extradata_size > 0 && video->extradata[0] == 1) {
            if (video->codec_id == AV_CODEC_ID_H264 && video->extradata_size >= 7) reader->nalLength = (video->extradata[4] & 3) + 1;
            if (video->codec_id == AV_CODEC_ID_HEVC && video->extradata_size >= 23) reader->nalLength = (video->extradata[21] & 3) + 1;
        }
        reader->origin = reader->input->start_time == AV_NOPTS_VALUE ? 0 : reader->input->start_time;
        reader->audioIndex = av_find_best_stream(reader->input, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        if (reader->audioIndex >= 0) {
            auto par = reader->input->streams[reader->audioIndex]->codecpar;
            auto codec = avcodec_find_decoder(par->codec_id);
            if (!codec) throw std::runtime_error("Audio decoder unavailable");
            reader->audio = avcodec_alloc_context3(codec);
            if (!reader->audio) throw std::bad_alloc();
            check(avcodec_parameters_to_context(reader->audio, par));
            check(avcodec_open2(reader->audio, codec, nullptr));
            if (reader->audio->channels < 1 || reader->audio->channels > 2 || reader->audio->sample_rate <= 0) throw std::runtime_error("Audio layout unsupported");
        }
        return reinterpret_cast<jlong>(reader.release());
    } catch (const std::exception& error) { if (name) env->ReleaseStringUTFChars(path, name); failure(env, error); return 0; }
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_aladdin_dashcam_media_FfmpegPacketReader_nativeInfo(JNIEnv* env, jobject, jlong handle) {
    auto r = reinterpret_cast<Reader*>(handle); auto v = r->input->streams[r->videoIndex]->codecpar;
    int rotation = 0;
    int size = 0; auto matrix = av_stream_get_side_data(r->input->streams[r->videoIndex], AV_PKT_DATA_DISPLAYMATRIX, &size);
    if (matrix && size >= 36) rotation = static_cast<int>(-av_display_rotation_get(reinterpret_cast<int32_t*>(matrix)));
    rotation = (rotation % 360 + 360) % 360;
    return info(env, {v->width, v->height, std::max<int64_t>(0, r->input->duration), v->codec_id == AV_CODEC_ID_H264 ? 0 : 1,
        r->audio ? r->audio->sample_rate : 0, r->audio ? r->audio->channels : 0, rotation});
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_aladdin_dashcam_media_FfmpegPacketReader_nativeConfig(JNIEnv* env, jobject, jlong handle) {
    auto r = reinterpret_cast<Reader*>(handle); auto v = r->input->streams[r->videoIndex]->codecpar;
    auto result = env->NewByteArray(v->extradata_size);
    if (v->extradata_size) env->SetByteArrayRegion(result, 0, v->extradata_size, reinterpret_cast<jbyte*>(v->extradata));
    return result;
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_aladdin_dashcam_media_FfmpegPacketReader_nativeRead(JNIEnv* env, jobject, jlong handle, jobject buffer) {
    auto r = reinterpret_cast<Reader*>(handle); auto destination = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const auto capacity = env->GetDirectBufferCapacity(buffer);
    try {
        if (!destination) throw std::runtime_error("Direct packet buffer required");
        for (;;) {
            if (r->audio) {
                const int result = avcodec_receive_frame(r->audio, r->frame);
                if (result >= 0) {
                    auto frame = r->frame;
                    if (!r->convert) {
                        const auto layout = frame->channel_layout ? frame->channel_layout : av_get_default_channel_layout(r->audio->channels);
                        r->convert = swr_alloc_set_opts(nullptr, av_get_default_channel_layout(r->audio->channels), AV_SAMPLE_FMT_S16,
                            r->audio->sample_rate, layout, static_cast<AVSampleFormat>(frame->format), frame->sample_rate, 0, nullptr);
                        if (!r->convert) throw std::bad_alloc(); check(swr_init(r->convert));
                    }
                    const int samples = frame->nb_samples;
                    if (static_cast<int64_t>(samples) * r->audio->channels * 2 > capacity) throw std::runtime_error("Audio packet too large");
                    const int written = swr_convert(r->convert, &destination, samples, const_cast<const uint8_t**>(frame->extended_data), samples); check(written);
                    const auto pts = r->time(frame->best_effort_timestamp, r->audioIndex); av_frame_unref(frame);
                    return info(env, {1, written * r->audio->channels * 2, pts, 0});
                }
                if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) check(result);
            }
            if (r->eof) return nullptr;
            av_packet_unref(r->packet);
            const int result = av_read_frame(r->input, r->packet);
            if (result == AVERROR_EOF) {
                r->eof = true;
                if (r->audio) { check(avcodec_send_packet(r->audio, nullptr)); continue; }
                return nullptr;
            }
            check(result);
            auto packet = r->packet;
            if (packet->stream_index == r->audioIndex) { check(avcodec_send_packet(r->audio, packet)); continue; }
            if (packet->stream_index != r->videoIndex) continue;
            int written = 0;
            if (r->nalLength) {
                int offset = 0;
                while (offset < packet->size) {
                    if (offset + r->nalLength > packet->size) throw std::runtime_error("Truncated NAL length");
                    uint32_t length = 0; for (int i = 0; i < r->nalLength; ++i) length = (length << 8) | packet->data[offset++];
                    if (length > static_cast<uint32_t>(packet->size - offset) || written + 4LL + length > capacity) throw std::runtime_error("Invalid NAL packet");
                    destination[written++] = 0; destination[written++] = 0; destination[written++] = 0; destination[written++] = 1;
                    std::memcpy(destination + written, packet->data + offset, length); written += length; offset += length;
                }
            } else {
                bool hasStartCode = (packet->size >= 3 && packet->data[0] == 0 && packet->data[1] == 0 && packet->data[2] == 1) ||
                                    (packet->size >= 4 && packet->data[0] == 0 && packet->data[1] == 0 && packet->data[2] == 0 && packet->data[3] == 1);
                if (!hasStartCode) {
                    if (written + 4LL + packet->size > capacity) throw std::runtime_error("Video packet too large");
                    destination[written++] = 0; destination[written++] = 0; destination[written++] = 0; destination[written++] = 1;
                    std::memcpy(destination + written, packet->data, packet->size);
                    written += packet->size;
                } else {
                    if (packet->size > capacity) throw std::runtime_error("Video packet too large");
                    std::memcpy(destination, packet->data, packet->size);
                    written = packet->size;
                }
            }
            return info(env, {0, written, r->time(packet->pts, r->videoIndex), packet->flags});
        }
    } catch (const std::exception& error) { failure(env, error); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_aladdin_dashcam_media_FfmpegPacketReader_nativeSeek(JNIEnv* env, jobject, jlong handle, jlong position) {
    auto r = reinterpret_cast<Reader*>(handle);
    try {
        check(av_seek_frame(r->input, -1, position + r->origin, AVSEEK_FLAG_BACKWARD));
        avformat_flush(r->input); if (r->audio) avcodec_flush_buffers(r->audio);
        swr_free(&r->convert); r->eof = false; av_packet_unref(r->packet); av_frame_unref(r->frame);
    } catch (const std::exception& error) { failure(env, error); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_aladdin_dashcam_media_FfmpegPacketReader_nativeInterrupt(JNIEnv*, jobject, jlong handle) {
    if (handle) {
        auto* r = reinterpret_cast<Reader*>(handle);
        r->interrupted.store(true, std::memory_order_relaxed);
    }
}
extern "C" JNIEXPORT void JNICALL
Java_com_aladdin_dashcam_media_FfmpegPacketReader_nativeClose(JNIEnv*, jobject, jlong handle) {
    if (handle) {
        auto* r = reinterpret_cast<Reader*>(handle);
        r->interrupted.store(true, std::memory_order_relaxed);
        delete r;
    }
}
