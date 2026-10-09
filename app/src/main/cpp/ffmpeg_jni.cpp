#include <jni.h>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/codec.h"
#include "libavformat/avformat.h"
#include "libavutil/audio_fifo.h"
#include "libavutil/channel_layout.h"
#include "libavutil/dict.h"
#include "libavutil/error.h"
#include "libavutil/frame.h"
#include "libavutil/mathematics.h"
#include "libavutil/opt.h"
#include "libavutil/samplefmt.h"
#include "libavfilter/avfilter.h"
#include "libavfilter/buffersink.h"
#include "libavfilter/buffersrc.h"
#include "libswresample/swresample.h"
}

// 本文件是 Android Kotlin 与 FFmpeg C API 之间的边界层。
// Kotlin 只传入普通文件路径和基础数值；这里负责资源创建/释放、解码、滤镜、重采样、
// FIFO 缓冲、编码、容器写入以及把 C++ 异常转换成 Java 异常。
namespace {

    // 将 FFmpeg 的负错误码转换为可读文本，所有 native 失败路径都尽量带上该信息。
    std::string ffmpegError(int error) {
        char buffer[AV_ERROR_MAX_STRING_SIZE] = {};
        av_strerror(error, buffer, sizeof(buffer));
        return std::string(buffer);
    }

    // 处理音频文件、编码器或滤镜失败：Java 侧收到 IOException。
    void throwException(JNIEnv *env, const std::string &message) {
        jclass exceptionClass = env->FindClass("java/io/IOException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, message.c_str());
            env->DeleteLocalRef(exceptionClass);
        }
    }

    // 处理 JNI 参数为空、区间无效等调用错误：Java 侧收到 IllegalArgumentException。
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
                case '\\':
                    result += "\\\\";
                    break;
                case '"':
                    result += "\\\"";
                    break;
                case '\n':
                    result += "\\n";
                    break;
                case '\r':
                    result += "\\r";
                    break;
                case '\t':
                    result += "\\t";
                    break;
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

