# 在 AudioTrackPlayer 中使用 FFmpeg 实现音频格式转换

本文以当前项目 AudioTrackPlayer 为例，详细介绍 Android 中如何使用 FFmpeg 实现 MP3、WAV、FLAC、OGG、Opus 等音频格式转换。文档覆盖：

- Android SAF 文件选择与临时文件；
- Kotlin/JNI/C++ 调用链；
- FFmpeg 解复用、解码、重采样、FIFO、编码和封装；
- MP3 转 FLAC、OGG、Opus 报“提交编码帧失败”的原因；
- 可复现的关键实现代码；
- 时间戳、刷新、资源释放、元数据和测试方法。

项目使用 FFmpeg n7.1。相关文件：

```text
app/src/main/java/com/bigjelly/temporun/AudioConvertActivity.kt
app/src/main/java/com/bigjelly/temporun/player/FfmpegBridge.kt
app/src/main/cpp/ffmpeg_jni.cpp
app/src/main/cpp/CMakeLists.txt
app/src/main/cpp/ffmpeg/
```

## 1. 先理解“格式转换”是什么

音频文件包含三个层面的概念：

| 层次 | 作用 | 示例 |
| --- | --- | --- |
| 容器 | 保存数据包、时间戳、标签和文件结构 | WAV、Ogg、MP4 |
| 编码器 | 把 PCM 编码成压缩或无损数据 | MP3、FLAC、Vorbis、Opus |
| PCM | 解码后真正的音频采样 | s16、s32、fltp |

所以 MP3 转 FLAC 不是修改扩展名，而是执行下面的流水线：

```text
MP3 文件
  -> 解复用：读取压缩 AVPacket
  -> 解码：MP3 AVPacket -> PCM AVFrame
  -> 重采样：统一采样率、声道布局、采样格式
  -> FIFO：按目标编码器要求重新组织采样
  -> 编码：PCM AVFrame -> FLAC AVPacket
  -> 封装：把 FLAC AVPacket 写入 .flac 文件
```

几个重要结论：

1. AVPacket 是压缩后的数据包，AVFrame 是解码后的 PCM 帧。
2. 解码器输出帧的大小不一定等于编码器要求的帧大小。
3. FLAC 是无损编码，但不能恢复 MP3 已经丢失的音频信息。
4. 转换时既要处理编码器，也要处理容器和时间戳。
5. `AVAudioFifo` 不是额外的编码格式转换器，而是位于 `SwrContext` 和编码器之间的 PCM 采样缓存与重新分帧器。

## 2. 当前项目的整体架构

当前页面的调用链是：

```text
AudioConvertActivity
  -> FfmpegBridge.convert(inputPath, outputPath, format)
  -> nativeConvert()
  -> transcodeFile()
  -> FFmpeg
```

当前支持的目标格式和编码器映射在 ffmpeg_jni.cpp 中：

```cpp
const char *codecForFormat(const std::string &format) {
    if (format == "mp3")  return "libmp3lame";
    if (format == "wav")  return "pcm_s16le";
    if (format == "flac") return "flac";
    if (format == "ogg")  return "libvorbis";
    if (format == "opus") return "libopus";
    return nullptr;
}
```

对应关系不是简单的“扩展名就是编码器”：

| 目标 | 编码器 | 常见容器 |
| --- | --- | --- |
| MP3 | libmp3lame | MPEG 音频 |
| WAV | pcm_s16le | RIFF/WAV |
| FLAC | flac | FLAC |
| OGG | libvorbis | Ogg |
| Opus | libopus | Ogg/Opus |

## 3. Android 文件访问：为什么要使用临时文件

Android 的 ACTION_OPEN_DOCUMENT 通常返回 content URI，例如：

```text
content://com.android.providers.media.documents/document/audio%3A12345
```

FFmpeg 的 avformat_open_input() 和 avio_open() 使用普通路径，例如：

```text
/data/user/0/com.bigjelly.temporun/cache/input-1234.audio
```

FFmpeg 不会自动调用 Android 的 ContentResolver 读取 content URI，所以项目采用：

```text
ACTION_OPEN_DOCUMENT
  -> ContentResolver.openInputStream(uri)
  -> 复制到 cacheDir/input-*.audio
  -> 把绝对路径传给 JNI

ACTION_CREATE_DOCUMENT
  -> FFmpeg 写 cacheDir/converted-*.flac
  -> ContentResolver.openOutputStream(uri)
  -> 复制临时输出到用户选择的位置
```

这样做的优点：

- native 层只处理普通路径；
- 可以先完整转换，成功后再覆盖用户目标文件；
- 转换失败时可以删除不完整的临时输出；
- FFmpeg 可以按普通文件方式读取和写入。

### 3.1 复制输入文件

```kotlin
private fun copyInputToCache(uri: Uri): File {
    val file = File.createTempFile("input-", ".audio", cacheDir)

    contentResolver.openInputStream(uri)?.use { input ->
        file.outputStream().use { output ->
            input.copyTo(output)
        }
    } ?: error("无法打开输入文件")

    return file
}
```

使用 ACTION_OPEN_DOCUMENT 时，用户已经通过系统文件选择器授予 URI 访问权，一般不需要额外申请传统外部存储权限。

### 3.2 执行转换并保存输出

转换必须放在后台线程。项目使用 ExecutorService，也可以使用 Kotlin 协程的 Dispatchers.IO：

```kotlin
private fun convertTo(outputUri: Uri) {
    val source = inputTempFile
    if (source == null || !source.isFile) {
        statusText.text = "请先选择输入文件"
        return
    }

    val format = FORMAT_KEYS[formatSpinner.selectedItemPosition]
    convertButton.isEnabled = false
    progress.visibility = View.VISIBLE

    executor.execute {
        val outputTemp = File.createTempFile(
            "converted-",
            "." + extension(format),
            cacheDir,
        )

        try {
            FfmpegBridge.convert(
                source.absolutePath,
                outputTemp.absolutePath,
                format,
            )

            // FFmpeg 成功后，再写入用户选择的 URI。
            contentResolver.openOutputStream(outputUri, "w")?.use { output ->
                outputTemp.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("无法打开目标文件")

            runOnUiThread {
                progress.visibility = View.GONE
                convertButton.isEnabled = true
                statusText.text = "转换完成"
            }
        } catch (error: Throwable) {
            runOnUiThread {
                progress.visibility = View.GONE
                convertButton.isEnabled = true
                statusText.text = "转换失败：" +
                    (error.message ?: error.javaClass.simpleName)
            }
        } finally {
            outputTemp.delete()
        }
    }
}
```

