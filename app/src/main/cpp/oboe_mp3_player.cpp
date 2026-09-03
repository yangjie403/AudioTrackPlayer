// Native MP3 playback pipeline:
//
//   Kotlin file path / direct ByteBuffer
//       |
//       | JNI
//       v
//   dr_mp3: MP3 -> interleaved signed 16-bit PCM frames
//       |
//       v
//   Oboe AudioStream::write(): PCM -> Android audio output
//
// dr_mp3 是单头文件库。只需要在一个 C/C++ 翻译单元中定义
// DR_MP3_IMPLEMENTATION，就会同时编译出它的实现。

#include <jni.h>

#include <android/log.h>
#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <limits>
#include <memory>
#include <vector>

#define DR_MP3_IMPLEMENTATION

#include "dr_mp3.h"

namespace {

    constexpr char kLogTag[] = "OboeMp3Player";
    constexpr int64_t kWriteTimeoutNanos = 100'000'000; // 100 ms

// MP3 每个 frame 最多可产生 1152 个 PCM frame。按这个大小读取，可以控制内存占用，
// 同时减少 JNI/Oboe 调用次数。dr_mp3 的读取单位也是 PCM frame。
    constexpr drmp3_uint64 kDecodeChunkFrames = DRMP3_MAX_PCM_FRAMES_PER_MP3_FRAME;

    class OboeMp3Output final {
    public:
        ~OboeMp3Output() {
            // 释放顺序：停止 Oboe -> 关闭 Oboe stream -> 释放 dr_mp3 decoder。
            // 文件模式下，drmp3_uninit() 还会关闭 dr_mp3 内部持有的 FILE。
            stop();
            if (stream_) {
                stream_->close();
                stream_.reset();
            }
            if (decoderInitialized_) {
                drmp3_uninit(&decoder_);
                decoderInitialized_ = false;
            }
        }

        // 从文件路径初始化 dr_mp3。drmp3_init_file() 会在内部打开文件，并持续持有
        // 文件句柄，直到调用 drmp3_uninit()。
        bool initFromFile(const char *path) {
            if (path == nullptr || path[0] == '\0') return false;

            if (!drmp3_init_file(&decoder_, path, nullptr)) return false;
            decoderInitialized_ = true;
            return openOutput();
        }

        // 从内存初始化 dr_mp3。drmp3_init_memory() 不会复制 pData，因此这里必须复制
        // 到 memory_，保证 native decoder 在整个播放生命周期内访问稳定的内存。
        bool initFromMemory(const void *data, size_t size) {
            if (data == nullptr || size == 0) return false;

            const auto *bytes = static_cast<const drmp3_uint8 *>(data);
            memory_.assign(bytes, bytes + size);
            if (!drmp3_init_memory(&decoder_, memory_.data(), memory_.size(), nullptr)) {
                memory_.clear();
                return false;
            }
            decoderInitialized_ = true;
            return openOutput();
        }

        oboe::Result start() {
            if (!stream_) return oboe::Result::ErrorClosed;

            // requestStart() 将 stream 从“已打开”切换为“运行中”。只有运行中的 stream
            // 才可以通过 blocking write() 写入音频帧。
            stopped_.store(false, std::memory_order_release);
            return stream_->requestStart();
        }

        void stop() {
            // stop() 可能由 UI 线程调用，decodeAndPlay() 则在播放线程中运行。
            // atomic 标志既用于退出解码循环，也用于让重复 stop() 调用保持幂等。
            const bool wasStopped = stopped_.exchange(true, std::memory_order_acq_rel);
            if (wasStopped) return;

            // write() 是 blocking I/O 时，requestStop() 用来唤醒等待中的写操作。
            if (stream_) {
                const auto result = stream_->requestStop();
                if (result != oboe::Result::OK && result != oboe::Result::ErrorClosed) {
                    __android_log_print(
                            ANDROID_LOG_WARN,
                            kLogTag,
                            "requestStop failed: %s",
                            oboe::convertToText(result));
                }
            }
        }