    // 读取容器和最佳音频流的元数据，并以 JSON 字符串返回给 Kotlin。
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
            durationMs = av_rescale_q(format->duration, AVRational{1, AV_TIME_BASE},
                                      AVRational{1, 1000});
        } else if (stream->duration != AV_NOPTS_VALUE && stream->duration > 0) {
            durationMs = av_rescale_q(stream->duration, stream->time_base, AVRational{1, 1000});
        }

        std::string json = "{";
        json += "\"durationMs\":" + std::to_string(durationMs);
        json += ",\"format\":" +
                jsonString(format->iformat == nullptr ? nullptr : format->iformat->name);
        json += ",\"formatLong\":" +
                jsonString(format->iformat == nullptr ? nullptr : format->iformat->long_name);
        json += ",\"bitRate\":" +
                std::to_string(parameters->bit_rate > 0 ? parameters->bit_rate : format->bit_rate);
        json += ",\"codec\":" + jsonString(avcodec_get_name(parameters->codec_id));
        json += ",\"codecLong\":" +
                jsonString(descriptor == nullptr ? nullptr : descriptor->long_name);
        json += ",\"sampleRate\":" + std::to_string(parameters->sample_rate);
        json += ",\"channels\":" + std::to_string(parameters->ch_layout.nb_channels);
        json += ",\"channelLayout\":" + jsonString(layout.empty() ? nullptr : layout.c_str());
        json += ",\"sampleFmt\":" +
                jsonString(sampleFormat.empty() ? nullptr : sampleFormat.c_str());
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
        // 目标格式同时决定了输出容器和编码器。这里使用编码器名称，
        // 后续再由输出文件扩展名/格式名称创建对应的 AVFormatContext。
        if (format == "mp3") return "libmp3lame";
        if (format == "wav") return "pcm_s16le";
        if (format == "flac") return "flac";
        if (format == "ogg") return "libvorbis";
        if (format == "opus") return "libopus";
        return nullptr;
    }

    enum AVSampleFormat firstEncoderSampleFormat(const AVCodec *codec) {
        // 编码器可能只接受某些采样格式（例如 FLTP），不能直接假设
        // 输入解码后的格式可以被编码器使用。通过新版配置查询 API
        // 获取编码器支持的采样格式列表，并取列表中的第一个格式。
        const void *config = nullptr;
        const int result = avcodec_get_supported_config(
                nullptr,
                codec,
                AV_CODEC_CONFIG_SAMPLE_FORMAT,
                0,
                &config,
                nullptr);
        if (result >= 0 && config != nullptr) {
            const auto *formats = static_cast<const enum AVSampleFormat *>(config);
            if (*formats != AV_SAMPLE_FMT_NONE) return *formats;
        }
        return AV_SAMPLE_FMT_FLTP;
    }

    int encoderSampleRate(const AVCodec *codec, int inputRate, const std::string &format) {
        // 编码器的采样率约束与输入文件不一定相同。Opus 的规范采样率
        // 固定使用 48 kHz；其他编码器优先保留输入采样率，否则选择
        // 编码器支持列表中距离输入最近的采样率。
        if (format == "opus") return 48000;
        const void *config = nullptr;
        const int result = avcodec_get_supported_config(
                nullptr,
                codec,
                AV_CODEC_CONFIG_SAMPLE_RATE,
                0,
                &config,
                nullptr);
        // 返回 NULL 表示编码器没有限制采样率，可以直接沿用输入值。
        if (result < 0 || config == nullptr) return inputRate > 0 ? inputRate : 48000;
        const auto *sampleRates = static_cast<const int *>(config);
        if (inputRate > 0) {
            for (const int *rate = sampleRates; *rate != 0; ++rate) {
                if (*rate == inputRate) return inputRate;
            }
        }
        int closest = sampleRates[0];
        for (const int *rate = sampleRates; *rate != 0; ++rate) {
            if (inputRate <= 0 || std::abs(*rate - inputRate) < std::abs(closest - inputRate)) {
                closest = *rate;
            }
        }
        return closest;
    }

    void copyChannelLayout(AVChannelLayout *target, const AVChannelLayout *source, int channels) {
        if (source != nullptr && source->nb_channels > 0 &&
            av_channel_layout_copy(target, source) >= 0)
            return;
        av_channel_layout_default(target, channels > 0 ? channels : 2);
    }

    // 从编码器持续取出压缩包。一次 send_frame 可能产生多个 packet，不能只取一次。
    void writeEncodedPackets(AVCodecContext *encoder,
                             AVFormatContext *output,
                             AVStream *stream,
                             AVPacket *packet) {
        // 一个输入 AVFrame 可能产生 0 个、1 个或多个输出 AVPacket。
        // 因此每次 send_frame 后都必须循环 receive_packet，直到 EAGAIN/EOF。
        while (true) {
            int result = avcodec_receive_packet(encoder, packet);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return;
            if (result < 0) throw std::runtime_error("编码音频失败: " + ffmpegError(result));
            // 编码器和输出流的时间基可能不同。编码器时间基是
            // {1, sample_rate}，写入容器前必须换算到输出流时间基。
            av_packet_rescale_ts(packet, encoder->time_base, stream->time_base);
            packet->stream_index = stream->index;
            result = av_interleaved_write_frame(output, packet);
            av_packet_unref(packet);
            if (result < 0) throw std::runtime_error("写入输出文件失败: " + ffmpegError(result));
        }
    }

    // 把一帧已经准备好格式和时间戳的 PCM 交给编码器，然后写出全部 packet。
    void encodeFrame(AVCodecContext *encoder,
                     AVFormatContext *output,
                     AVStream *stream,
                     AVFrame *frame,
                     AVPacket *packet) {
        // FFmpeg 新 API 使用“发送输入、接收输出”的异步模型：
        // send_frame 只负责把原始音频帧交给编码器，真正的压缩数据
        // 通过 writeEncodedPackets 中的 receive_packet 取出。
        int result = avcodec_send_frame(encoder, frame);
        if (result < 0) throw std::runtime_error("提交编码帧失败: " + ffmpegError(result));
        writeEncodedPackets(encoder, output, stream, packet);
    }

    // 把一个解码器输出帧转换成编码器要求的格式，并追加到 FIFO。
    //
    // 这里不能直接调用 encodeFrame()，原因是解码器输出帧的 nb_samples
    // 由输入编码格式决定，而编码器通常要求固定的 frame_size。例如：
    //
    //     MP3 解码帧       -> 1152 samples
    //     FLAC 编码帧      -> 4096 samples
    //     Opus 编码帧      -> 960 samples（48 kHz、20 ms）
    //
    // 解码帧和编码帧不是一一对应的关系。FIFO 的作用就是暂存这些已经
    // 完成格式转换的 PCM 样本，等样本数达到编码器要求后再组装 AVFrame。
    void resampleFrameToFifo(SwrContext *resampler,
                             AVFrame *source,
                             AVCodecContext *decoder,
                             AVCodecContext *encoder,
                             AVAudioFifo *fifo) {
        // swr_get_delay() 是重采样器内部尚未输出的输入采样数。计算输出
        // 容量时必须把这部分 delay 算进去，否则重采样器可能没有足够的
        // 输出空间，尾部样本也可能无法在后续流程中取出。
        const int outputCapacity = static_cast<int>(av_rescale_rnd(
                swr_get_delay(resampler, decoder->sample_rate) + source->nb_samples,
                encoder->sample_rate,
                decoder->sample_rate,
                AV_ROUND_UP));
        if (outputCapacity <= 0) return;

        // converted 只是本次 swr_convert() 的临时输出缓冲区。写入 FIFO
        // 后即可释放，PCM 数据本身已经由 av_audio_fifo_write() 复制到 FIFO。
        AVFrame *converted = av_frame_alloc();
        if (converted == nullptr) {
            throw std::runtime_error("无法分配重采样音频帧");
        }

        // 重采样器的输入参数来自 decoder，输出参数来自 encoder。这里的
        // AVFrame 元数据必须和重采样器输出保持一致，否则 av_frame_get_buffer
        // 或后续 FIFO/编码器操作可能无法正确解释音频数据。
        converted->format = encoder->sample_fmt;
        converted->sample_rate = encoder->sample_rate;
        converted->nb_samples = outputCapacity;
        int result = av_channel_layout_copy(&converted->ch_layout, &encoder->ch_layout);
        if (result < 0) {
            av_frame_free(&converted);
            throw std::runtime_error("无法设置重采样后的声道布局: " + ffmpegError(result));
        }

        result = av_frame_get_buffer(converted, 0);
        if (result < 0) {
            av_frame_free(&converted);
            throw std::runtime_error("无法分配重采样缓冲区: " + ffmpegError(result));
        }

        // samples 表示本次实际生成的“每个声道的采样数”，不是字节数。
        // 对 planar 和 packed 采样格式，swr_convert() 会按照 format 填充
        // converted->data 中的对应声道/交错数据。
        const int samples = swr_convert(
                resampler,
                converted->data,
                outputCapacity,
                const_cast<const uint8_t **>(source->extended_data),
                source->nb_samples);
        if (samples < 0) {
            av_frame_free(&converted);
            throw std::runtime_error("音频重采样失败: " + ffmpegError(samples));
        }

        if (samples > 0) {
            // AVAudioFifo 的容量单位同样是每个声道的 sample 数。它会根据
            // sample_fmt 判断每个 sample 的字节数，并把 converted 中的数据
            // 追加到 FIFO 尾部；这里不会因为本次只产生了一个“不完整编码帧”
            // 就立即提交给编码器。
            result = av_audio_fifo_write(
                    fifo,
                    reinterpret_cast<void **>(converted->data),
                    samples);
            if (result < samples) {
                av_frame_free(&converted);
                throw std::runtime_error("向音频 FIFO 写入数据失败");
            }
        }
        av_frame_free(&converted);
    }

    // 输入解码结束后，SwrContext 内部可能仍缓存着重采样延迟样本。
    // 这些样本不属于新的 decoder AVFrame，必须使用 swr_convert(..., nullptr, 0)
    // 主动取出，并继续追加到 FIFO，否则输出文件末尾会缺少音频数据。
    void drainResamplerIntoFifo(SwrContext *resampler,
                                AVCodecContext *decoder,
                                AVCodecContext *encoder,
                                AVAudioFifo *fifo) {
        while (true) {
            const int64_t delay = swr_get_delay(resampler, decoder->sample_rate);
            // delay 归零表示重采样器已经完全排空。此时才可以进入 FIFO
            // 尾帧处理和编码器 flush 阶段。
            if (delay <= 0) return;

            const int capacity = static_cast<int>(av_rescale_rnd(
                    delay,
                    encoder->sample_rate,
                    decoder->sample_rate,
                    AV_ROUND_UP));
            if (capacity <= 0) return;

            AVFrame *tail = av_frame_alloc();
            if (tail == nullptr) {
                throw std::runtime_error("无法分配重采样尾帧");
            }
            tail->format = encoder->sample_fmt;
            tail->sample_rate = encoder->sample_rate;
            tail->nb_samples = capacity;

            int result = av_channel_layout_copy(&tail->ch_layout, &encoder->ch_layout);
            if (result >= 0) result = av_frame_get_buffer(tail, 0);
            if (result < 0) {
                av_frame_free(&tail);
                throw std::runtime_error("无法分配重采样尾帧缓冲区: " + ffmpegError(result));
            }

            // 输入指针为 nullptr、输入样本数为 0，表示“只读取重采样器
            // 内部缓存，不再提供新的输入样本”。一次调用可能仍无法取尽
            // 所有 delay，因此外层 while 会继续排空。
            const int samples = swr_convert(resampler, tail->data, capacity, nullptr, 0);
            if (samples < 0) {
                av_frame_free(&tail);
                throw std::runtime_error("排空重采样器失败: " + ffmpegError(samples));
            }
            if (samples == 0) {
                av_frame_free(&tail);
                return;
            }

            // 排出的样本和普通解码帧产生的样本一样，都必须先进入 FIFO；
            // 后续统一由 encodeAvailableFifo() 按 frame_size 组帧。
            result = av_audio_fifo_write(
                    fifo,
                    reinterpret_cast<void **>(tail->data),
                    samples);
            av_frame_free(&tail);
            if (result < samples) {
                throw std::runtime_error("向音频 FIFO 写入尾部数据失败");
            }
        }
    }

    // 尽可能从 FIFO 中取出编码帧并提交给编码器。
    //
    // 调用时机有两种：
    // 1. finalDrain == false：正在解码，只有 FIFO 中已经有完整帧时才编码，
    //    不足一个 frame_size 的样本留在 FIFO，等待下一个解码帧补齐。
    // 2. finalDrain == true：decoder 和 SwrContext 都已结束，FIFO 中的剩余
    //    样本必须全部处理；尾部不足一个完整帧时，根据编码器能力发送小帧
    //    或补静音到完整帧。
    void encodeAvailableFifo(AVAudioFifo *fifo,
                             AVCodecContext *encoder,
                             AVFormatContext *output,
                             AVStream *stream,
                             AVPacket *packet,
                             int64_t *nextPts,
                             bool finalDrain) {
        // VARIABLE_FRAME_SIZE 表示编码器允许每次接收不同的 nb_samples。
        // 没有这个能力时，普通帧必须严格使用 encoder->frame_size。
        const bool variableFrameSize =
                (encoder->codec->capabilities & AV_CODEC_CAP_VARIABLE_FRAME_SIZE) != 0;

        // SMALL_LAST_FRAME 表示编码器允许最后一帧小于固定 frame_size。
        // FLAC 通常支持该能力；不支持时，尾部必须补静音后再送入编码器。
        const bool smallLastFrame =
                (encoder->codec->capabilities & AV_CODEC_CAP_SMALL_LAST_FRAME) != 0;

        while (true) {
            // available 是 FIFO 中当前已有的每声道样本数，而不是字节数。
            const int available = av_audio_fifo_size(fifo);
            if (available <= 0) return;

            // 转码尚未结束时不能对不完整的 FIFO 数据补静音，因为后面还会
            // 有新的真实音频样本到来。此时直接返回，保留 FIFO 中的样本。
            if (!finalDrain && !variableFrameSize && available < encoder->frame_size) return;

            const int nominalFrameSize = encoder->frame_size > 0 ? encoder->frame_size : available;
            int frameSamples;
            if (variableFrameSize) {
                // 可变帧编码器可以直接使用当前可用样本；如果配置了一个
                // 正数 frame_size，则按该大小分批，避免一次积累过多数据。
                frameSamples = std::min(available, nominalFrameSize);
            } else if (finalDrain && available < nominalFrameSize && smallLastFrame) {
                // 允许小尾帧的编码器可以直接消费 FIFO 剩余的真实样本。
                frameSamples = available;
            } else {
                // 固定帧编码器的普通帧和不支持小尾帧的最后一帧，都必须
                // 分配完整 frame_size 的 AVFrame。尾部缺少的部分稍后补静音。
                frameSamples = nominalFrameSize;
            }
            if (frameSamples <= 0) return;

            AVFrame *frame = av_frame_alloc();
            if (frame == nullptr) throw std::runtime_error("无法分配编码音频帧");
            // 组装出的 AVFrame 必须完整描述编码器要接收的数据：采样格式、
            // 采样率、声道布局、每声道样本数以及时间戳。
            frame->format = encoder->sample_fmt;
            frame->sample_rate = encoder->sample_rate;
            frame->nb_samples = frameSamples;
            frame->pts = *nextPts;

            int result = av_channel_layout_copy(&frame->ch_layout, &encoder->ch_layout);
            if (result >= 0) result = av_frame_get_buffer(frame, 0);
            if (result < 0) {
                av_frame_free(&frame);
                throw std::runtime_error("无法分配编码帧缓冲区: " + ffmpegError(result));
            }

            // 只从 FIFO 读取真实存在的样本。对于需要补齐的尾帧，读取数会
            // 小于 frameSamples，剩余空间仍然保留在刚分配的 frame 中。
            const int samplesToRead = std::min(av_audio_fifo_size(fifo), frameSamples);
            result = av_audio_fifo_read(
                    fifo,
                    reinterpret_cast<void **>(frame->data),
                    samplesToRead);
            if (result < samplesToRead) {
                av_frame_free(&frame);
                throw std::runtime_error("从音频 FIFO 读取数据失败");
            }

            if (samplesToRead < frameSamples) {
                // 补静音只发生在最终尾帧。这样既满足固定帧大小要求，又不会
                // 把补出的静音计入 nextPts，避免输出时间戳比真实音频更长。
                av_samples_set_silence(
                        frame->data,
                        samplesToRead,
                        frameSamples - samplesToRead,
                        encoder->ch_layout.nb_channels,
                        encoder->sample_fmt);
            } else if (smallLastFrame && finalDrain) {
                // 对允许小尾帧的编码器，frameSamples 等于真实剩余样本数；
                // 明确设置 nb_samples，表达“这是一个没有补静音的小尾帧”。
                frame->nb_samples = samplesToRead;
            }

            // 编码器 time_base 为 {1, sample_rate}，所以 nextPts 的单位是
            // 每声道采样点。这里只推进真实读取的样本数，不推进补的静音数。
            *nextPts += samplesToRead;
            encodeFrame(encoder, output, stream, frame, packet);
            av_frame_free(&frame);
        }
    }

    void transcodeFile(const std::string &inputPath,
                       const std::string &outputPath,
                       const std::string &targetFormat) {
        // 整体转码管线：
        // 1. 打开输入容器并找到音频流；
        // 2. 用输入流参数初始化解码器，压缩数据 -> PCM/音频帧；
        // 3. 初始化目标编码器和输出容器；
        // 4. 用重采样器统一采样率、声道布局和采样格式；
        // 5. PCM 音频帧 -> 编码包，并写入输出文件；
        // 6. flush 解码器和编码器，写出尾部数据并释放资源。
        const char *codecName = codecForFormat(targetFormat);
        if (codecName == nullptr) throw std::runtime_error("不支持的目标格式: " + targetFormat);

        AVFormatContext *input = nullptr;
        AVCodecContext *decoder = nullptr;
        AVFormatContext *output = nullptr;
        AVCodecContext *encoder = nullptr;
        SwrContext *resampler = nullptr;
        AVAudioFifo *fifo = nullptr;
        AVPacket *packet = nullptr;
        AVFrame *decoded = nullptr;
        bool headerWritten = false;

        try {
            // ---------- 输入阶段：打开容器并读取流信息 ----------
            int result = avformat_open_input(&input, inputPath.c_str(), nullptr, nullptr);
            if (result < 0) throw std::runtime_error("无法打开输入文件: " + ffmpegError(result));
            result = avformat_find_stream_info(input, nullptr);
            if (result < 0)
                throw std::runtime_error("无法读取输入文件信息: " + ffmpegError(result));

            const int audioIndex = findAudioStream(input);
            if (audioIndex < 0) throw std::runtime_error("文件中没有音频流");
            AVStream *inputStream = input->streams[audioIndex];
            const AVCodec *decoderCodec = avcodec_find_decoder(inputStream->codecpar->codec_id);
            if (decoderCodec == nullptr) throw std::runtime_error("找不到输入音频解码器");
            decoder = avcodec_alloc_context3(decoderCodec);
            if (decoder == nullptr) throw std::runtime_error("无法分配解码器上下文");
            // codecpar 保存的是容器中的静态参数；复制到 AVCodecContext 后，
            // 解码器才拥有实际工作所需的完整配置。
            result = avcodec_parameters_to_context(decoder, inputStream->codecpar);
            if (result < 0) throw std::runtime_error("无法配置解码器: " + ffmpegError(result));
            result = avcodec_open2(decoder, decoderCodec, nullptr);
            if (result < 0) throw std::runtime_error("无法打开解码器: " + ffmpegError(result));

            // ---------- 输出阶段：选择编码器并创建输出容器 ----------
            const AVCodec *encoderCodec = avcodec_find_encoder_by_name(codecName);
            if (encoderCodec == nullptr)
                throw std::runtime_error("找不到目标编码器: " + std::string(codecName));
            result = avformat_alloc_output_context2(&output, nullptr, nullptr, outputPath.c_str());
            if (result < 0 || output == nullptr) {
                result = avformat_alloc_output_context2(
                        &output,
                        nullptr,
                        targetFormat == "opus" ? "opus" : targetFormat.c_str(),
                        outputPath.c_str());
            }
            if (result < 0 || output == nullptr)
                throw std::runtime_error("无法创建输出容器: " + ffmpegError(result));

            encoder = avcodec_alloc_context3(encoderCodec);
            if (encoder == nullptr) throw std::runtime_error("无法分配编码器上下文");
            // 编码器配置必须满足其能力限制。采样率、采样格式和声道布局
            // 将作为重采样器的目标参数。
            encoder->sample_rate = encoderSampleRate(encoderCodec, decoder->sample_rate,
                                                     targetFormat);
            encoder->sample_fmt =
                    targetFormat == "wav" ? AV_SAMPLE_FMT_S16 : firstEncoderSampleFormat(
                            encoderCodec);
            encoder->bit_rate = targetFormat == "mp3" ? 192000 : targetFormat == "opus" ? 96000 :
                                                                 targetFormat == "ogg" ? 128000 : 0;
            encoder->time_base = AVRational{1, encoder->sample_rate};
            copyChannelLayout(&encoder->ch_layout, &decoder->ch_layout,
                              decoder->ch_layout.nb_channels);
            if ((encoderCodec->capabilities & AV_CODEC_CAP_EXPERIMENTAL) != 0) {
                encoder->strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL;
            }
            result = avcodec_open2(encoder, encoderCodec, nullptr);
            if (result < 0) throw std::runtime_error("无法打开编码器: " + ffmpegError(result));

            // 输出流承载编码参数和时间戳；codecpar 由编码器上下文复制而来。
            AVStream *outputStream = avformat_new_stream(output, nullptr);
            if (outputStream == nullptr) throw std::runtime_error("无法创建输出音频流");
            outputStream->time_base = encoder->time_base;
            result = avcodec_parameters_from_context(outputStream->codecpar, encoder);
            if (result < 0)
                throw std::runtime_error("无法设置输出音频参数: " + ffmpegError(result));

            if ((output->oformat->flags & AVFMT_NOFILE) == 0) {
                result = avio_open(&output->pb, outputPath.c_str(), AVIO_FLAG_WRITE);
                if (result < 0)
                    throw std::runtime_error("无法创建输出文件: " + ffmpegError(result));
            }
            result = avformat_write_header(output, nullptr);
            if (result < 0) throw std::runtime_error("无法写入输出文件头: " + ffmpegError(result));
            headerWritten = true;

            // ---------- 格式适配阶段：创建音频重采样器 ----------
            // 解码器输出的 sample_fmt/sample_rate/ch_layout 可能和编码器
            // 要求不同。SwrContext 会完成采样格式转换、采样率转换以及
            // 必要的声道布局转换。
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
            if (result < 0 || resampler == nullptr)
                throw std::runtime_error("无法创建音频重采样器: " + ffmpegError(result));
            result = swr_init(resampler);
            if (result < 0)
                throw std::runtime_error("无法初始化音频重采样器: " + ffmpegError(result));

            // FIFO 初始容量以“每声道 sample 数”为单位，不是字节数。容量
            // 不够时 av_audio_fifo_write() 会自动扩容；这里使用至少 1024
            // samples，减少 MP3 解码帧较小时的频繁扩容。
            const int fifoInitialSize = std::max(encoder->frame_size, 1024);
            fifo = av_audio_fifo_alloc(
                    encoder->sample_fmt,
                    encoder->ch_layout.nb_channels,
                    fifoInitialSize);
            if (fifo == nullptr) throw std::runtime_error("无法创建音频 FIFO");

            packet = av_packet_alloc();
            decoded = av_frame_alloc();
            if (packet == nullptr || decoded == nullptr)
                throw std::runtime_error("无法分配音频处理缓冲区");

            int64_t nextPts = 0;
            auto convertAndEncode = [&](AVFrame *source) {
                // 每得到一个 decoder AVFrame，就执行两步：
                //
                //   decoder frame -> swr_convert -> FIFO
                //   FIFO 中的完整样本 -> encoder frame -> encoder
                //
                // encodeAvailableFifo(false) 只会取走完整编码帧，剩余样本
                // 会继续留在 FIFO 中，直到下一个解码帧到来。
                resampleFrameToFifo(resampler, source, decoder, encoder, fifo);
                encodeAvailableFifo(
                        fifo, encoder, output, outputStream, packet, &nextPts, false);
            };

            // ---------- 主循环：读取压缩包、解码、重采样并编码 ----------
            while ((result = av_read_frame(input, packet)) >= 0) {
                // 输入容器可能包含视频、字幕等其他流，只把目标音频流
                // 的 packet 送进解码器。
                if (packet->stream_index == audioIndex) {
                    // 一个 packet 不一定对应一个 frame，也可能暂时没有
                    // 可取帧，因此 send_packet 后要持续 receive_frame。
                    result = avcodec_send_packet(decoder, packet);
                    if (result < 0)
                        throw std::runtime_error("提交解码数据失败: " + ffmpegError(result));
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
            if (result != AVERROR_EOF)
                throw std::runtime_error("读取音频数据失败: " + ffmpegError(result));

            // ---------- flush 解码器 ----------
            // 读取完所有输入 packet 后发送 nullptr，通知解码器进入结束状态，
            // 取出它缓存的最后几个音频帧（某些编码格式存在帧重排序/缓存）。
            result = avcodec_send_packet(decoder, nullptr);
            if (result < 0) throw std::runtime_error("刷新解码器失败: " + ffmpegError(result));
            while ((result = avcodec_receive_frame(decoder, decoded)) >= 0) {
                convertAndEncode(decoded);
                av_frame_unref(decoded);
            }
            if (result != AVERROR(EAGAIN) && result != AVERROR_EOF)
                throw std::runtime_error("刷新解码器失败: " + ffmpegError(result));

            // 结束阶段的顺序不能颠倒：
            //
            //   1. decoder flush：取出解码器内部缓存的最后 AVFrame；
            //   2. swr flush：把重采样器内部 delay 排入 FIFO；
            //   3. FIFO drain：编码 FIFO 中的完整帧和最后尾帧；
            //   4. encoder flush：取出编码器内部缓存的最后 AVPacket；
            //   5. write trailer：完成容器索引和尾部元数据。
            //
            // 如果提前 flush encoder，FIFO 中尚未编码的样本就会被遗漏。
            drainResamplerIntoFifo(resampler, decoder, encoder, fifo);
            encodeAvailableFifo(
                    fifo, encoder, output, outputStream, packet, &nextPts, true);

            // ---------- flush 编码器并完成封装 ----------
            // 编码器也可能缓存输入帧。发送 nullptr 后继续取包，确保最后
            // 一个编码包被写入；随后写入容器尾部索引和元数据。
            result = avcodec_send_frame(encoder, nullptr);
            if (result < 0 && result != AVERROR_EOF)
                throw std::runtime_error("刷新编码器失败: " + ffmpegError(result));
            writeEncodedPackets(encoder, output, outputStream, packet);
            if (headerWritten) {
                result = av_write_trailer(output);
                if (result < 0)
                    throw std::runtime_error("无法写入输出文件尾: " + ffmpegError(result));
            }
        } catch (...) {
            av_frame_free(&decoded);
            av_packet_free(&packet);
            av_audio_fifo_free(fifo);
            swr_free(&resampler);
            avcodec_free_context(&encoder);
            if (output != nullptr && output->pb != nullptr &&
                (output->oformat->flags & AVFMT_NOFILE) == 0) {
                avio_closep(&output->pb);
            }
            if (output != nullptr) avformat_free_context(output);
            avcodec_free_context(&decoder);
            closeInput(&input);
            throw;
        }

        av_frame_free(&decoded);
        av_packet_free(&packet);
        av_audio_fifo_free(fifo);
        swr_free(&resampler);
        avcodec_free_context(&encoder);
        if (output != nullptr && output->pb != nullptr &&
            (output->oformat->flags & AVFMT_NOFILE) == 0) {
            avio_closep(&output->pb);
        }
        if (output != nullptr) avformat_free_context(output);
        avcodec_free_context(&decoder);
        closeInput(&input);
    }

    // 一个滤镜输入对应一个独立的容器、解码器和 abuffer。拼接时会有两个实例，
    // 裁剪/变速变调时只有一个实例。
    struct FilterInput {
        AVFormatContext *format = nullptr;
        AVCodecContext *decoder = nullptr;
        AVStream *stream = nullptr;
        int audioIndex = -1;
        AVFilterContext *source = nullptr;
        int64_t nextPts = 0;
        // atrim 可能在解码器读到输入文件 EOF 之前就关闭下游滤镜图。
        // source 返回 AVERROR_EOF 后，不能再向它写入后续解码帧。
        bool sourceEnded = false;
    };

    void closeFilterInput(FilterInput &input) {
        avcodec_free_context(&input.decoder);
        closeInput(&input.format);
        input.stream = nullptr;
        input.source = nullptr;
    }

    // 打开滤镜管线的输入文件并初始化解码器；此时还没有创建滤镜图。
    void openFilterInput(const std::string &path, FilterInput &input) {
        int result = avformat_open_input(&input.format, path.c_str(), nullptr, nullptr);
        if (result < 0) throw std::runtime_error("无法打开输入文件: " + ffmpegError(result));
        result = avformat_find_stream_info(input.format, nullptr);
        if (result < 0) throw std::runtime_error("无法读取输入文件信息: " + ffmpegError(result));

        input.audioIndex = findAudioStream(input.format);
        if (input.audioIndex < 0) throw std::runtime_error("文件中没有音频流");
        input.stream = input.format->streams[input.audioIndex];
        const AVCodec *codec = avcodec_find_decoder(input.stream->codecpar->codec_id);
        if (codec == nullptr) throw std::runtime_error("找不到输入音频解码器");
        input.decoder = avcodec_alloc_context3(codec);
        if (input.decoder == nullptr) throw std::runtime_error("无法分配解码器上下文");
        result = avcodec_parameters_to_context(input.decoder, input.stream->codecpar);
        if (result < 0) throw std::runtime_error("无法配置解码器: " + ffmpegError(result));
        result = avcodec_open2(input.decoder, codec, nullptr);
        if (result < 0) throw std::runtime_error("无法打开解码器: " + ffmpegError(result));
        if (input.decoder->sample_rate <= 0 || input.decoder->ch_layout.nb_channels <= 0) {
            throw std::runtime_error("输入音频参数无效");
        }
    }

    std::string inputChannelLayout(const FilterInput &input) {
        std::string layout = channelLayoutName(input.decoder->ch_layout);
        if (!layout.empty()) return layout;
        return input.decoder->ch_layout.nb_channels == 1 ? "mono" : "stereo";
    }

    // atempo 单次允许的倍率范围是 0.5～2.0。超出范围时拆成多个 atempo 串联。
    void appendAtempo(std::ostringstream &filters, double factor) {
        if (!std::isfinite(factor) || factor <= 0.0) {
            throw std::runtime_error("变速参数无效");
        }
        while (factor < 0.5) {
            filters << "atempo=0.5,";
            factor /= 0.5;
        }
        while (factor > 2.0) {
            filters << "atempo=2.0,";
            factor /= 2.0;
        }
        if (std::abs(factor - 1.0) > 0.000001) {
            filters << "atempo=" << std::fixed << std::setprecision(6) << factor << ",";
        }
    }

    // 所有编辑滤镜统一输出格式，保证 sink、FIFO、编码器对样本内存的解释一致。
    std::string fixedAudioFormat() {
        return "aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo";
    }

    // 对完整、无需处理的 MP3 执行字节级复制，保留原始编码数据和元数据。
    void copyBinaryFile(const std::string &inputPath, const std::string &outputPath) {
        if (inputPath == outputPath) {
            throw std::runtime_error("输入文件和输出文件不能相同");
        }
        std::ifstream input(inputPath, std::ios::binary);
        if (!input.is_open()) throw std::runtime_error("无法读取输入音频");
        std::ofstream output(outputPath, std::ios::binary | std::ios::trunc);
        if (!output.is_open()) throw std::runtime_error("无法创建输出音频");
        output << input.rdbuf();
        if (!output.good()) throw std::runtime_error("复制音频文件失败");
    }

    // 获取快速复制判断所需的输入时长，优先使用容器时长，再回退到流时长。
    int64_t filterInputDurationMs(const FilterInput &input) {
        if (input.format->duration != AV_NOPTS_VALUE && input.format->duration > 0) {
            return av_rescale_q(
                    input.format->duration,
                    AVRational{1, AV_TIME_BASE},
                    AVRational{1, 1000});
        }
        if (input.stream->duration != AV_NOPTS_VALUE && input.stream->duration > 0) {
            return av_rescale_q(
                    input.stream->duration,
                    input.stream->time_base,
                    AVRational{1, 1000});
        }
        return 0;
    }

    // 判断一次编辑是否等价于“完整 MP3 原样输出”，只有这种情况才允许绕过转码。
    bool canCopyWholeMp3(const FilterInput &input,
                         int64_t startMs,
                         int64_t endMs) {
        const bool isMp3 = input.format->iformat != nullptr &&
                           std::strcmp(input.format->iformat->name, "mp3") == 0;
        const int64_t durationMs = filterInputDurationMs(input);
        // 只有整段 MP3 且没有任何处理时才能直接复制，避免无意义的二次编码。
        return isMp3 && startMs == 0 && durationMs > 0 && endMs >= durationMs - 1;
    }

    // 生成单输入编辑滤镜：裁剪 -> 时间戳归零 -> 可选变调 -> 速度补偿 -> 固定格式。
    std::string buildSingleFilter(const FilterInput &input,
                                  int64_t startMs,
                                  int64_t endMs,
                                  double speed,
                                  double pitchSemitones) {
        const double start = static_cast<double>(startMs) / 1000.0;
        const double end = static_cast<double>(endMs) / 1000.0;
        const double pitchRatio = std::pow(2.0, pitchSemitones / 12.0);
        std::ostringstream filters;
        filters << "[in0]atrim=start=" << std::fixed << std::setprecision(6) << start
                << ":end=" << end << ",asetpts=PTS-STARTPTS,";
        if (std::abs(pitchRatio - 1.0) > 0.000001) {
            const int shiftedRate = std::max(
                    1000,
                    static_cast<int>(std::llround(input.decoder->sample_rate * pitchRatio)));
            // asetrate 调整音调，aresample 恢复输出采样率；后面的 atempo
            // 抵消由音调变化带来的速度变化，从而实现速度和音调独立控制。
            filters << "asetrate=" << shiftedRate << ",aresample=44100,";
        }
        appendAtempo(filters, speed / pitchRatio);
        filters << fixedAudioFormat() << "[out]";
        return filters.str();
    }

    // 创建 abuffer/abuffersink，并限制 sink 输出为 fltp、44100 Hz、stereo。
    void configureFilterSources(AVFilterGraph *graph,
                                std::vector<FilterInput> &inputs,
                                AVFilterContext *&sink) {
        const AVFilter *bufferFilter = avfilter_get_by_name("abuffer");
        const AVFilter *sinkFilter = avfilter_get_by_name("abuffersink");
        if (bufferFilter == nullptr || sinkFilter == nullptr) {
            throw std::runtime_error("FFmpeg 音频滤镜不可用");
        }

        for (size_t index = 0; index < inputs.size(); ++index) {
            const char *sampleFormat = av_get_sample_fmt_name(inputs[index].decoder->sample_fmt);
            if (sampleFormat == nullptr) throw std::runtime_error("输入采样格式无效");
            std::ostringstream args;
            args << "time_base=1/" << inputs[index].decoder->sample_rate
                 << ":sample_rate=" << inputs[index].decoder->sample_rate
                 << ":sample_fmt=" << sampleFormat
                 << ":channel_layout=" << inputChannelLayout(inputs[index]);
            const std::string name = "in" + std::to_string(index);
            int result = avfilter_graph_create_filter(
                    &inputs[index].source,
                    bufferFilter,
                    name.c_str(),
                    args.str().c_str(),
                    nullptr,
                    graph);
            if (result < 0) throw std::runtime_error("无法创建音频滤镜输入: " + ffmpegError(result));
        }

        int result = avfilter_graph_create_filter(
                &sink,
                sinkFilter,
                "out",
                nullptr,
                nullptr,
                graph);
        if (result < 0) throw std::runtime_error("无法创建音频滤镜输出: " + ffmpegError(result));

        const int sampleFormats[] = {AV_SAMPLE_FMT_FLTP, AV_SAMPLE_FMT_NONE};
        const int sampleRates[] = {44100, -1};
        result = av_opt_set_int_list(
                sink, "sample_fmts", sampleFormats, AV_SAMPLE_FMT_NONE, AV_OPT_SEARCH_CHILDREN);
        if (result < 0) throw std::runtime_error("无法设置滤镜采样格式: " + ffmpegError(result));
        result = av_opt_set_int_list(
                sink, "sample_rates", sampleRates, -1, AV_OPT_SEARCH_CHILDREN);
        if (result < 0) throw std::runtime_error("无法设置滤镜采样率: " + ffmpegError(result));
        result = av_opt_set(sink, "ch_layouts", "stereo", AV_OPT_SEARCH_CHILDREN);
        if (result < 0) throw std::runtime_error("无法设置滤镜声道布局: " + ffmpegError(result));
    }

    // atrim 这类有结束边界的滤镜可能早于解码器到达 EOF。
    // 这里将其视为当前滤镜输入的正常结束，而不是输入文件错误；调用方随后停止
    // 继续喂入解码帧，并排空 sink 中已经生成的选区数据。
    bool pushFrameToFilter(FilterInput &input, AVFrame *frame) {
        if (input.sourceEnded) return false;
        const int result = av_buffersrc_add_frame_flags(
                input.source, frame, AV_BUFFERSRC_FLAG_KEEP_REF);
        if (result == AVERROR_EOF) {
            input.sourceEnded = true;
            return false;
        }
        if (result < 0) {
            throw std::runtime_error("写入音频滤镜失败: " + ffmpegError(result));
        }
        return true;
    }

    // 从 sink 排出滤镜生成的音频帧，先写入 FIFO，再按 MP3 frame_size 组帧编码。
    void consumeFilteredFrames(AVFilterContext *sink,
                                AVAudioFifo *fifo,
                                AVCodecContext *encoder,
                                AVFormatContext *output,
                                AVStream *stream,
                                AVPacket *packet,
                                int64_t *nextPts,
                                bool untilEof) {
        AVFrame *filtered = av_frame_alloc();
        if (filtered == nullptr) throw std::runtime_error("无法分配滤镜输出帧");
        while (true) {
            const int result = av_buffersink_get_frame(sink, filtered);
            if (result == AVERROR(EAGAIN)) {
                if (untilEof) {
                    av_frame_free(&filtered);
                    throw std::runtime_error("滤镜输出未完成");
                }
                break;
            }
            if (result == AVERROR_EOF) break;
            if (result < 0) {
                av_frame_free(&filtered);
                throw std::runtime_error("读取滤镜输出失败: " + ffmpegError(result));
            }
            const int written = av_audio_fifo_write(
                    fifo,
                    reinterpret_cast<void **>(filtered->extended_data),
                    filtered->nb_samples);
            if (written < filtered->nb_samples) {
                av_frame_free(&filtered);
                throw std::runtime_error("写入滤镜 FIFO 失败");
            }
            encodeAvailableFifo(fifo, encoder, output, stream, packet, nextPts, false);
            av_frame_unref(filtered);
        }
        av_frame_free(&filtered);
        if (untilEof) {
            encodeAvailableFifo(fifo, encoder, output, stream, packet, nextPts, true);
        }
    }

    // 执行带音频滤镜的统一处理管线。filterDescription 使用 FFmpeg filtergraph 语法，
    // 输入端名称是 in0、in1，输出端名称是 out。
    void transcodeFiltered(const std::vector<std::string> &inputPaths,
                           const std::string &outputPath,
                           const std::string &filterDescription) {
        if (inputPaths.empty()) throw std::runtime_error("没有输入音频");

        std::vector<FilterInput> inputs(inputPaths.size());
        AVFilterGraph *graph = nullptr;
        AVFilterContext *sink = nullptr;
        AVFormatContext *output = nullptr;
        AVCodecContext *encoder = nullptr;
        AVAudioFifo *fifo = nullptr;
        AVPacket *packet = nullptr;
        AVFrame *decoded = nullptr;
        bool headerWritten = false;

        try {
            for (size_t i = 0; i < inputPaths.size(); ++i) {
                openFilterInput(inputPaths[i], inputs[i]);
            }

            const AVCodec *encoderCodec = avcodec_find_encoder_by_name("libmp3lame");
            if (encoderCodec == nullptr) throw std::runtime_error("找不到 MP3 编码器");
            int result = avformat_alloc_output_context2(
                    &output, nullptr, "mp3", outputPath.c_str());
            if (result < 0 || output == nullptr) {
                throw std::runtime_error("无法创建 MP3 输出容器: " + ffmpegError(result));
            }
            encoder = avcodec_alloc_context3(encoderCodec);
            if (encoder == nullptr) throw std::runtime_error("无法分配 MP3 编码器上下文");
            encoder->sample_rate = 44100;
            // 滤镜末端固定输出 fltp，编码器、FIFO 必须使用完全相同的采样格式。
            // 之前这里取编码器支持列表的第一个格式，libmp3lame 在当前构建中
            // 返回 s32p，导致 fltp 数据按 s32p 解释，最终产生严重滋滋声。
            encoder->sample_fmt = AV_SAMPLE_FMT_FLTP;
            int64_t outputBitRate = 0;
            for (const FilterInput &input : inputs) {
                const int64_t inputBitRate = input.decoder->bit_rate > 0
                        ? input.decoder->bit_rate
                        : input.format->bit_rate;
                if (inputBitRate > 0) outputBitRate = std::max(outputBitRate, inputBitRate);
            }
            if (outputBitRate <= 0) outputBitRate = 192000;
            // 单段转码尽量保持源 MP3 码率；多段拼接使用输入中的最高码率，避免
            // 拼接后被意外降到固定的 192 kbps。MP3 编码器支持的范围为 32～320 kbps。
            encoder->bit_rate = std::clamp<int64_t>(outputBitRate, 32000, 320000);
            encoder->time_base = AVRational{1, encoder->sample_rate};
            av_channel_layout_default(&encoder->ch_layout, 2);
            result = avcodec_open2(encoder, encoderCodec, nullptr);
            if (result < 0) throw std::runtime_error("无法打开 MP3 编码器: " + ffmpegError(result));

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

            graph = avfilter_graph_alloc();
            if (graph == nullptr) throw std::runtime_error("无法创建音频滤镜图");
            configureFilterSources(graph, inputs, sink);

            AVFilterInOut *graphInputs = avfilter_inout_alloc();
            AVFilterInOut *graphOutputs = nullptr;
            if (graphInputs == nullptr) throw std::runtime_error("无法分配滤镜连接");
            graphInputs->name = av_strdup("out");
            graphInputs->filter_ctx = sink;
            graphInputs->pad_idx = 0;
            for (int index = static_cast<int>(inputs.size()) - 1; index >= 0; --index) {
                AVFilterInOut *outputLink = avfilter_inout_alloc();
                if (outputLink == nullptr) {
                    avfilter_inout_free(&graphInputs);
                    throw std::runtime_error("无法分配滤镜输入连接");
                }
                outputLink->name = av_strdup(("in" + std::to_string(index)).c_str());
                outputLink->filter_ctx = inputs[index].source;
                outputLink->pad_idx = 0;
                outputLink->next = graphOutputs;
                graphOutputs = outputLink;
            }
            result = avfilter_graph_parse_ptr(
                    graph, filterDescription.c_str(), &graphInputs, &graphOutputs, nullptr);
            avfilter_inout_free(&graphInputs);
            avfilter_inout_free(&graphOutputs);
            if (result < 0) throw std::runtime_error("解析音频滤镜失败: " + ffmpegError(result));
            result = avfilter_graph_config(graph, nullptr);
            if (result < 0) throw std::runtime_error("配置音频滤镜失败: " + ffmpegError(result));

            fifo = av_audio_fifo_alloc(
                    encoder->sample_fmt,
                    encoder->ch_layout.nb_channels,
                    std::max(encoder->frame_size, 1024));
            packet = av_packet_alloc();
            decoded = av_frame_alloc();
            if (fifo == nullptr || packet == nullptr || decoded == nullptr) {
                throw std::runtime_error("无法分配音频处理缓冲区");
            }

            int64_t nextPts = 0;
            for (FilterInput &input : inputs) {
                avformat_seek_file(input.format, input.audioIndex, INT64_MIN, 0, INT64_MAX, 0);
                while (!input.sourceEnded && (result = av_read_frame(input.format, packet)) >= 0) {
                    if (packet->stream_index == input.audioIndex) {
                        result = avcodec_send_packet(input.decoder, packet);
                        if (result < 0) throw std::runtime_error("提交解码数据失败: " + ffmpegError(result));
                        while (!input.sourceEnded &&
                               (result = avcodec_receive_frame(input.decoder, decoded)) >= 0) {
                            int64_t timestamp = decoded->best_effort_timestamp;
                            if (timestamp == AV_NOPTS_VALUE) timestamp = decoded->pts;
                            if (timestamp != AV_NOPTS_VALUE) {
                                decoded->pts = av_rescale_q(
                                        timestamp,
                                        input.stream->time_base,
                                        AVRational{1, input.decoder->sample_rate});
                            } else {
                                decoded->pts = input.nextPts;
                            }
                            input.nextPts = decoded->pts + decoded->nb_samples;
                            if (!pushFrameToFilter(input, decoded)) {
                                av_frame_unref(decoded);
                                break;
                            }
                            consumeFilteredFrames(
                                    sink, fifo, encoder, output, outputStream, packet, &nextPts, false);
                            av_frame_unref(decoded);
                        }
                        if (!input.sourceEnded &&
                            result != AVERROR(EAGAIN) && result != AVERROR_EOF) {
                            throw std::runtime_error("解码音频失败: " + ffmpegError(result));
                        }
                    }
                    av_packet_unref(packet);
                }
                if (!input.sourceEnded && result != AVERROR_EOF) {
                    throw std::runtime_error("读取音频数据失败: " + ffmpegError(result));
                }
                if (!input.sourceEnded) {
                    result = avcodec_send_packet(input.decoder, nullptr);
                    if (result < 0) throw std::runtime_error("刷新解码器失败: " + ffmpegError(result));
                    while (!input.sourceEnded &&
                           (result = avcodec_receive_frame(input.decoder, decoded)) >= 0) {
                        decoded->pts = input.nextPts;
                        input.nextPts += decoded->nb_samples;
                        if (!pushFrameToFilter(input, decoded)) {
                            av_frame_unref(decoded);
                            break;
                        }
                        consumeFilteredFrames(
                                sink, fifo, encoder, output, outputStream, packet, &nextPts, false);
                        av_frame_unref(decoded);
                    }
                    if (!input.sourceEnded &&
                        result != AVERROR(EAGAIN) && result != AVERROR_EOF) {
                        throw std::runtime_error("刷新解码器失败: " + ffmpegError(result));
                    }
                }
                if (!input.sourceEnded) {
                    result = av_buffersrc_add_frame_flags(input.source, nullptr, 0);
                    if (result == AVERROR_EOF) {
                        input.sourceEnded = true;
                    } else if (result < 0) {
                        throw std::runtime_error("结束音频滤镜输入失败: " + ffmpegError(result));
                    }
                }
                consumeFilteredFrames(
                        sink, fifo, encoder, output, outputStream, packet, &nextPts, false);
            }

            consumeFilteredFrames(
                    sink, fifo, encoder, output, outputStream, packet, &nextPts, true);
            encodeAvailableFifo(fifo, encoder, output, outputStream, packet, &nextPts, true);
            result = avcodec_send_frame(encoder, nullptr);
            if (result < 0 && result != AVERROR_EOF) {
                throw std::runtime_error("刷新 MP3 编码器失败: " + ffmpegError(result));
            }
            writeEncodedPackets(encoder, output, outputStream, packet);
            if (headerWritten) {
                result = av_write_trailer(output);
                if (result < 0) throw std::runtime_error("无法写入输出文件尾: " + ffmpegError(result));
            }
        } catch (...) {
            av_frame_free(&decoded);
            av_packet_free(&packet);
            av_audio_fifo_free(fifo);
            avfilter_graph_free(&graph);
            avcodec_free_context(&encoder);
            if (output != nullptr && output->pb != nullptr &&
                (output->oformat->flags & AVFMT_NOFILE) == 0) {
                avio_closep(&output->pb);
            }
            if (output != nullptr) avformat_free_context(output);
            for (FilterInput &input : inputs) closeFilterInput(input);
            throw;
        }

        av_frame_free(&decoded);
        av_packet_free(&packet);
        av_audio_fifo_free(fifo);
        avfilter_graph_free(&graph);
        avcodec_free_context(&encoder);
        if (output != nullptr && output->pb != nullptr &&
            (output->oformat->flags & AVFMT_NOFILE) == 0) {
            avio_closep(&output->pb);
        }
        if (output != nullptr) avformat_free_context(output);
        for (FilterInput &input : inputs) closeFilterInput(input);
    }

    // 将一个 packed sample 转为 -1～1 附近的浮点值，供波形峰值统计使用。
    float sampleToFloat(const uint8_t *data, enum AVSampleFormat packedFormat) {
        switch (packedFormat) {
            case AV_SAMPLE_FMT_U8:
                return (static_cast<int>(*data) - 128) / 128.0f;
            case AV_SAMPLE_FMT_S16:
                return *reinterpret_cast<const int16_t *>(data) / 32768.0f;
            case AV_SAMPLE_FMT_S32:
                return static_cast<float>(*reinterpret_cast<const int32_t *>(data) / 2147483648.0);
            case AV_SAMPLE_FMT_S64:
                return static_cast<float>(*reinterpret_cast<const int64_t *>(data) / 9223372036854775808.0);
            case AV_SAMPLE_FMT_FLT:
                return *reinterpret_cast<const float *>(data);
            case AV_SAMPLE_FMT_DBL:
                return static_cast<float>(*reinterpret_cast<const double *>(data));
            default:
                return 0.0f;
        }
    }

    // 解码整段音频，按每个解码帧统计所有声道峰值，再压缩为 pointCount 个波形点。
    std::vector<float> waveformFile(const std::string &path, int pointCount) {
        FilterInput input;
        AVPacket *packet = nullptr;
        AVFrame *frame = nullptr;
        std::vector<float> framePeaks;
        try {
            openFilterInput(path, input);
            packet = av_packet_alloc();
            frame = av_frame_alloc();
            if (packet == nullptr || frame == nullptr) throw std::runtime_error("无法分配波形缓冲区");
            const enum AVSampleFormat packedFormat = av_get_packed_sample_fmt(input.decoder->sample_fmt);
            const int bytesPerSample = av_get_bytes_per_sample(packedFormat);
            const bool planar = av_sample_fmt_is_planar(input.decoder->sample_fmt) != 0;
            if (bytesPerSample <= 0) throw std::runtime_error("不支持的波形采样格式");

            auto collect = [&]() {
                float peak = 0.0f;
                const int channels = input.decoder->ch_layout.nb_channels;
                for (int sample = 0; sample < frame->nb_samples; ++sample) {
                    for (int channel = 0; channel < channels; ++channel) {
                        const uint8_t *data = planar
                                ? frame->extended_data[channel] + sample * bytesPerSample
                                : frame->extended_data[0] +
                                  (sample * channels + channel) * bytesPerSample;
                        peak = std::max(peak, std::abs(sampleToFloat(data, packedFormat)));
                    }
                }
                framePeaks.push_back(std::min(1.0f, peak));
            };

            int result;
            while ((result = av_read_frame(input.format, packet)) >= 0) {
                if (packet->stream_index == input.audioIndex) {
                    result = avcodec_send_packet(input.decoder, packet);
                    if (result < 0) throw std::runtime_error("提交波形解码数据失败: " + ffmpegError(result));
                    while ((result = avcodec_receive_frame(input.decoder, frame)) >= 0) {
                        collect();
                        av_frame_unref(frame);
                    }
                    if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) {
                        throw std::runtime_error("解码波形失败: " + ffmpegError(result));
                    }
                }
                av_packet_unref(packet);
            }
            if (result != AVERROR_EOF) throw std::runtime_error("读取波形失败: " + ffmpegError(result));
            result = avcodec_send_packet(input.decoder, nullptr);
            if (result < 0) throw std::runtime_error("刷新波形解码器失败: " + ffmpegError(result));
            while ((result = avcodec_receive_frame(input.decoder, frame)) >= 0) {
                collect();
                av_frame_unref(frame);
            }
            if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) {
                throw std::runtime_error("刷新波形失败: " + ffmpegError(result));
            }
        } catch (...) {
            av_frame_free(&frame);
            av_packet_free(&packet);
            closeFilterInput(input);
            throw;
        }
        av_frame_free(&frame);
        av_packet_free(&packet);
        closeFilterInput(input);

        std::vector<float> waveform(std::max(pointCount, 1), 0.0f);
        if (framePeaks.empty()) return waveform;
        for (size_t point = 0; point < waveform.size(); ++point) {
            const size_t begin = point * framePeaks.size() / waveform.size();
            const size_t end = std::max(begin + 1, (point + 1) * framePeaks.size() / waveform.size());
            for (size_t index = begin; index < std::min(end, framePeaks.size()); ++index) {
                waveform[point] = std::max(waveform[point], framePeaks[index]);
            }
        }
        return waveform;
    }

} // namespace

