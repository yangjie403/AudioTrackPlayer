#include <jni.h>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/codec.h"
#include "libavformat/avformat.h"
#include "libavutil/channel_layout.h"
#include "libavutil/dict.h"
#include "libavutil/error.h"
#include "libavutil/frame.h"
#include "libavutil/mathematics.h"
#include "libavutil/opt.h"
#include "libavutil/samplefmt.h"
#include "libswresample/swresample.h"
}

namespace {

std::string ffmpegError(int error) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(error, buffer, sizeof(buffer));
    return std::string(buffer);
}

void throwException(JNIEnv *env, const std::string &message) {
    jclass exceptionClass = env->FindClass("java/io/IOException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
        env->DeleteLocalRef(exceptionClass);
    }
}

void throwArgument(JNIEnv *env, const char *message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
        env->DeleteLocalRef(exceptionClass);
    }
}

std::string jstringValue(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string jsonEscape(const char *value) {
    if (value == nullptr) return {};
    std::string result;
    for (const unsigned char *p = reinterpret_cast<const unsigned char *>(value); *p; ++p) {
        switch (*p) {
            case '\\': result += "\\\\"; break;
            case '"': result += "\\\""; break;
            case '\n': result += "\\n"; break;
            case '\r': result += "\\r"; break;
            case '\t': result += "\\t"; break;
            default:
                if (*p < 0x20) {
                    char escaped[7];
                    std::snprintf(escaped, sizeof(escaped), "\\u%04x", *p);
                    result += escaped;
                } else {
                    result.push_back(static_cast<char>(*p));
                }
        }
    }
    return result;
}

std::string jsonString(const char *value) {
    return value == nullptr ? "null" : "\"" + jsonEscape(value) + "\"";
}

std::string channelLayoutName(const AVChannelLayout &layout) {
    if (layout.nb_channels <= 0) return {};
    char buffer[128] = {};
    if (av_channel_layout_describe(&layout, buffer, sizeof(buffer)) < 0) return {};
    return buffer;
}

std::string sampleFormatName(enum AVSampleFormat format) {
    const char *name = av_get_sample_fmt_name(format);
    return name == nullptr ? std::string() : std::string(name);
}

void closeInput(AVFormatContext **format) {
    if (format != nullptr && *format != nullptr) avformat_close_input(format);
}

int findAudioStream(AVFormatContext *format) {
    int stream = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (stream >= 0) return stream;
    for (unsigned int i = 0; i < format->nb_streams; ++i) {
        if (format->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            return static_cast<int>(i);
        }
    }
    return AVERROR_STREAM_NOT_FOUND;
}

std::string probeFile(const std::string &path) {
    AVFormatContext *format = nullptr;
    int result = avformat_open_input(&format, path.c_str(), nullptr, nullptr);
    if (result < 0) throw std::runtime_error("无法打开输入文件: " + ffmpegError(result));

    result = avformat_find_stream_info(format, nullptr);
    if (result < 0) {
        closeInput(&format);
        throw std::runtime_error("无法读取音频信息: " + ffmpegError(result));
    }

    const int audioIndex = findAudioStream(format);
    if (audioIndex < 0) {
        closeInput(&format);
        throw std::runtime_error("文件中没有音频流");
    }

    AVStream *stream = format->streams[audioIndex];
    AVCodecParameters *parameters = stream->codecpar;
    const AVCodecDescriptor *descriptor = avcodec_descriptor_get(parameters->codec_id);
    const std::string layout = channelLayoutName(parameters->ch_layout);
    const std::string sampleFormat = sampleFormatName(
            static_cast<enum AVSampleFormat>(parameters->format));
    int64_t durationMs = 0;
    if (format->duration != AV_NOPTS_VALUE && format->duration > 0) {
        durationMs = av_rescale_q(format->duration, AVRational{1, AV_TIME_BASE}, AVRational{1, 1000});
    } else if (stream->duration != AV_NOPTS_VALUE && stream->duration > 0) {
        durationMs = av_rescale_q(stream->duration, stream->time_base, AVRational{1, 1000});
    }

    std::string json = "{";
    json += "\"durationMs\":" + std::to_string(durationMs);
    json += ",\"format\":" + jsonString(format->iformat == nullptr ? nullptr : format->iformat->name);
    json += ",\"formatLong\":" + jsonString(format->iformat == nullptr ? nullptr : format->iformat->long_name);
    json += ",\"bitRate\":" + std::to_string(parameters->bit_rate > 0 ? parameters->bit_rate : format->bit_rate);
    json += ",\"codec\":" + jsonString(avcodec_get_name(parameters->codec_id));
    json += ",\"codecLong\":" + jsonString(descriptor == nullptr ? nullptr : descriptor->long_name);
    json += ",\"sampleRate\":" + std::to_string(parameters->sample_rate);
    json += ",\"channels\":" + std::to_string(parameters->ch_layout.nb_channels);
    json += ",\"channelLayout\":" + jsonString(layout.empty() ? nullptr : layout.c_str());
    json += ",\"sampleFmt\":" + jsonString(sampleFormat.empty() ? nullptr : sampleFormat.c_str());
    json += ",\"streams\":" + std::to_string(format->nb_streams);
    json += ",\"tags\":{";
    bool first = true;
    const AVDictionaryEntry *tag = nullptr;
    while ((tag = av_dict_iterate(stream->metadata, tag)) != nullptr) {
        if (!first) json += ",";
        first = false;
        json += jsonString(tag->key) + ":" + jsonString(tag->value);
    }
    json += "}}";
    closeInput(&format);
    return json;
}

const char *codecForFormat(const std::string &format) {
    if (format == "mp3") return "libmp3lame";
    if (format == "wav") return "pcm_s16le";
    if (format == "flac") return "flac";
    if (format == "ogg") return "libvorbis";
    if (format == "opus") return "libopus";
    return nullptr;
}

enum AVSampleFormat firstEncoderSampleFormat(const AVCodec *codec) {
    if (codec->sample_fmts == nullptr) return AV_SAMPLE_FMT_FLTP;
    for (const enum AVSampleFormat *format = codec->sample_fmts;
         *format != AV_SAMPLE_FMT_NONE;
         ++format) {
        return *format;
    }
    return AV_SAMPLE_FMT_FLTP;
}

int encoderSampleRate(const AVCodec *codec, int inputRate, const std::string &format) {
    if (format == "opus") return 48000;
    if (codec->supported_samplerates == nullptr) return inputRate > 0 ? inputRate : 48000;
    if (inputRate > 0) {
        for (const int *rate = codec->supported_samplerates; *rate != 0; ++rate) {
            if (*rate == inputRate) return inputRate;
        }
    }
    int closest = codec->supported_samplerates[0];
    for (const int *rate = codec->supported_samplerates; *rate != 0; ++rate) {
        if (inputRate <= 0 || std::abs(*rate - inputRate) < std::abs(closest - inputRate)) {
            closest = *rate;
        }
    }
    return closest;
}

void copyChannelLayout(AVChannelLayout *target, const AVChannelLayout *source, int channels) {
    if (source != nullptr && source->nb_channels > 0 && av_channel_layout_copy(target, source) >= 0) return;
    av_channel_layout_default(target, channels > 0 ? channels : 2);
}

void writeEncodedPackets(AVCodecContext *encoder,
                         AVFormatContext *output,
                         AVStream *stream,
                         AVPacket *packet) {
    while (true) {
        int result = avcodec_receive_packet(encoder, packet);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return;
        if (result < 0) throw std::runtime_error("编码音频失败: " + ffmpegError(result));
        av_packet_rescale_ts(packet, encoder->time_base, stream->time_base);
        packet->stream_index = stream->index;
        result = av_interleaved_write_frame(output, packet);
        av_packet_unref(packet);
        if (result < 0) throw std::runtime_error("写入输出文件失败: " + ffmpegError(result));
    }
}

void encodeFrame(AVCodecContext *encoder,
                 AVFormatContext *output,
                 AVStream *stream,
                 AVFrame *frame,
                 AVPacket *packet) {
    int result = avcodec_send_frame(encoder, frame);
    if (result < 0) throw std::runtime_error("提交编码帧失败: " + ffmpegError(result));
    writeEncodedPackets(encoder, output, stream, packet);
}

void transcodeFile(const std::string &inputPath,
                   const std::string &outputPath,
                   const std::string &targetFormat) {
    const char *codecName = codecForFormat(targetFormat);
    if (codecName == nullptr) throw std::runtime_error("不支持的目标格式: " + targetFormat);

    AVFormatContext *input = nullptr;
    AVCodecContext *decoder = nullptr;
    AVFormatContext *output = nullptr;
    AVCodecContext *encoder = nullptr;
    SwrContext *resampler = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *decoded = nullptr;
    AVFrame *converted = nullptr;
    bool headerWritten = false;

    try {
        int result = avformat_open_input(&input, inputPath.c_str(), nullptr, nullptr);
        if (result < 0) throw std::runtime_error("无法打开输入文件: " + ffmpegError(result));
        result = avformat_find_stream_info(input, nullptr);
        if (result < 0) throw std::runtime_error("无法读取输入文件信息: " + ffmpegError(result));

        const int audioIndex = findAudioStream(input);
        if (audioIndex < 0) throw std::runtime_error("文件中没有音频流");
        AVStream *inputStream = input->streams[audioIndex];
        const AVCodec *decoderCodec = avcodec_find_decoder(inputStream->codecpar->codec_id);
        if (decoderCodec == nullptr) throw std::runtime_error("找不到输入音频解码器");
        decoder = avcodec_alloc_context3(decoderCodec);
        if (decoder == nullptr) throw std::runtime_error("无法分配解码器上下文");
        result = avcodec_parameters_to_context(decoder, inputStream->codecpar);
        if (result < 0) throw std::runtime_error("无法配置解码器: " + ffmpegError(result));
        result = avcodec_open2(decoder, decoderCodec, nullptr);
        if (result < 0) throw std::runtime_error("无法打开解码器: " + ffmpegError(result));

        const AVCodec *encoderCodec = avcodec_find_encoder_by_name(codecName);
        if (encoderCodec == nullptr) throw std::runtime_error("找不到目标编码器: " + std::string(codecName));
        result = avformat_alloc_output_context2(&output, nullptr, nullptr, outputPath.c_str());
        if (result < 0 || output == nullptr) {
            result = avformat_alloc_output_context2(
                    &output,
                    nullptr,
                    targetFormat == "opus" ? "opus" : targetFormat.c_str(),
                    outputPath.c_str());
        }
        if (result < 0 || output == nullptr) throw std::runtime_error("无法创建输出容器: " + ffmpegError(result));

        encoder = avcodec_alloc_context3(encoderCodec);
        if (encoder == nullptr) throw std::runtime_error("无法分配编码器上下文");
        encoder->sample_rate = encoderSampleRate(encoderCodec, decoder->sample_rate, targetFormat);
        encoder->sample_fmt = targetFormat == "wav" ? AV_SAMPLE_FMT_S16 : firstEncoderSampleFormat(encoderCodec);
        encoder->bit_rate = targetFormat == "mp3" ? 192000 : targetFormat == "opus" ? 96000 : targetFormat == "ogg" ? 128000 : 0;
        encoder->time_base = AVRational{1, encoder->sample_rate};
        copyChannelLayout(&encoder->ch_layout, &decoder->ch_layout, decoder->ch_layout.nb_channels);
        if ((encoderCodec->capabilities & AV_CODEC_CAP_EXPERIMENTAL) != 0) {
            encoder->strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL;
        }
        result = avcodec_open2(encoder, encoderCodec, nullptr);
        if (result < 0) throw std::runtime_error("无法打开编码器: " + ffmpegError(result));

        AVStream *outputStream = avformat_new_stream(output, nullptr);
        if (outputStream == nullptr) throw std::runtime_error("无法创建输出音频流");
        outputStream->time_base = encoder->time_base;
        result = avcodec_parameters_from_context(outputStream->codecpar, encoder);
        if (result < 0) throw std::runtime_error("无法设置输出音频参数: " + ffmpegError(result));

        if ((output->oformat->flags & AVFMT_NOFILE) == 0) {
            result = avio_open(&output->pb, outputPath.c_str(), AVIO_FLAG_WRITE);
            if (result < 0) throw std::runtime_error("无法创建输出文件: " + ffmpegError(result));
        }
        result = avformat_write_header(output, nullptr);
        if (result < 0) throw std::runtime_error("无法写入输出文件头: " + ffmpegError(result));
        headerWritten = true;

        result = swr_alloc_set_opts2(
                &resampler,
                &encoder->ch_layout,
                encoder->sample_fmt,
                encoder->sample_rate,
                &decoder->ch_layout,
                decoder->sample_fmt,
                decoder->sample_rate,
                0,
                nullptr);
        if (result < 0 || resampler == nullptr) throw std::runtime_error("无法创建音频重采样器: " + ffmpegError(result));
        result = swr_init(resampler);
        if (result < 0) throw std::runtime_error("无法初始化音频重采样器: " + ffmpegError(result));

        packet = av_packet_alloc();
        decoded = av_frame_alloc();
        converted = av_frame_alloc();
        if (packet == nullptr || decoded == nullptr || converted == nullptr) throw std::runtime_error("无法分配音频处理缓冲区");

        int64_t nextPts = 0;
        auto convertAndEncode = [&](AVFrame *source) {
            av_frame_unref(converted);
            converted->format = encoder->sample_fmt;
            converted->sample_rate = encoder->sample_rate;
            result = av_channel_layout_copy(&converted->ch_layout, &encoder->ch_layout);
            if (result < 0) throw std::runtime_error("无法设置转换后的声道布局: " + ffmpegError(result));
            const int outputSamples = static_cast<int>(av_rescale_rnd(
                    swr_get_delay(resampler, decoder->sample_rate) + source->nb_samples,
                    encoder->sample_rate,
                    decoder->sample_rate,
                    AV_ROUND_UP));
            converted->nb_samples = outputSamples;
            result = av_frame_get_buffer(converted, 0);
            if (result < 0) throw std::runtime_error("无法分配转换后的音频帧: " + ffmpegError(result));
            const int samples = swr_convert(
                    resampler,
                    converted->data,
                    outputSamples,
                    const_cast<const uint8_t **>(source->extended_data),
                    source->nb_samples);
            if (samples < 0) throw std::runtime_error("音频重采样失败: " + ffmpegError(samples));
            converted->nb_samples = samples;
            converted->pts = nextPts;
            nextPts += samples;
            if (samples > 0) encodeFrame(encoder, output, outputStream, converted, packet);
        };

        while ((result = av_read_frame(input, packet)) >= 0) {
            if (packet->stream_index == audioIndex) {
                result = avcodec_send_packet(decoder, packet);
                if (result < 0) throw std::runtime_error("提交解码数据失败: " + ffmpegError(result));
                while ((result = avcodec_receive_frame(decoder, decoded)) >= 0) {
                    convertAndEncode(decoded);
                    av_frame_unref(decoded);
                }
                if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) {
                    throw std::runtime_error("解码音频失败: " + ffmpegError(result));
                }
            }
            av_packet_unref(packet);
        }
        if (result != AVERROR_EOF) throw std::runtime_error("读取音频数据失败: " + ffmpegError(result));

        result = avcodec_send_packet(decoder, nullptr);
        if (result < 0) throw std::runtime_error("刷新解码器失败: " + ffmpegError(result));
        while ((result = avcodec_receive_frame(decoder, decoded)) >= 0) {
            convertAndEncode(decoded);
            av_frame_unref(decoded);
        }
        if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) throw std::runtime_error("刷新解码器失败: " + ffmpegError(result));

        result = avcodec_send_frame(encoder, nullptr);
        if (result < 0 && result != AVERROR_EOF) throw std::runtime_error("刷新编码器失败: " + ffmpegError(result));
        writeEncodedPackets(encoder, output, outputStream, packet);
        if (headerWritten) {
            result = av_write_trailer(output);
            if (result < 0) throw std::runtime_error("无法写入输出文件尾: " + ffmpegError(result));
        }
    } catch (...) {
        av_frame_free(&converted);
        av_frame_free(&decoded);
        av_packet_free(&packet);
        swr_free(&resampler);
        avcodec_free_context(&encoder);
        if (output != nullptr && output->pb != nullptr && (output->oformat->flags & AVFMT_NOFILE) == 0) {
            avio_closep(&output->pb);
        }
        if (output != nullptr) avformat_free_context(output);
        avcodec_free_context(&decoder);
        closeInput(&input);
        throw;
    }

    av_frame_free(&converted);
    av_frame_free(&decoded);
    av_packet_free(&packet);
    swr_free(&resampler);
    avcodec_free_context(&encoder);
    if (output != nullptr && output->pb != nullptr && (output->oformat->flags & AVFMT_NOFILE) == 0) {
        avio_closep(&output->pb);
    }
    if (output != nullptr) avformat_free_context(output);
    avcodec_free_context(&decoder);
    closeInput(&input);
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_bigjelly_temporun_player_FfmpegBridge_nativeVersion(
        JNIEnv *env,
        jobject) {
    return env->NewStringUTF(av_version_info());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bigjelly_temporun_player_FfmpegBridge_nativeConfiguration(
        JNIEnv *env,
        jobject) {
    const char *configuration = avformat_configuration();
    return env->NewStringUTF(configuration == nullptr ? "" : configuration);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bigjelly_temporun_player_FfmpegBridge_nativeProbe(
        JNIEnv *env,
        jobject,
        jstring path) {
    if (path == nullptr) {
        throwArgument(env, "path must not be null");
        return nullptr;
    }
    try {
        const std::string value = probeFile(jstringValue(env, path));
        return env->NewStringUTF(value.c_str());
    } catch (const std::exception &error) {
        throwException(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bigjelly_temporun_player_FfmpegBridge_nativeConvert(
        JNIEnv *env,
        jobject,
        jstring inputPath,
        jstring outputPath,
        jstring targetFormat) {
    if (inputPath == nullptr || outputPath == nullptr || targetFormat == nullptr) {
        throwArgument(env, "Conversion arguments must not be null");
        return;
    }
    try {
        transcodeFile(jstringValue(env, inputPath), jstringValue(env, outputPath), jstringValue(env, targetFormat));
    } catch (const std::exception &error) {
        throwException(env, error.what());
    }
}
