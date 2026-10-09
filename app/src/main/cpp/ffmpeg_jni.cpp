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
#include "libavutil/audio_fifo.h"
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
        transcodeFile(jstringValue(env, inputPath), jstringValue(env, outputPath),
                      jstringValue(env, targetFormat));
    } catch (const std::exception &error) {
        throwException(env, error.what());
    }
}