        // 在当前调用线程里完成“读 MP3 -> 解码 PCM -> 写 Oboe”的循环。
        // dr_mp3 的拉取式 API 会自动读取并解析 MP3 帧，调用者只需要请求 PCM frame 数。
        oboe::Result decodeAndPlay() {
            if (!decoderInitialized_) return oboe::Result::ErrorInvalidFormat;
            if (!stream_) return oboe::Result::ErrorClosed;

            const size_t pcmSampleCount =
                    static_cast<size_t>(kDecodeChunkFrames) * decoder_.channels;
            std::vector<drmp3_int16> pcm(pcmSampleCount);

            while (!stopped_.load(std::memory_order_acquire)) {
                // 返回值是实际解码出的 PCM frame 数，范围是 [0, kDecodeChunkFrames]。
                // 一个 stereo frame 包含两个交错的 int16 sample：L、R。
                const drmp3_uint64 framesRead = drmp3_read_pcm_frames_s16(
                        &decoder_,
                        kDecodeChunkFrames,
                        pcm.data());

                // framesRead == 0 表示到达文件末尾，或当前输入已经没有可解码数据。
                if (framesRead == 0) return oboe::Result::OK;

                const auto result = writePcm(pcm.data(), framesRead);
                if (result != oboe::Result::OK) return result;
            }

            // 用户主动 stop 不是错误。
            return oboe::Result::OK;
        }

    private:
        bool openOutput() {
            // dr_mp3 在成功初始化后会填充 channels 和 sampleRate，它们就是解码后 PCM
            // 的格式，必须用同样的配置创建 Oboe 输出流。
            if (!decoderInitialized_ || decoder_.sampleRate == 0 || decoder_.channels == 0) {
                return false;
            }
            if (decoder_.channels > 2) {
                __android_log_print(
                        ANDROID_LOG_ERROR,
                        kLogTag,
                        "Unsupported channel count: %u",
                        decoder_.channels);
                return false;
            }

            sampleRate_ = static_cast<int32_t>(decoder_.sampleRate);
            channelCount_ = static_cast<int32_t>(decoder_.channels);

            // LowLatency 是性能请求而非保证。若设备不支持这组参数，再尝试默认性能模式。
            auto result = openWithPerformanceMode(oboe::PerformanceMode::LowLatency);
            if (result != oboe::Result::OK) {
                result = openWithPerformanceMode(oboe::PerformanceMode::None);
            }

            if (result == oboe::Result::OK) {
                __android_log_print(
                        ANDROID_LOG_INFO,
                        kLogTag,
                        "MP3 opened: %dHz/%dch, Oboe actual=%dHz/%dch",
                        sampleRate_,
                        channelCount_,
                        stream_->getSampleRate(),
                        stream_->getChannelCount());
            }
            return result == oboe::Result::OK;
        }

        oboe::Result openWithPerformanceMode(oboe::PerformanceMode performanceMode) {
            // 一个 AudioStream 打开后，采样率、声道数和 PCM 编码不能再修改，因此回退时
            // 必须重新创建 builder 和 stream，而不是修改已经打开的 stream。
            stream_.reset();

            oboe::AudioStreamBuilder builder;
            builder.setDirection(oboe::Direction::Output);
            builder.setSharingMode(oboe::SharingMode::Shared);
            builder.setPerformanceMode(performanceMode);
            builder.setFormat(oboe::AudioFormat::I16);
            builder.setSampleRate(sampleRate_);
            builder.setChannelCount(channelCount_);
            builder.setUsage(oboe::Usage::Media);
            builder.setContentType(oboe::ContentType::Music);

            return builder.openStream(stream_);
        }