// 以下 JNI 函数是 FfmpegBridge.kt 中 external 方法的实际实现。
// 方法名遵循 JNI 静态命名规则：Java_<包名>_<对象名>_<方法名>。
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
    // probe 只返回元数据，不创建输出文件；失败时通过 JNI 抛回 IOException。
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
    // 通用转码入口：输入解码为 PCM，经 SwrContext 适配后交给目标编码器。
    if (inputPath == nullptr || outputPath == nullptr || targetFormat == nullptr) {
        throwArgument(env, "Conversion arguments must not be null");
        return;
    }
    try {
        transcodeFile(jstringValue(env, inputPath), jstringValue(env, outputPath),
                      jstringValue(env, targetFormat));
    } catch (const std::exception &error) {
        throwException(env, error.what());
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_bigjelly_temporun_player_FfmpegBridge_nativeWaveform(
        JNIEnv *env,
        jobject,
        jstring path,
        jint pointCount) {
    // JNI 数组只在成功路径创建；异常路径返回 nullptr，并保留 Java 异常状态。
    if (path == nullptr || pointCount <= 0) {
        throwArgument(env, "Invalid waveform arguments");
        return nullptr;
    }
    try {
        const std::vector<float> values = waveformFile(jstringValue(env, path), pointCount);
        jfloatArray result = env->NewFloatArray(static_cast<jsize>(values.size()));
        if (result == nullptr) return nullptr;
        env->SetFloatArrayRegion(
                result, 0, static_cast<jsize>(values.size()), values.data());
        return result;
    } catch (const std::exception &error) {
        throwException(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bigjelly_temporun_player_FfmpegBridge_nativeTrim(
        JNIEnv *env,
        jobject,
        jstring inputPath,
        jstring outputPath,
        jlong startMs,
        jlong endMs) {
    // 裁剪没有速度/变调参数，因此固定调用 speed=1、pitch=0 的单输入滤镜。
    // 完整 MP3 命中快速路径时会直接复制二进制文件，不重新编码。
    if (inputPath == nullptr || outputPath == nullptr || endMs <= startMs || startMs < 0) {
        throwArgument(env, "Invalid trim arguments");
        return;
    }
    try {
        const std::string input = jstringValue(env, inputPath);
        const std::string output = jstringValue(env, outputPath);
        FilterInput metadata;
        try {
            openFilterInput(input, metadata);
            if (canCopyWholeMp3(metadata, startMs, endMs)) {
                copyBinaryFile(input, output);
                closeFilterInput(metadata);
                return;
            }
            const std::string filter = buildSingleFilter(
                    metadata, startMs, endMs, 1.0, 0.0);
            closeFilterInput(metadata);
            transcodeFiltered({input}, output, filter);
        } catch (...) {
            closeFilterInput(metadata);
            throw;
        }
    } catch (const std::exception &error) {
        throwException(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bigjelly_temporun_player_FfmpegBridge_nativeTempoPitch(
        JNIEnv *env,
        jobject,
        jstring inputPath,
        jstring outputPath,
        jlong startMs,
        jlong endMs,
        jdouble speed,
        jdouble pitchSemitones) {
    // 速度和变调都在 native 层再次校验，不能只依赖 Kotlin UI 的 Slider 范围。
    if (inputPath == nullptr || outputPath == nullptr || endMs <= startMs ||
        startMs < 0 || !std::isfinite(speed) || speed <= 0.0 ||
        !std::isfinite(pitchSemitones)) {
        throwArgument(env, "Invalid tempo/pitch arguments");
        return;
    }
    try {
        const std::string input = jstringValue(env, inputPath);
        const std::string output = jstringValue(env, outputPath);
        FilterInput metadata;
        try {
            openFilterInput(input, metadata);
            if (std::abs(speed - 1.0) <= 0.000001 &&
                std::abs(pitchSemitones) <= 0.000001 &&
                canCopyWholeMp3(metadata, startMs, endMs)) {
                copyBinaryFile(input, output);
                closeFilterInput(metadata);
                return;
            }
            const std::string filter = buildSingleFilter(
                    metadata, startMs, endMs, speed, pitchSemitones);
            closeFilterInput(metadata);
            transcodeFiltered({input}, output, filter);
        } catch (...) {
            closeFilterInput(metadata);
            throw;
        }
    } catch (const std::exception &error) {
        throwException(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bigjelly_temporun_player_FfmpegBridge_nativeConcat(
        JNIEnv *env,
        jobject,
        jstring firstInputPath,
        jstring secondInputPath,
        jstring outputPath) {
    // 两个输入分别绑定到 in0/in1，滤镜 concat 按输入顺序生成一个连续音频流。
    if (firstInputPath == nullptr || secondInputPath == nullptr || outputPath == nullptr) {
        throwArgument(env, "Invalid concat arguments");
        return;
    }
    try {
        const std::string filter =
                "[in0]asetpts=PTS-STARTPTS[a0];"
                "[in1]asetpts=PTS-STARTPTS[a1];"
                "[a0][a1]concat=n=2:v=0:a=1,aresample=44100,"
                "aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[out]";
        transcodeFiltered(
                {jstringValue(env, firstInputPath), jstringValue(env, secondInputPath)},
                jstringValue(env, outputPath),
                filter);
    } catch (const std::exception &error) {
        throwException(env, error.what());
    }
}