## 4. Kotlin/JNI 桥接

### 4.1 Kotlin 接口

```kotlin
package com.bigjelly.temporun.player

object FfmpegBridge {
    init {
        System.loadLibrary("ffmpeg_jni")
    }

    private external fun nativeProbe(path: String): String

    private external fun nativeConvert(
        inputPath: String,
        outputPath: String,
        targetFormat: String,
    )

    fun convert(
        inputPath: String,
        outputPath: String,
        targetFormat: String,
    ) {
        require(inputPath.isNotBlank())
        require(outputPath.isNotBlank())
        require(targetFormat.isNotBlank())

        nativeConvert(inputPath, outputPath, targetFormat)
    }
}
```

因为 native 方法位于 com.bigjelly.temporun.player.FfmpegBridge，JNI 函数名必须是：

```text
Java_com_bigjelly_temporun_player_FfmpegBridge_nativeConvert
```

修改 Kotlin 包名、类名或 native 方法名时，C++ 函数名也要同步修改，否则运行时会出现 UnsatisfiedLinkError。

### 4.2 C++ JNI 入口

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_bigjelly_temporun_player_FfmpegBridge_nativeConvert(
        JNIEnv *env,
        jobject,
        jstring inputPath,
        jstring outputPath,
        jstring targetFormat) {
    if (inputPath == nullptr ||
        outputPath == nullptr ||
        targetFormat == nullptr) {
        throwArgument(env, "Conversion arguments must not be null");
        return;
    }

    try {
        transcodeFile(
                jstringValue(env, inputPath),
                jstringValue(env, outputPath),
                jstringValue(env, targetFormat));
    } catch (const std::exception &error) {
        throwException(env, error.what());
    }
}
```

C++ 异常不能直接穿过 JNI 边界，应转换成 Java 异常：

```cpp
void throwException(
        JNIEnv *env,
        const std::string &message) {
    jclass exceptionClass =
            env->FindClass("java/io/IOException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
        env->DeleteLocalRef(exceptionClass);
    }
}