        oboe::Result writePcm(const drmp3_int16 *data, drmp3_uint64 frameCount) {
            const int32_t bytesPerFrame =
                    channelCount_ * static_cast<int32_t>(sizeof(drmp3_int16));
            const auto maxFrames = static_cast<drmp3_uint64>(
                    std::numeric_limits<int32_t>::max() / bytesPerFrame);
            if (data == nullptr || frameCount == 0 || frameCount > maxFrames) {
                return oboe::Result::ErrorInvalidFormat;
            }

            int32_t framesRemaining = static_cast<int32_t>(frameCount);
            const auto *cursor = data;
            while (framesRemaining > 0 && !stopped_.load(std::memory_order_acquire)) {
                // Oboe write() 使用 frame 数作为第二个参数，而不是字节数；超时时间使用纳秒。
                auto result = stream_->write(cursor, framesRemaining, kWriteTimeoutNanos);
                if (!result) {
                    if (stopped_.load(std::memory_order_acquire)) return oboe::Result::OK;
                    __android_log_print(
                            ANDROID_LOG_ERROR,
                            kLogTag,
                            "Oboe write failed: %s",
                            oboe::convertToText(result.error()));
                    return result.error();
                }

                const int32_t framesWritten = result.value();
                if (framesWritten <= 0) {
                    if (stopped_.load(std::memory_order_acquire)) return oboe::Result::OK;
                    return oboe::Result::ErrorTimeout;
                }

                // 正常情况下 framesWritten <= framesRemaining。少数 Shared/重采样路径可能
                // 返回设备侧帧数；不能让它把输入指针推进到当前 PCM 缓冲区之外。
                const int32_t inputFramesConsumed =
                        framesWritten < framesRemaining ? framesWritten : framesRemaining;
                cursor += static_cast<size_t>(inputFramesConsumed) * channelCount_;
                framesRemaining -= inputFramesConsumed;
            }
            return oboe::Result::OK;
        }

        drmp3 decoder_{};
        bool decoderInitialized_ = false;
        std::vector<drmp3_uint8> memory_;

        int32_t sampleRate_ = 0;
        int32_t channelCount_ = 0;
        std::shared_ptr<oboe::AudioStream> stream_;
        std::atomic<bool> stopped_{true};
    };

    OboeMp3Output *fromHandle(jlong handle) {
        return reinterpret_cast<OboeMp3Output *>(handle);
    }

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_bigjelly_temporun_player_OboeMp3Player_nativeCreateFromFile(
        JNIEnv *env, jobject, jstring path) {
    if (path == nullptr) return 0;

    const char *pathChars = env->GetStringUTFChars(path, nullptr);
    if (pathChars == nullptr) return 0;

    auto *output = new OboeMp3Output();
    const bool initialized = output->initFromFile(pathChars);
    env->ReleaseStringUTFChars(path, pathChars);

    if (!initialized) {
        delete output;
        return 0;
    }
    return reinterpret_cast<jlong>(output);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bigjelly_temporun_player_OboeMp3Player_nativeCreateFromMemory(
        JNIEnv *env, jobject, jobject buffer, jint size) {
    if (buffer == nullptr || size <= 0) return 0;

    // GetDirectBufferAddress() 只适用于 direct ByteBuffer；Kotlin 侧明确使用
    // ByteBuffer.allocateDirect() 创建 assets 的输入缓冲区。
    auto *address = static_cast<const drmp3_uint8 *>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity < 0 || static_cast<jlong>(size) > capacity) return 0;

    auto *output = new OboeMp3Output();
    if (!output->initFromMemory(address, static_cast<size_t>(size))) {
        delete output;
        return 0;
    }
    return reinterpret_cast<jlong>(output);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bigjelly_temporun_player_OboeMp3Player_nativeStart(
        JNIEnv *, jobject, jlong handle) {
    auto *output = fromHandle(handle);
    if (!output) return static_cast<jint>(oboe::Result::ErrorClosed);
    return static_cast<jint>(output->start());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bigjelly_temporun_player_OboeMp3Player_nativeDecodeAndPlay(JNIEnv *, jobject,
                                                                    jlong handle) {
    auto *output = fromHandle(handle);
    if (!output) return static_cast<jint>(oboe::Result::ErrorClosed);
    return static_cast<jint>(output->decodeAndPlay());
}

extern "C" JNIEXPORT void JNICALL
Java_com_bigjelly_temporun_player_OboeMp3Player_nativeStop(JNIEnv *, jobject, jlong handle) {
    if (auto *output = fromHandle(handle)) output->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_bigjelly_temporun_player_OboeMp3Player_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    // Kotlin 侧会先 requestStop() 并等待播放线程结束，再调用 nativeDestroy()，避免
    // delete 与 decodeAndPlay() 并发访问 decoder 或 Oboe stream。
    delete fromHandle(handle);
}