std::string jstringValue(
        JNIEnv *env,
        jstring value) {
    if (value == nullptr) {
        return {};
    }

    const char *chars =
            env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
```

## 5. FFmpeg 中的核心对象

| 对象 | 作用 |
| --- | --- |
| AVFormatContext | 输入或输出容器 |
| AVStream | 容器中的一条音频、视频或字幕流 |
| AVCodecContext | 解码器或编码器上下文 |
| AVPacket | 压缩数据包 |
| AVFrame | 解码后的 PCM 音频帧 |
| SwrContext | 重采样、声道转换、采样格式转换 |
| AVAudioFifo | 按采样数缓存 PCM |
| AVDictionary | 元数据标签 |

必须牢记：

```text
AVPacket：压缩数据，来自 av_read_frame()
AVFrame：解码后的 PCM，来自 avcodec_receive_frame()
```

标准音频转码循环是：

```text
av_read_frame()
  -> avcodec_send_packet(decoder, packet)
  -> avcodec_receive_frame(decoder, frame)
  -> swr_convert()
  -> av_audio_fifo_write()
  -> av_audio_fifo_read()
  -> avcodec_send_frame(encoder, frame)
  -> avcodec_receive_packet(encoder, packet)
  -> av_interleaved_write_frame()
```

## 6. 打开输入文件和解码器

### 6.1 打开输入并查找音频流

```cpp
AVFormatContext *input = nullptr;

int result = avformat_open_input(
        &input,
        inputPath.c_str(),
        nullptr,
        nullptr);
if (result < 0) {
    throw std::runtime_error(
            "无法打开输入文件: " + ffmpegError(result));
}

result = avformat_find_stream_info(input, nullptr);
if (result < 0) {
    throw std::runtime_error(
            "无法读取输入文件信息: " + ffmpegError(result));
}

const int audioIndex = av_find_best_stream(
        input,
        AVMEDIA_TYPE_AUDIO,
        -1,
        -1,
        nullptr,
        0);
if (audioIndex < 0) {
    throw std::runtime_error("文件中没有音频流");
}

AVStream *inputStream = input->streams[audioIndex];
```

一个 MP4 或 MKV 可能有多条流，不能假设第 0 条就是音频。应该使用 av_find_best_stream()，或者遍历 codecpar->codec_type。

### 6.2 创建并打开解码器

```cpp
const AVCodec *decoderCodec =
        avcodec_find_decoder(
                inputStream->codecpar->codec_id);
if (decoderCodec == nullptr) {
    throw std::runtime_error("找不到输入音频解码器");
}

AVCodecContext *decoder =
        avcodec_alloc_context3(decoderCodec);
if (decoder == nullptr) {
    throw std::runtime_error("无法分配解码器上下文");
}

result = avcodec_parameters_to_context(
        decoder,
        inputStream->codecpar);
if (result < 0) {
    throw std::runtime_error(
            "无法配置解码器: " + ffmpegError(result));
}

result = avcodec_open2(
        decoder,
        decoderCodec,
        nullptr);
if (result < 0) {
    throw std::runtime_error(
            "无法打开解码器: " + ffmpegError(result));
}
```

MP3 解码器可能输出：

```text
sample_rate = 44100
ch_layout   = stereo
sample_fmt  = fltp
nb_samples  = 1152
```

fltp 表示 planar float，每个声道有独立的数据平面。目标编码器可能要求 s16、s32 或其他格式，因此需要重采样。

## 7. 创建输出容器和编码器

### 7.1 查找编码器和输出容器

```cpp
const AVCodec *encoderCodec =
        avcodec_find_encoder_by_name(codecName);
if (encoderCodec == nullptr) {
    throw std::runtime_error(
            "找不到目标编码器: " +
            std::string(codecName));
}

AVFormatContext *output = nullptr;
result = avformat_alloc_output_context2(
        &output,
        nullptr,
        nullptr,
        outputPath.c_str());
if (result < 0 || output == nullptr) {
    throw std::runtime_error(
            "无法创建输出容器: " + ffmpegError(result));
}

AVCodecContext *encoder =
        avcodec_alloc_context3(encoderCodec);
if (encoder == nullptr) {
    throw std::runtime_error(
            "无法分配编码器上下文");
}
```

如果输出文件没有正确扩展名，可以在 avformat_alloc_output_context2() 中显式传递容器名。生产代码还应校验目标格式和容器是否匹配。

### 7.2 配置编码器

编码器至少需要配置采样率、采样格式、声道布局和时间基。有损编码器还需要配置码率：

```cpp
enum AVSampleFormat firstEncoderSampleFormat(
        const AVCodec *codec) {
    if (codec->sample_fmts == nullptr) {
        return AV_SAMPLE_FMT_FLTP;
    }

    for (const enum AVSampleFormat *format =
                 codec->sample_fmts;
         *format != AV_SAMPLE_FMT_NONE;
         ++format) {
        return *format;
    }

    return AV_SAMPLE_FMT_FLTP;
}

encoder->sample_rate = decoder->sample_rate > 0
        ? decoder->sample_rate
        : 44100;
encoder->sample_fmt =
        firstEncoderSampleFormat(encoderCodec);
encoder->time_base =
        AVRational{1, encoder->sample_rate};

result = av_channel_layout_copy(
        &encoder->ch_layout,
        &decoder->ch_layout);
if (result < 0 || encoder->ch_layout.nb_channels <= 0) {
    av_channel_layout_uninit(&encoder->ch_layout);
    result = av_channel_layout_default(
            &encoder->ch_layout,
            2);
}
if (result < 0) {
    throw std::runtime_error(
            "无法设置编码器声道布局");
}

if (targetFormat == "mp3") {
    encoder->bit_rate = 192000;
} else if (targetFormat == "ogg") {
    encoder->bit_rate = 128000;
} else if (targetFormat == "opus") {
    encoder->bit_rate = 96000;
}

result = avcodec_open2(
        encoder,
        encoderCodec,
        nullptr);
if (result < 0) {
    throw std::runtime_error(
            "无法打开编码器: " + ffmpegError(result));
}
```

不能假设所有编码器都接受 fltp。应从 encoderCodec->sample_fmts 中选择实际支持的采样格式。

### 7.3 创建输出流并写文件头

```cpp
AVStream *outputStream =
        avformat_new_stream(output, nullptr);
if (outputStream == nullptr) {
    throw std::runtime_error(
            "无法创建输出音频流");
}

outputStream->time_base = encoder->time_base;

result = avcodec_parameters_from_context(
        outputStream->codecpar,
        encoder);
if (result < 0) {
    throw std::runtime_error(
            "无法设置输出音频参数: " +
            ffmpegError(result));
}

if ((output->oformat->flags & AVFMT_NOFILE) == 0) {
    result = avio_open(
            &output->pb,
            outputPath.c_str(),
            AVIO_FLAG_WRITE);
    if (result < 0) {
        throw std::runtime_error(
                "无法创建输出文件: " +
                ffmpegError(result));
    }
}

result = avformat_write_header(output, nullptr);
if (result < 0) {
    throw std::runtime_error(
            "无法写入输出文件头: " +
            ffmpegError(result));
}
```

avformat_write_header() 成功后，才可以向输出容器写编码包。

## 8. 使用 SwrContext 统一 PCM 参数

例如：

```text
解码输出：44100 Hz / stereo / fltp
编码输入：44100 Hz / stereo / s16
```

创建重采样器：

```cpp
SwrContext *resampler = nullptr;

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
if (result < 0 || resampler == nullptr) {
    throw std::runtime_error(
            "无法创建音频重采样器: " +
            ffmpegError(result));
}

result = swr_init(resampler);
if (result < 0) {
    throw std::runtime_error(
            "无法初始化音频重采样器: " +
            ffmpegError(result));
}
```

参数顺序是：

```text
输出声道布局、输出采样格式、输出采样率、
输入声道布局、输入采样格式、输入采样率
```

输入参数来自 decoder，输出参数来自 encoder，顺序不能写反。

## 9. MP3 转 FLAC、OGG、Opus 失败的根本原因

### 9.1 解码帧和编码帧大小不同

AVFrame 的 nb_samples 表示每个声道的采样数。编码器打开后会设置 encoder->frame_size。

对于不支持可变帧大小的编码器，FFmpeg 要求：

```text
普通编码帧：frame->nb_samples == encoder->frame_size
最后一帧：只有支持 AV_CODEC_CAP_SMALL_LAST_FRAME 时才允许更小
```

MP3 解码器经常输出 1152 个采样，而 FLAC 编码器可能要求 4096 个采样：

```text
MP3 解码帧：1152 samples
FLAC 编码帧：4096 samples
avcodec_send_frame() -> AVERROR(EINVAL)
界面显示：提交编码帧失败: Invalid argument
```

当前项目原始代码在每次 swr_convert() 后直接发送：

```cpp
converted->nb_samples = samples;
if (samples > 0) {
    encodeFrame(
            encoder,
            output,
            outputStream,
            converted,
            packet);
}
```

所以错误发生在“提交具体 PCM 帧”这一步，而不是找不到 FLAC 编码器。修复方法是在 decoder 和 encoder 之间加入 AVAudioFifo。

### 9.2 FIFO 的作用

```text
解码帧 A：1152 samples
解码帧 B：1152 samples
解码帧 C：1152 samples
                 |
                 v
FIFO：3456 samples
                 |
继续解码，直到 FIFO >= 4096
                 |
取出 4096 samples -> FLAC 编码器
```

解码帧和编码帧不能直接一一对应。

### 9.3 为什么 OGG 和 Opus 也需要同样的处理

FIFO 不是只为 FLAC 准备的特殊分支，而是所有“解码帧大小”和“编码器输入帧大小”可能不同的目标格式都需要的通用层。当前项目的目标编码器分别是：

| 目标格式 | 编码器 | FIFO 的作用 |
| --- | --- | --- |
| FLAC | `flac` | 将解码后的 PCM 组织成 FLAC 能接受的固定或允许的小尾帧 |
| OGG | `libvorbis` | 将解码后的 PCM 组织成 Vorbis 编码器要求的输入帧 |
| Opus | `libopus` | 将重采样到 48 kHz 后的 PCM 按 Opus 的编码帧要求组装 |

不能根据文件扩展名推断“这一种格式可以直接接收任意 `nb_samples`”。编码器打开后，实际应读取：

```cpp
encoder->frame_size
encoder->codec->capabilities
```

然后按以下规则处理：

1. 普通阶段只从 FIFO 取出完整的 `encoder->frame_size`；
2. 具有 `AV_CODEC_CAP_VARIABLE_FRAME_SIZE` 的编码器可以使用可变大小的帧；
3. 输入结束时，具有 `AV_CODEC_CAP_SMALL_LAST_FRAME` 的编码器可以接收剩余小帧；
4. 不支持小尾帧时，将尾部真实样本读出后补静音到完整 `frame_size`；
5. 只有真实从 FIFO 读取的样本才推进 `nextPts`，补出的静音不计入 PTS。

因此，修复后的数据流对三种目标格式都是一致的：

```text
MP3 AVFrame
  -> swr_convert（格式、采样率、声道布局转换）
  -> av_audio_fifo_write（追加每声道 samples）
  -> av_audio_fifo_size（判断是否达到编码帧大小）
  -> av_audio_fifo_read（取出一帧 PCM）
  -> avcodec_send_frame（提交符合要求的 AVFrame）
```

## 10. AVAudioFifo 的正确实现

新增头文件：

```cpp
#include <algorithm>

extern "C" {
#include "libavutil/audio_fifo.h"
#include "libavutil/samplefmt.h"
}
```

### 10.1 把重采样结果写入 FIFO

```cpp
void resampleFrameToFifo(
        SwrContext *resampler,
        AVFrame *source,
        AVCodecContext *decoder,
        AVCodecContext *encoder,
        AVAudioFifo *fifo) {
    const int outputCapacity =
            static_cast<int>(av_rescale_rnd(
                    swr_get_delay(
                            resampler,
                            decoder->sample_rate) +
                            source->nb_samples,
                    encoder->sample_rate,
                    decoder->sample_rate,
                    AV_ROUND_UP));

    if (outputCapacity <= 0) {
        return;
    }

    AVFrame *converted = av_frame_alloc();
    if (converted == nullptr) {
        throw std::runtime_error(
                "无法分配重采样音频帧");
    }

    converted->format = encoder->sample_fmt;
    converted->sample_rate = encoder->sample_rate;
    converted->nb_samples = outputCapacity;

    int result = av_channel_layout_copy(
            &converted->ch_layout,
            &encoder->ch_layout);
    if (result < 0) {
        av_frame_free(&converted);
        throw std::runtime_error(
                "无法设置重采样后的声道布局");
    }

    result = av_frame_get_buffer(converted, 0);
    if (result < 0) {
        av_frame_free(&converted);
        throw std::runtime_error(
                "无法分配重采样缓冲区: " +
                ffmpegError(result));
    }

    const int samples = swr_convert(
            resampler,
            converted->data,
            outputCapacity,
            const_cast<const uint8_t **>(
                    source->extended_data),
            source->nb_samples);
    if (samples < 0) {
        av_frame_free(&converted);
        throw std::runtime_error(
                "音频重采样失败: " +
                ffmpegError(samples));
    }

    if (samples > 0) {
        result = av_audio_fifo_write(
                fifo,
                reinterpret_cast<void **>(
                        converted->data),
                samples);
        if (result < samples) {
            av_frame_free(&converted);
            throw std::runtime_error(
                    "向音频 FIFO 写入数据失败");
        }
    }

    av_frame_free(&converted);
}
```

FIFO 的容量单位是每个声道的采样数，不是字节数。av_audio_fifo_write() 会根据 sample_fmt 正确处理 planar 和 packed 数据。

### 10.2 从 FIFO 取出编码器帧

```cpp
void encodeAvailableFifo(
        AVAudioFifo *fifo,
        AVCodecContext *encoder,
        AVFormatContext *output,
        AVStream *stream,
        AVPacket *packet,
        int64_t *nextPts,
        bool finalDrain) {
    const bool variableFrameSize =
            (encoder->codec->capabilities &
             AV_CODEC_CAP_VARIABLE_FRAME_SIZE) != 0;

    const bool smallLastFrame =
            (encoder->codec->capabilities &
             AV_CODEC_CAP_SMALL_LAST_FRAME) != 0;

    while (true) {
        const int available =
                av_audio_fifo_size(fifo);
        if (available <= 0) {
            return;
        }

        // 正常阶段只发送完整帧。
        if (!finalDrain &&
            !variableFrameSize &&
            available < encoder->frame_size) {
            return;
        }

        const int nominalFrameSize = encoder->frame_size > 0
                ? encoder->frame_size
                : available;
        int frameSamples = 0;

        if (variableFrameSize) {
            // 当前实现即使面对可变帧编码器，也按 nominalFrameSize 分批，
            // 避免一次从 FIFO 取出过大的音频帧。
            frameSamples = std::min(available, nominalFrameSize);
        } else if (finalDrain &&
                   available < nominalFrameSize &&
                   !smallLastFrame) {
            // 不支持小尾帧的编码器需要补齐。
            frameSamples = nominalFrameSize;
        } else {
            frameSamples = finalDrain
                    ? std::min(available, nominalFrameSize)
                    : nominalFrameSize;
        }

        if (frameSamples <= 0) {
            return;
        }

        AVFrame *frame = av_frame_alloc();
        if (frame == nullptr) {
            throw std::runtime_error(
                    "无法分配编码音频帧");
        }

        frame->format = encoder->sample_fmt;
        frame->sample_rate = encoder->sample_rate;
        frame->nb_samples = frameSamples;
        frame->pts = *nextPts;

        int result = av_channel_layout_copy(
                &frame->ch_layout,
                &encoder->ch_layout);
        if (result < 0) {
            av_frame_free(&frame);
            throw std::runtime_error(
                    "无法设置编码帧声道布局");
        }

        result = av_frame_get_buffer(frame, 0);
        if (result < 0) {
            av_frame_free(&frame);
            throw std::runtime_error(
                    "无法分配编码帧缓冲区: " +
                    ffmpegError(result));
        }

        const int availableBeforeRead =
                av_audio_fifo_size(fifo);
        const int samplesToRead =
                std::min(availableBeforeRead,
                         frameSamples);

        result = av_audio_fifo_read(
                fifo,
                reinterpret_cast<void **>(
                        frame->data),
                samplesToRead);
        if (result < samplesToRead) {
            av_frame_free(&frame);
            throw std::runtime_error(
                    "从音频 FIFO 读取数据失败");
        }

        if (samplesToRead < frameSamples) {
            // 仅为不支持小尾帧的编码器补静音。
            av_samples_set_silence(
                    frame->data,
                    samplesToRead,
                    frameSamples - samplesToRead,
                    encoder->ch_layout.nb_channels,
                    encoder->sample_fmt);
        } else if (smallLastFrame && finalDrain) {
            // 允许小尾帧的编码器只接收真实剩余样本。
            frame->nb_samples = samplesToRead;
        }

        *nextPts += samplesToRead;

        encodeFrame(
                encoder,
                output,
                stream,
                frame,
                packet);

        av_frame_free(&frame);
    }
}
```

FLAC 支持 AV_CODEC_CAP_SMALL_LAST_FRAME，因此最后一帧可以小于 frame_size，不需要为了凑整帧而补静音。

## 11. 编码并写入输出包

### 11.1 排空编码器输出

一次 avcodec_send_frame() 可能产生 0 个、1 个或多个输出包，必须循环调用 avcodec_receive_packet()：

```cpp
void writeEncodedPackets(
        AVCodecContext *encoder,
        AVFormatContext *output,
        AVStream *stream,
        AVPacket *packet) {
    while (true) {
        int result =
                avcodec_receive_packet(
                        encoder,
                        packet);

        if (result == AVERROR(EAGAIN) ||
            result == AVERROR_EOF) {
            return;
        }

        if (result < 0) {
            throw std::runtime_error(
                    "编码音频失败: " +
                    ffmpegError(result));
        }

        av_packet_rescale_ts(
                packet,
                encoder->time_base,
                stream->time_base);
        packet->stream_index = stream->index;

        result = av_interleaved_write_frame(
                output,
                packet);
        av_packet_unref(packet);

        if (result < 0) {
            throw std::runtime_error(
                    "写入输出文件失败: " +
                    ffmpegError(result));
        }
    }
}
```

EAGAIN 表示当前没有更多输出，不是编码失败。真正的负错误码才需要报告。

### 11.2 发送一帧并写包

```cpp
void encodeFrame(
        AVCodecContext *encoder,
        AVFormatContext *output,
        AVStream *stream,
        AVFrame *frame,
        AVPacket *packet) {
    int result = avcodec_send_frame(
            encoder,
            frame);
    if (result < 0) {
        throw std::runtime_error(
                "提交编码帧失败: " +
                ffmpegError(result));
    }

    writeEncodedPackets(
            encoder,
            output,
            stream,
            packet);
}
```

## 12. 排空重采样器

SwrContext 可能缓存输入末尾的少量样本。输入结束后必须使用空输入继续调用 swr_convert()：

```cpp
void drainResamplerIntoFifo(
        SwrContext *resampler,
        AVCodecContext *decoder,
        AVCodecContext *encoder,
        AVAudioFifo *fifo) {
    while (true) {
        const int64_t delay =
                swr_get_delay(
                        resampler,
                        decoder->sample_rate);
        if (delay <= 0) {
            return;
        }

        const int capacity =
                static_cast<int>(
                        av_rescale_rnd(
                                delay,
                                encoder->sample_rate,
                                decoder->sample_rate,
                                AV_ROUND_UP));
        if (capacity <= 0) {
            return;
        }

        AVFrame *frame = av_frame_alloc();
        if (frame == nullptr) {
            throw std::runtime_error(
                    "无法分配重采样尾帧");
        }

        frame->format = encoder->sample_fmt;
        frame->sample_rate = encoder->sample_rate;
        frame->nb_samples = capacity;

        int result = av_channel_layout_copy(
                &frame->ch_layout,
                &encoder->ch_layout);
        if (result >= 0) {
            result = av_frame_get_buffer(
                    frame,
                    0);
        }
        if (result < 0) {
            av_frame_free(&frame);
            throw std::runtime_error(
                    "无法分配重采样尾帧缓冲区");
        }

        const int samples = swr_convert(
                resampler,
                frame->data,
                capacity,
                nullptr,
                0);
        if (samples < 0) {
            av_frame_free(&frame);
            throw std::runtime_error(
                    "排空重采样器失败: " +
                    ffmpegError(samples));
        }

        if (samples == 0) {
            av_frame_free(&frame);
            return;
        }

        result = av_audio_fifo_write(
                fifo,
                reinterpret_cast<void **>(
                        frame->data),
                samples);
        av_frame_free(&frame);

        if (result < samples) {
            throw std::runtime_error(
                    "向音频 FIFO 写入尾部数据失败");
        }
    }
}
```

如果忽略这一步，采样率转换时输出尾部可能少几个采样。

## 13. 完整转码循环

### 13.1 创建 FIFO 和缓冲区

```cpp
const int initialFifoCapacity =
        std::max(encoder->frame_size, 1024);

AVAudioFifo *fifo = av_audio_fifo_alloc(
        encoder->sample_fmt,
        encoder->ch_layout.nb_channels,
        initialFifoCapacity);
if (fifo == nullptr) {
    throw std::runtime_error(
            "无法创建音频 FIFO");
}

AVPacket *packet = av_packet_alloc();
AVFrame *decoded = av_frame_alloc();

if (packet == nullptr || decoded == nullptr) {
    throw std::runtime_error(
            "无法分配音频处理缓冲区");
}

int64_t nextPts = 0;
```

### 13.2 读取、解码、重采样、编码

```cpp
while ((result = av_read_frame(
        input,
        packet)) >= 0) {
    if (packet->stream_index == audioIndex) {
        result = avcodec_send_packet(
                decoder,
                packet);
        if (result < 0) {
            throw std::runtime_error(
                    "提交解码数据失败: " +
                    ffmpegError(result));
        }

        while ((result =
                avcodec_receive_frame(
                        decoder,
                        decoded)) >= 0) {
            resampleFrameToFifo(
                    resampler,
                    decoded,
                    decoder,
                    encoder,
                    fifo);

            encodeAvailableFifo(
                    fifo,
                    encoder,
                    output,
                    outputStream,
                    packet,
                    &nextPts,
                    false);

            av_frame_unref(decoded);
        }

        if (result != AVERROR(EAGAIN) &&
            result != AVERROR_EOF) {
            throw std::runtime_error(
                    "解码音频失败: " +
                    ffmpegError(result));
        }
    }

    av_packet_unref(packet);
}

if (result != AVERROR_EOF) {
    throw std::runtime_error(
            "读取音频数据失败: " +
            ffmpegError(result));
}
```

即使是非音频包，也要调用 av_packet_unref()，避免内存增长。

### 13.3 刷新 decoder、SwrContext、FIFO 和 encoder

```cpp
// 1. 刷新解码器，取出内部剩余帧。
result = avcodec_send_packet(
        decoder,
        nullptr);
if (result < 0 && result != AVERROR_EOF) {
    throw std::runtime_error(
            "刷新解码器失败");
}

while ((result =
        avcodec_receive_frame(
                decoder,
                decoded)) >= 0) {
    resampleFrameToFifo(
            resampler,
            decoded,
            decoder,
            encoder,
            fifo);
    av_frame_unref(decoded);
}

// 2. 排空重采样器。
drainResamplerIntoFifo(
        resampler,
        decoder,
        encoder,
        fifo);

// 3. 编码 FIFO 的完整帧和最后小帧。
encodeAvailableFifo(
        fifo,
        encoder,
        output,
        outputStream,
        packet,
        &nextPts,
        true);

// 4. 刷新编码器并写完缓存包。
result = avcodec_send_frame(
        encoder,
        nullptr);
if (result < 0 && result != AVERROR_EOF) {
    throw std::runtime_error(
            "刷新编码器失败");
}

writeEncodedPackets(
        encoder,
        output,
        outputStream,
        packet);

// 5. 完成容器。
result = av_write_trailer(output);
if (result < 0) {
    throw std::runtime_error(
            "无法写入输出文件尾");
}
```

正确顺序是：

```text
刷新 decoder
  -> 刷新 SwrContext
  -> 编码 FIFO 中的完整帧
  -> 编码 FIFO 中的最后小帧
  -> 刷新 encoder
  -> 写 trailer
```

不能在 FIFO 还有数据时先刷新 encoder，否则剩余 PCM 不会被编码。

## 14. 主函数骨架

下面代码把各阶段连接起来。输入、输出、编码器和重采样器的初始化使用前面章节的代码：

```cpp
void transcodeFile(
        const std::string &inputPath,
        const std::string &outputPath,
        const std::string &targetFormat) {
    const char *codecName =
            codecForFormat(targetFormat);
    if (codecName == nullptr) {
        throw std::runtime_error(
                "不支持的目标格式: " + targetFormat);
    }

    AVFormatContext *input = nullptr;
    AVCodecContext *decoder = nullptr;
    AVFormatContext *output = nullptr;
    AVCodecContext *encoder = nullptr;
    SwrContext *resampler = nullptr;
    AVAudioFifo *fifo = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *decoded = nullptr;
    AVStream *outputStream = nullptr;
    int audioIndex = -1;
    int result = 0;

    try {
        // 1. avformat_open_input()
        // 2. avformat_find_stream_info()
        // 3. 查找 audioIndex
        // 4. 创建并打开 decoder
        // 5. 创建 output 和 encoder
        // 6. 创建 outputStream
        // 7. avio_open() 和 avformat_write_header()
        // 8. 创建并初始化 resampler

        fifo = av_audio_fifo_alloc(
                encoder->sample_fmt,
                encoder->ch_layout.nb_channels,
                std::max(encoder->frame_size, 1024));
        if (fifo == nullptr) {
            throw std::runtime_error(
                    "无法创建音频 FIFO");
        }

        packet = av_packet_alloc();
        decoded = av_frame_alloc();
        if (packet == nullptr || decoded == nullptr) {
            throw std::runtime_error(
                    "无法分配音频处理缓冲区");
        }

        int64_t nextPts = 0;

        while ((result = av_read_frame(
                input,
                packet)) >= 0) {
            if (packet->stream_index == audioIndex) {
                result = avcodec_send_packet(
                        decoder,
                        packet);
                if (result < 0) {
                    throw std::runtime_error(
                            "提交解码数据失败");
                }

                while ((result =
                        avcodec_receive_frame(
                                decoder,
                                decoded)) >= 0) {
                    resampleFrameToFifo(
                            resampler,
                            decoded,
                            decoder,
                            encoder,
                            fifo);

                    encodeAvailableFifo(
                            fifo,
                            encoder,
                            output,
                            outputStream,
                            packet,
                            &nextPts,
                            false);

                    av_frame_unref(decoded);
                }

                if (result != AVERROR(EAGAIN) &&
                    result != AVERROR_EOF) {
                    throw std::runtime_error(
                            "解码音频失败: " +
                            ffmpegError(result));
                }
            }

            av_packet_unref(packet);
        }

        if (result != AVERROR_EOF) {
            throw std::runtime_error(
                    "读取音频数据失败: " +
                    ffmpegError(result));
        }

        result = avcodec_send_packet(
                decoder,
                nullptr);
        if (result < 0 && result != AVERROR_EOF) {
            throw std::runtime_error(
                    "刷新解码器失败");
        }

        while ((result =
                avcodec_receive_frame(
                        decoder,
                        decoded)) >= 0) {
            resampleFrameToFifo(
                    resampler,
                    decoded,
                    decoder,
                    encoder,
                    fifo);
            av_frame_unref(decoded);
        }

        drainResamplerIntoFifo(
                resampler,
                decoder,
                encoder,
                fifo);

        encodeAvailableFifo(
                fifo,
                encoder,
                output,
                outputStream,
                packet,
                &nextPts,
                true);

        result = avcodec_send_frame(
                encoder,
                nullptr);
        if (result < 0 && result != AVERROR_EOF) {
            throw std::runtime_error(
                    "刷新编码器失败");
        }

        writeEncodedPackets(
                encoder,
                output,
                outputStream,
                packet);

        result = av_write_trailer(output);
        if (result < 0) {
            throw std::runtime_error(
                    "无法写入输出文件尾");
        }
    } catch (...) {
        av_frame_free(&decoded);
        av_packet_free(&packet);
        av_audio_fifo_free(fifo);
        swr_free(&resampler);
        avcodec_free_context(&encoder);
        avcodec_free_context(&decoder);

        if (output != nullptr &&
            output->pb != nullptr &&
            (output->oformat->flags & AVFMT_NOFILE) == 0) {
            avio_closep(&output->pb);
        }

        avformat_free_context(output);
        avformat_close_input(&input);
        throw;
    }

    av_frame_free(&decoded);
    av_packet_free(&packet);
    av_audio_fifo_free(fifo);
    swr_free(&resampler);
    avcodec_free_context(&encoder);
    avcodec_free_context(&decoder);

    if (output != nullptr &&
        output->pb != nullptr &&
        (output->oformat->flags & AVFMT_NOFILE) == 0) {
        avio_closep(&output->pb);
    }

    avformat_free_context(output);
    avformat_close_input(&input);
}
```

上面骨架中的初始化步骤必须补入第 6、7、8 节的代码。核心处理顺序不能改变。

## 15. 资源释放

建议的释放顺序：

```text
AVFrame / AVPacket
AVAudioFifo
SwrContext
encoder AVCodecContext
decoder AVCodecContext
output AVIOContext
output AVFormatContext
input AVFormatContext
```

常用释放代码：

```cpp
av_frame_free(&decoded);
av_packet_free(&packet);
av_audio_fifo_free(fifo);
swr_free(&resampler);
avcodec_free_context(&encoder);
avcodec_free_context(&decoder);

if (output != nullptr &&
    output->pb != nullptr &&
    (output->oformat->flags & AVFMT_NOFILE) == 0) {
    avio_closep(&output->pb);
}

avformat_free_context(output);
avformat_close_input(&input);
```

转换成功和异常失败都要释放资源，否则多次转换可能逐渐耗尽 native 内存或文件描述符。

## 16. CMake 和 FFmpeg 静态库

当前项目在 app/src/main/cpp/CMakeLists.txt 中创建 ffmpeg_jni，包含 FFmpeg 头文件并链接静态库：

```cmake
add_library(ffmpeg_jni SHARED ffmpeg_jni.cpp)

set(FFMPEG_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg)
set(FFMPEG_INCLUDE_DIR ${FFMPEG_ROOT}/include)
set(FFMPEG_LIB_DIR ${FFMPEG_ROOT}/static/${ANDROID_ABI})

target_include_directories(
        ffmpeg_jni
        PRIVATE
        ${FFMPEG_INCLUDE_DIR}
)

set(FFMPEG_STATIC_LIBS
        ${FFMPEG_LIB_DIR}/libavfilter.a
        ${FFMPEG_LIB_DIR}/libavformat.a
        ${FFMPEG_LIB_DIR}/libavcodec.a
        ${FFMPEG_LIB_DIR}/libswresample.a
        ${FFMPEG_LIB_DIR}/libswscale.a
        ${FFMPEG_LIB_DIR}/libavutil.a
        ${FFMPEG_LIB_DIR}/libvorbisenc.a
        ${FFMPEG_LIB_DIR}/libvorbis.a
        ${FFMPEG_LIB_DIR}/libopus.a
        ${FFMPEG_LIB_DIR}/libmp3lame.a
        ${FFMPEG_LIB_DIR}/libogg.a
)

target_link_libraries(
        ffmpeg_jni
        PRIVATE
        "-Wl,--start-group"
        ${FFMPEG_STATIC_LIBS}
        "-Wl,--end-group"
        ${log-lib}
        m
        z
        dl
        android
)
```

各库职责：

| 库 | 作用 |
| --- | --- |
| libavformat | 解复用、封装、文件头、时间戳、文件 IO |
| libavcodec | 音频解码器和编码器 |
| libavutil | AVFrame、声道布局、采样格式、FIFO |
| libswresample | 重采样、声道转换、采样格式转换 |
| libavfilter | FFmpeg 滤镜 |
| libmp3lame | MP3 编码器依赖 |
| libvorbis、libvorbisenc | Vorbis 编码器依赖 |
| libopus | Opus 编码器依赖 |
| libogg | Ogg 相关依赖 |

FLAC 编码器由 FFmpeg 的 libavcodec 提供，当前不需要额外链接 libFLAC。

Gradle ABI 配置为：

```gradle
android {
    defaultConfig {
        ndk {
            abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86_64'
        }
    }
}
```

每个 ABI 都必须存在对应的静态库，否则会在链接阶段失败或运行时找不到 native 库。

## 17. 时间基和 PTS

项目使用：

```cpp
encoder->time_base =
        AVRational{1, encoder->sample_rate};
```

因此：

```text
PTS = 已经编码的每声道采样数
时间（秒） = PTS / sample_rate
```

编码器产生的包和输出流可能使用不同时间基，写包前必须转换：

```cpp
av_packet_rescale_ts(
        packet,
        encoder->time_base,
        outputStream->time_base);
```

忘记转换可能导致输出时长错误、播放速度异常、时间戳不递增或播放器无法播放。

如果实现裁剪、拼接或音画同步，还要考虑输入 start_time、解码器延迟、编码器延迟和尾部 padding。简单整段转码可以使用递增的 nextPts。

## 18. 元数据和封面

简单转换主要处理音频内容，不会自动复制所有标签和封面。复制文本标签：

```cpp
const AVDictionaryEntry *tag = nullptr;

while ((tag = av_dict_iterate(
        inputStream->metadata,
        tag)) != nullptr) {
    av_dict_set(
            &outputStream->metadata,
            tag->key,
            tag->value,
            0);
}
```

容器级标签应从 input->metadata 复制到 output->metadata。封面通常是额外的视频流，不能只复制音频流的 metadata，需要单独处理。

## 19. 常见错误和定位方法

| 错误 | 常见原因 | 检查方向 |
| --- | --- | --- |
| 无法打开输入文件 | 路径错误、文件为空、临时复制失败 | 检查文件存在和长度 |
| 文件中没有音频流 | 输入不是音频或视频没有音轨 | 检查流列表 |
| 找不到目标编码器 | 构建时未包含对应编码器 | 检查 avcodec_find_encoder_by_name |
| 无法打开编码器 | 采样率、声道、采样格式不支持 | 检查 encoder 参数 |
| 提交编码帧失败: Invalid argument | nb_samples 不符合 frame_size，或帧参数不完整 | 检查 frame_size 和 frame |
| 音频重采样失败 | 输入/输出布局、采样率或格式无效 | 检查 swr_alloc_set_opts2 |
| 写入输出文件失败 | 路径不可写或磁盘空间不足 | 检查临时目录 |
| 输出无法播放 | 没写 trailer、时间基错误或容器不匹配 | 检查 av_write_trailer 和时间基 |
| 输出比输入短一点 | 未刷新 decoder 或 SwrContext | 补充 flush |
| 输出比输入长一点 | 末帧盲目补静音 | 只在必要时补齐 |

建议给送帧错误增加编码器参数：

```cpp
std::string describeEncoder(
        const AVCodecContext *encoder) {
    const char *sampleFmt =
            av_get_sample_fmt_name(
                    encoder->sample_fmt);

    char layout[128] = {};
    av_channel_layout_describe(
            &encoder->ch_layout,
            layout,
            sizeof(layout));

    return "codec=" +
           std::string(encoder->codec->name) +
           ", sample_rate=" +
           std::to_string(encoder->sample_rate) +
           ", sample_fmt=" +
           (sampleFmt != nullptr ? sampleFmt : "?") +
           ", channels=" +
           std::to_string(
                   encoder->ch_layout.nb_channels) +
           ", frame_size=" +
           std::to_string(encoder->frame_size) +
           ", layout=" + layout;
}
```

失败时可以报告：

```cpp
throw std::runtime_error(
        "提交编码帧失败: " +
        ffmpegError(result) +
        " (" +
        describeEncoder(encoder) +
        ", input_nb_samples=" +
        std::to_string(frame->nb_samples) +
        ")");
```

## 20. 从零复现 MP3 转 FLAC

### 第一步：准备 FFmpeg 文件

确认以下目录存在：

```text
app/src/main/cpp/ffmpeg/include/
app/src/main/cpp/ffmpeg/static/arm64-v8a/
app/src/main/cpp/ffmpeg/static/armeabi-v7a/
app/src/main/cpp/ffmpeg/static/x86_64/
```

### 第二步：加入 native 目标

在 CMake 中创建 ffmpeg_jni，加入 include 目录和静态库，并链接 log、m、z、dl、android。

### 第三步：实现 JNI

保持调用链：

```text
FfmpegBridge.nativeConvert()
  -> Java_com_bigjelly_temporun_player_FfmpegBridge_nativeConvert()
  -> transcodeFile()
```

### 第四步：实现数据循环

```text
av_read_frame
  -> avcodec_send_packet(decoder)
  -> avcodec_receive_frame(decoder)
  -> swr_convert
  -> av_audio_fifo_write
  -> 按 encoder->frame_size 取帧
  -> avcodec_send_frame(encoder)
  -> avcodec_receive_packet(encoder)
  -> av_interleaved_write_frame
```

### 第五步：正确刷新

```text
avcodec_send_packet(decoder, nullptr)
  -> 取出 decoder 剩余帧
  -> swr_convert(resampler, ..., nullptr, 0)
  -> drain FIFO
  -> avcodec_send_frame(encoder, nullptr)
  -> 取出 encoder 剩余包
  -> av_write_trailer(output)
```

### 第六步：验证结果

选择一个 MP3 文件并选择 FLAC 作为目标格式，确认：

1. probe() 能读取输入编码、采样率和声道；
2. 转换在后台线程执行；
3. 输出文件长度大于 0；
4. 输出 FLAC 能再次被 probe 和解码；
5. 输出时长与输入基本一致；
6. 转换失败时临时文件会被删除；
7. 不再出现第一帧的 Invalid argument。

## 21. 最终要记住的原则

1. 扩展名不等于编码格式，转换必须经过解码和重新编码。
2. AVPacket 是压缩数据，AVFrame 是 PCM 数据。
3. 输入解码帧大小和输出编码帧大小通常不同。
4. 使用 AVAudioFifo 按采样数缓存 PCM。
5. 普通帧要满足 encoder->frame_size，末帧要依据编码器能力处理。
6. 采样率、采样格式和声道布局必须同时配置。
7. SwrContext 的输入参数来自 decoder，输出参数来自 encoder。
8. avcodec_receive_packet 返回 EAGAIN 是正常状态。
9. 写包前使用 av_packet_rescale_ts 转换时间基。
10. 输入结束后依次刷新 decoder、SwrContext、FIFO 和 encoder。
11. Android content URI 先复制成临时文件，native 层只处理普通路径。
12. 转换放在后台线程，JNI 异常转换为 Java 异常。
13. FLAC 无法恢复 MP3 已经损失的音频信息。
