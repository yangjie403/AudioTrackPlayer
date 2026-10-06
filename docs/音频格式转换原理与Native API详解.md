# 音频格式转换原理、流程与 Native API 详解

本文以 AudioTrackPlayer 项目为背景，详细说明音频格式转换的原理、完整处理流程、Android 与 native 层之间的调用方式，以及项目当前支持的全部音频格式转换组合。

项目使用 FFmpeg n7.1。相关文件：

```kotlin
app/src/main/java/com/bigjelly/temporun/AudioConvertActivity.kt
app/src/main/java/com/bigjelly/temporun/player/FfmpegBridge.kt
app/src/main/cpp/ffmpeg_jni.cpp
app/src/main/cpp/CMakeLists.txt
app/src/main/cpp/ffmpeg/
```

---

## 1. 项目支持的格式范围

### 1.1 五种目标格式

AudioConvertActivity 当前提供五种目标格式：

```kotlin
private val FORMATS = listOf("MP3", "WAV", "FLAC", "OGG", "OPUS")
```

native 层的目标格式映射：

```cpp
const char *codecForFormat(const std::string &format) {
    if (format == "mp3") return "libmp3lame";
    if (format == "wav") return "pcm_s16le";
    if (format == "flac") return "flac";
    if (format == "ogg") return "libvorbis";
    if (format == "opus") return "libopus";
    return nullptr;
}
```

| 目标 key | 编码器 | 常见容器 | 扩展名 | 特性 |
| --- | --- | --- | --- | --- |
| mp3 | libmp3lame | MPEG Audio | .mp3 | 有损压缩 |
| wav | pcm_s16le | RIFF/WAV | .wav | 未压缩 PCM |
| flac | flac | FLAC | .flac | 无损压缩 |
| ogg | libvorbis | Ogg | .ogg | Ogg/Vorbis 有损压缩 |
| opus | libopus | Ogg/Opus | .opus | Opus 有损压缩 |

OGG 是容器，而不是唯一编码器。本项目的 OGG 目标具体表示 Ogg 容器中的 Vorbis 编码。

### 1.2 五种格式之间的全部转换组合

如果输入文件也属于上述五种格式，项目共有 25 种组合：

| 输入 \ 输出 | MP3 | WAV | FLAC | OGG | OPUS |
| --- | --- | --- | --- | --- | --- |
| MP3 | MP3→MP3 | MP3→WAV | MP3→FLAC | MP3→OGG | MP3→OPUS |
| WAV | WAV→MP3 | WAV→WAV | WAV→FLAC | WAV→OGG | WAV→OPUS |
| FLAC | FLAC→MP3 | FLAC→WAV | FLAC→FLAC | FLAC→OGG | FLAC→OPUS |
| OGG | OGG→MP3 | OGG→WAV | OGG→FLAC | OGG→OGG | OGG→OPUS |
| OPUS | OPUS→MP3 | OPUS→WAV | OPUS→FLAC | OPUS→OGG | OPUS→OPUS |

所有组合都经过相同的通用流水线：

```text
输入容器
  -> 解复用
  -> 输入解码器
  -> PCM
  -> 重采样、声道和采样格式转换
  -> FIFO 重新分帧
  -> 目标编码器
  -> 输出容器
```

输入选择器使用 audio/*，理论上还可以打开 FFmpeg 构建中包含的其他音频格式，例如 AAC、M4A、WMA 和 AIFF。项目明确提供的输出格式仍然只有上述五种。

### 1.3 同格式转换也会重新编码

当前 convert 是转码，不是直接复制：

- MP3→MP3 会再次进行有损编码；
- FLAC→FLAC 会重新生成 FLAC 编码数据；
- WAV→WAV 会按当前配置输出 pcm_s16le；
- OGG→OGG 会重新进行 Vorbis 编码；
- OPUS→OPUS 会重新进行 Opus 编码。

如果只想改变容器而不重新编码，应实现 stream copy，而不是使用当前的 PCM 转码流程。

---

## 2. 容器、编码器、PCM 和扩展名

### 2.1 容器

容器负责组织数据包、时间戳、流信息、标签、封面、文件头和文件尾。

常见容器包括：

- WAV；
- Ogg；
- MP4；
- Matroska；
- MPEG Audio。

容器与编码器不是一一对应关系。例如 Ogg 可以承载 Vorbis 或 Opus，MP4 可以承载 AAC 或 ALAC。

### 2.2 编码器和解码器

编码器把 PCM 转为压缩或无损编码数据：

```text
PCM -> MP3 encoder    -> MP3 packet
PCM -> FLAC encoder   -> FLAC packet
PCM -> Vorbis encoder -> Vorbis packet
PCM -> Opus encoder   -> Opus packet
```

解码器执行反方向操作：

```text
MP3 packet    -> MP3 decoder    -> PCM frame
FLAC packet   -> FLAC decoder   -> PCM frame
Vorbis packet -> Vorbis decoder -> PCM frame
```

### 2.3 PCM 的三个关键参数

| 参数 | 含义 | 示例 |
| --- | --- | --- |
| sample rate | 每秒采样数 | 44100、48000 |
| channel layout | 声道布局 | mono、stereo、5.1 |
| sample format | 样本存储方式 | s16、s32、fltp |

例如：

```text
44100 Hz / stereo / fltp
```

表示 44100 Hz、双声道、planar float PCM。

### 2.4 packed 和 planar

双声道 packed 数据：

```text
L0 R0 L1 R1 L2 R2 ...
```

planar 数据：

```text
左声道：L0 L1 L2 ...
右声道：R0 R1 R2 ...
```

常见采样格式：

| 格式 | 含义 |
| --- | --- |
| s16 | 交错的有符号 16 位整数 |
| s16p | 分平面的有符号 16 位整数 |
| fltp | 分平面的 32 位浮点数 |
| s32 | 交错的有符号 32 位整数 |
| s32p | 分平面的有符号 32 位整数 |

不要仅根据字节数手工复制 PCM，应使用 AVFrame、swr_convert 和 AVAudioFifo 处理数据平面。

---

## 3. 完整转换流程

一次音频格式转换的完整链路：

```cpp
Android content URI
  -> 应用缓存目录中的临时文件
  -> AVFormatContext 打开容器
  -> AVStream 选择音频流
  -> AVCodecContext 打开输入解码器
  -> AVPacket 读取压缩数据
  -> AVFrame 解码 PCM
  -> SwrContext 统一参数
  -> AVAudioFifo 缓存和重新分帧
  -> AVCodecContext 打开目标编码器
  -> AVPacket 得到目标编码数据
  -> AVFormatContext 写入目标容器
  -> 临时输出文件
  -> Android 目标 URI
```

典型 API 顺序：

```cpp
avformat_open_input()
avformat_find_stream_info()
av_find_best_stream()
avcodec_find_decoder()
avcodec_parameters_to_context()
avcodec_open2()

avcodec_find_encoder_by_name()
avformat_alloc_output_context2()
avcodec_alloc_context3()
avcodec_open2()
avformat_new_stream()
avcodec_parameters_from_context()
avio_open()
avformat_write_header()

av_read_frame()
avcodec_send_packet()
avcodec_receive_frame()
swr_convert()
av_audio_fifo_write()
av_audio_fifo_read()
avcodec_send_frame()
avcodec_receive_packet()
av_interleaved_write_frame()

avcodec_send_packet(decoder, nullptr)
swr_convert(..., nullptr, 0)
avcodec_send_frame(encoder, nullptr)
av_write_trailer()
```

说明：上面展示的是本文要实现的完整正确流水线。当前仓库中的 `ffmpeg_jni.cpp` 仍保留直接把重采样帧送入编码器的旧路径；因此，遇到固定帧大小编码器时，应按照第 11 节接入 FIFO 后再运行完整流程。

---

## 4. Android 层：URI、临时文件和线程

### 4.1 为什么使用临时文件

ACTION_OPEN_DOCUMENT 返回的通常是：

```text
content://com.android.providers.media.documents/document/audio%3A12345
```

FFmpeg 普通文件 API 使用：

```text
/data/user/0/com.bigjelly.temporun/cache/input-1234.audio
```

FFmpeg 不会自动调用 ContentResolver，因此项目先把 URI 复制到应用私有目录：

```kotlin
ACTION_OPEN_DOCUMENT
  -> ContentResolver.openInputStream(uri)
  -> cacheDir/input-*.audio
  -> 把绝对路径传给 JNI

ACTION_CREATE_DOCUMENT
  -> FFmpeg 写 cacheDir/converted-*.flac
  -> ContentResolver.openOutputStream(uri)
  -> 复制到用户选择的位置
```

### 4.2 复制输入文件

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

### 4.3 执行转换并保存输出

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

先写临时输出文件，可以避免转换失败时留下不完整的用户文件。

### 4.4 必须使用后台线程

不要在主线程直接执行：

```kotlin
FfmpegBridge.convert(inputPath, outputPath, "flac")
```

推荐使用 ExecutorService 或协程：

```kotlin
lifecycleScope.launch(Dispatchers.IO) {
    try {
        FfmpegBridge.convert(
            inputFile.absolutePath,
            outputFile.absolutePath,
            targetFormat,
        )

        withContext(Dispatchers.Main) {
            showSuccess()
        }
    } catch (error: IOException) {
        withContext(Dispatchers.Main) {
            showError(error.message.orEmpty())
        }
    }
}
```

---

## 5. Kotlin API 使用方法

### 5.1 FfmpegBridge

```kotlin
object FfmpegBridge {
    init {
        System.loadLibrary("ffmpeg_jni")
    }

    private external fun nativeVersion(): String
    private external fun nativeConfiguration(): String
    private external fun nativeProbe(path: String): String

    private external fun nativeConvert(
        inputPath: String,
        outputPath: String,
        targetFormat: String,
    )

    fun version(): String = nativeVersion()

    fun configuration(): String = nativeConfiguration()

    fun convert(
        inputPath: String,
        outputPath: String,
        targetFormat: String,
    ) {
        require(inputPath.isNotBlank())
        require(outputPath.isNotBlank())
        nativeConvert(inputPath, outputPath, targetFormat)
    }
}
```

### 5.2 convert() 参数

```kotlin
FfmpegBridge.convert(
    inputPath = "/data/user/0/.../cache/input.audio",
    outputPath = "/data/user/0/.../cache/output.flac",
    targetFormat = "flac",
)
```

| 参数 | 说明 |
| --- | --- |
| inputPath | 已存在的本地输入绝对路径 |
| outputPath | native 层可写的本地输出绝对路径 |
| targetFormat | mp3、wav、flac、ogg 或 opus |

targetFormat 使用小写 key，UI 显示名称会转换为小写后传给 native。

### 5.3 probe() 的用途

转换前：

```kotlin
val inputProbe = FfmpegBridge.probe(inputFile.absolutePath)
println(inputProbe.codec)
println(inputProbe.sampleRate)
println(inputProbe.channels)
```

转换后：

```kotlin
FfmpegBridge.convert(
    inputFile.absolutePath,
    outputFile.absolutePath,
    "flac",
)

val outputProbe = FfmpegBridge.probe(outputFile.absolutePath)
check(outputProbe.codec == "flac")
```

probe 可以确认文件是否包含音频流，也可以验证输出编码、采样率、声道和时长。

### 5.4 API 调用约束

- `convert()` 是同步 native 调用，不能在 Android 主线程执行；项目应通过 `Executor`、协程 `Dispatchers.IO` 或其他后台线程调用。
- `inputPath` 和 `outputPath` 必须是普通文件的绝对路径。`content://` URI 需要先通过 `ContentResolver` 复制到缓存目录。
- `targetFormat` 只能传 `mp3`、`wav`、`flac`、`ogg`、`opus` 五个小写 key；大写显示名称应在 Kotlin 层先转换。
- `nativeConvert()` 成功返回只表示 native 已完成写文件；复制到 `ACTION_CREATE_DOCUMENT` 返回的 URI 仍由 Kotlin 层负责。
- native 抛出的 `IOException` 应在 UI 层转换为失败状态，并在 `finally` 中删除临时输出文件。

---

## 6. JNI 层 API 使用方法

### 6.1 JNI 声明和符号名

Kotlin：

```kotlin
private external fun nativeConvert(
    inputPath: String,
    outputPath: String,
    targetFormat: String,
)
```

C++：

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_bigjelly_temporun_player_FfmpegBridge_nativeConvert(
        JNIEnv *env,
        jobject,
        jstring inputPath,
        jstring outputPath,
        jstring targetFormat);
```

因为方法位于 com.bigjelly.temporun.player.FfmpegBridge，JNI 名称必须匹配：

```text
Java_com_bigjelly_temporun_player_FfmpegBridge_nativeConvert
```

修改包名、类名或 native 方法名时，C++ 符号也要同步修改。

### 6.2 Java 字符串转换

```cpp
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

### 6.3 native 异常转换为 Java 异常

```cpp
void throwException(
        JNIEnv *env,
        const std::string &message) {
    jclass exceptionClass =
            env->FindClass("java/io/IOException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(
                exceptionClass,
                message.c_str());
        env->DeleteLocalRef(exceptionClass);
    }
}
```

JNI 入口统一捕获 C++ 异常：

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
        throwArgument(
                env,
                "Conversion arguments must not be null");
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

C++ 异常不能直接跨过 JNI 边界，必须在 JNI 函数内部处理。

### 6.4 JNI 参数和返回值约定

`JNIEnv *env` 是当前线程的 JNI 操作入口，不能跨线程保存；`jobject` 是调用该实例方法的对象，本项目没有使用它；三个 `jstring` 分别对应 Kotlin 方法的输入路径、输出路径和目标格式。`nativeConvert()` 的返回类型是 `void`：成功时不返回音频数据，输出结果写入 `outputPath`；失败时通过 `ThrowNew()` 把 C++ 异常转换为 Java `IOException` 或 `IllegalArgumentException`。

`GetStringUTFChars()` 返回 JNI 修改后的 UTF-8 字符串指针，只在对应 `ReleaseStringUTFChars()` 调用前有效，不能保存到转换函数之外。对于包含非 ASCII 路径的场景，应确认 JNI 字符串转换和 Android 文件系统路径均能正确处理；更稳妥的生产实现可以使用 `GetStringChars()` 配合 UTF-16 到 UTF-8 的显式转换。

### 6.5 nativeVersion()、nativeConfiguration() 和 nativeProbe()

| 方法 | 方法作用 | 参数 | 返回值 |
| --- | --- | --- | --- |
| `nativeVersion()` | 查询当前链接的 FFmpeg 版本 | 无 | FFmpeg 版本字符串，`jstring` |
| `nativeConfiguration()` | 查询 FFmpeg 构建配置，用于确认是否包含 `libmp3lame`、`libvorbis`、`libopus` 等编码器 | 无 | 编译配置字符串，`jstring` |
| `nativeProbe(String path)` | 打开文件、查找音频流并读取格式、编码器、采样率、声道、时长和标签 | `path`：本地绝对路径，不能是 `content://` URI | JSON 字符串，失败时抛出 `IOException` |

`nativeProbe(path)` 的 `path` 参数必须指向已经存在且可读的文件。native 层内部依次调用 `avformat_open_input()`、`avformat_find_stream_info()` 和音频流选择 API；它只读取信息，不修改输入文件。Kotlin 的 `probe()` 再把 JSON 转成 `FfmpegProbe` 对象。

### 6.6 查询和诊断相关的 Native API

#### av_version_info()

函数原型：

```cpp
const char *av_version_info(void);
```

方法作用：返回当前 FFmpeg 构建的版本字符串。无参数、无错误码；返回的字符串由 FFmpeg 管理，调用方不能释放。项目的 `nativeVersion()` 直接把它转换为 Kotlin `String`。

#### avformat_configuration()

函数原型：

```cpp
const char *avformat_configuration(void);
```

方法作用：返回编译 FFmpeg 时使用的 configure 参数，用于确认是否启用了外部编码器和相关组件。无参数；返回常量字符串，不能释放。项目的 `nativeConfiguration()` 用它诊断 `libmp3lame`、`libvorbis`、`libopus` 是否可能可用。

#### avcodec_get_name()

函数原型：

```cpp
const char *avcodec_get_name(enum AVCodecID id);
```

方法作用：把编码器 ID 转成短名称，例如 `mp3`、`flac`、`vorbis` 或 `opus`，用于 probe 输出和日志。

参数和返回值：

- `id`：流参数中的 `codec_id`；
- 返回由 FFmpeg 管理的常量字符串；
- 未知 ID 时通常返回 `unknown`，调用方不能释放返回指针。

#### avcodec_descriptor_get()

函数原型：

```cpp
const AVCodecDescriptor *avcodec_descriptor_get(
        enum AVCodecID id);
```

方法作用：查询编码器的静态描述信息，例如长名称和媒体类型。它只查询注册表，不创建或打开解码器。

参数和返回值：

- `id`：编码器 ID；
- 返回 `AVCodecDescriptor` 常量指针，找不到时返回 `nullptr`；
- 返回对象由 FFmpeg 管理，调用方不能释放。

#### av_dict_iterate()

函数原型：

```cpp
const AVDictionaryEntry *av_dict_iterate(
        const AVDictionary *m,
        const AVDictionaryEntry *prev);
```

方法作用：遍历容器或流的 metadata 标签，例如 title、artist 和 album。第一次调用时 `prev` 传 `nullptr`，之后把上一次返回的条目传回去，直到返回 `nullptr`。

参数和返回值：

- `m`：待遍历的字典；可以是 `stream->metadata` 或 `format->metadata`；
- `prev`：上一次返回的条目，第一次必须为 `nullptr`；
- 返回当前条目，包含 `key` 和 `value`；遍历结束返回 `nullptr`；
- 返回条目属于 FFmpeg，调用方不能释放或修改其内存。

---

## 7. FFmpeg 核心对象

| 对象 | 作用 |
| --- | --- |
| AVFormatContext | 输入或输出容器 |
| AVStream | 容器中的一条音频、视频或字幕流 |
| AVCodec | 编解码器描述 |
| AVCodecContext | 解码器或编码器运行上下文 |
| AVCodecParameters | 流的静态编码参数 |
| AVPacket | 压缩数据包 |
| AVFrame | 解码后的 PCM 音频帧 |
| SwrContext | 重采样、声道转换、采样格式转换 |
| AVAudioFifo | 按采样数缓存 PCM |
| AVDictionary | 标签和元数据 |

必须区分：

```cpp
AVPacket：压缩数据，来自 av_read_frame()
AVFrame：PCM 数据，来自 avcodec_receive_frame()
```

解码器输入 AVPacket，编码器输入 AVFrame。

---

## 8. 输入侧 Native API

本节中的输入侧 API 负责完成“打开文件、识别流、创建并打开解码器”。它们的依赖关系是：

```cpp
avformat_open_input()
  -> avformat_find_stream_info()
  -> av_find_best_stream()
  -> avcodec_find_decoder()
  -> avcodec_alloc_context3()
  -> avcodec_parameters_to_context()
  -> avcodec_open2()
```

FFmpeg 的返回值约定如下：

- `0` 或非负值通常表示成功；
- `AVERROR(EAGAIN)` 表示当前暂时没有更多数据，不一定是失败；
- `AVERROR_EOF` 表示输入或处理器已经结束；
- 其他负值表示错误，可以用 `av_strerror()` 转成人类可读的文字。

### 8.0 固定选项参数的阅读方法

FFmpeg API 中有三类常见的“只能从固定集合选择”的参数：

| 类型 | 典型参数 | 可选值来源 | 本项目处理方式 |
| --- | --- | --- | --- |
| 枚举值 | `AVMediaType`、`AVSampleFormat`、`AVRounding` | 头文件中的 `enum` 常量 | 根据场景选择一个明确常量 |
| 标志位 | `AVIO_FLAG_WRITE`、`AV_CODEC_CAP_*` | `#define` 位掩码 | 可以用 `|` 组合，判断时用 `&` |
| 注册名称 | `libmp3lame`、`flac`、`libopus` | 当前 FFmpeg 构建实际注册的编码器/封装器 | 用名称查找，找不到必须报错 |

`nullptr` 不是“一个具体格式值”，而是“交给 FFmpeg 自动探测或使用默认配置”。例如 `avformat_open_input()` 的 `fmt` 传 `nullptr`，表示根据文件内容和 URL/扩展名探测输入格式；只有已经明确知道容器类型、需要禁止误探测时，才传一个具体的 `AVInputFormat*`。

本项目最常遇到的固定选项速查：

| 参数类别 | 可设置值 | 选择含义 |
| --- | --- | --- |
| `targetFormat` | `mp3`、`wav`、`flac`、`ogg`、`opus` | 选择目标编码器和输出容器 |
| `AVInputFormat *fmt` | `nullptr`、`mp3`、`wav`、`flac`、`ogg` 或其他已注册 demuxer | 自动探测，或强制使用输入解复用器 |
| `AVMediaType type` | `UNKNOWN`、`VIDEO`、`AUDIO`、`DATA`、`SUBTITLE`、`ATTACHMENT` | 指定要选择哪一种流 |
| `AVCodecID id` | `MP3`、`PCM_S16LE`、`FLAC`、`VORBIS`、`OPUS` 等 | 指定要查找的解码器编码类型 |
| `AVSampleFormat` | `U8`、`S16`、`S32`、`FLT`、`DBL` 及对应 planar 版本 | 指定 PCM 样本类型和数据布局 |
| `AVRounding` | `ZERO`、`INF`、`DOWN`、`UP`、`NEAR_INF`，可附加 `PASS_MINMAX` | 指定整数比例换算的舍入规则 |
| IO flags | `READ`、`WRITE`、`READ_WRITE`，可附加 `NONBLOCK`、`DIRECT` | 指定底层文件/协议的打开方式 |

其中 `AVInputFormat`、`AVOutputFormat` 和编码器名称不是编译期固定枚举，最终可用值取决于本次 FFmpeg 构建。文档列出的项目值是当前构建和 UI 需要的值；如果要支持更多格式，应通过 `av_demuxer_iterate()`、`av_muxer_iterate()` 或 `avcodec_find_*()` 查询实际注册项，而不是凭经验硬编码名称。

### 8.1 avformat_open_input()

函数原型：

```cpp
int avformat_open_input(
        AVFormatContext **ps,
        const char *url,
        const AVInputFormat *fmt,
        AVDictionary **options);
```

方法作用：打开输入文件或输入 URL，探测输入容器，并创建 `AVFormatContext`。此时主要是打开和探测容器，音频流的完整参数还需要调用 `avformat_find_stream_info()` 获取。

```cpp
AVFormatContext *input = nullptr;

int result = avformat_open_input(
        &input,
        inputPath.c_str(),
        nullptr,
        nullptr);
if (result < 0) {
    throw std::runtime_error(
            "无法打开输入文件: " +
            ffmpegError(result));
}
```

作用是打开输入文件并创建 AVFormatContext。

| 参数 | 含义 |
| --- | --- |
| &input | 输出的格式上下文 |
| inputPath | 输入本地路径 |
| 第三个参数 | 指定输入格式；nullptr 表示自动探测 |
| 第四个参数 | 格式选项字典；nullptr 表示默认 |

`fmt` 的可设置值：

| 设置方式 | 含义 | 适用场景 |
| --- | --- | --- |
| `nullptr` | 自动探测输入格式，推荐用于本项目 | 用户选择的音频可能是 MP3、WAV、FLAC、Ogg/Opus 等，不能仅凭扩展名判断 |
| `av_find_input_format("mp3")` | 强制使用 MPEG Audio/MP3 输入解复用器 | 已经确认文件是 MP3，且希望格式不被其他探测结果覆盖 |
| `av_find_input_format("wav")` | 强制使用 WAV 输入解复用器 | 已确认是 RIFF/WAV |
| `av_find_input_format("flac")` | 强制使用 FLAC 输入解复用器 | 已确认是原生 FLAC 文件 |
| `av_find_input_format("ogg")` | 强制使用 Ogg 输入解复用器 | 已确认是 Ogg 容器；内部可能是 Vorbis 或 Opus |
| 其他 FFmpeg 注册的输入格式 | 强制使用指定 demuxer | 只有在业务明确限制输入容器时使用 |

`av_find_input_format()` 的原型为：

```cpp
const AVInputFormat *av_find_input_format(
        const char *short_name);
```

其中 `short_name` 是 FFmpeg 注册的短名称，不是任意文件扩展名；找不到时返回 `nullptr`。例如：

```cpp
const AVInputFormat *forced =
        av_find_input_format("wav");
if (forced == nullptr) {
    throw std::runtime_error("找不到 WAV 输入格式");
}
```

不要把 `"mp3"`、`"wav"` 等字符串直接强转为 `AVInputFormat*`。项目默认传 `nullptr`，因为输入由系统文件选择器提供，强制指定格式会导致用户选择了其他格式时打开失败。

返回值和注意事项：

- 返回 `0` 表示成功；
- 返回负值表示文件不存在、无法读取、格式不支持或文件损坏；
- 成功后 `input` 由 FFmpeg 分配，结束时用 `avformat_close_input(&input)` 释放；
- 本项目的 `inputPath` 必须是应用缓存目录中的普通绝对路径，不能直接是 Android 的 `content://` URI；
- 如果 `options` 中有未被使用的选项，调用后应检查并释放剩余字典项。

### 8.2 avformat_find_stream_info()

函数原型：

```cpp
int avformat_find_stream_info(
        AVFormatContext *ic,
        AVDictionary **options);
```

方法作用：读取一部分输入数据并分析所有流，补充 `AVStream` 和 `codecpar` 中的编码器、采样率、声道、时长等信息。它不是打开文件，而是完善已经打开的容器信息。

```cpp
result = avformat_find_stream_info(
        input,
        nullptr);
if (result < 0) {
    throw std::runtime_error(
            "无法读取输入文件信息: " +
            ffmpegError(result));
}
```

它会读取并分析输入流，用于确定 codec、采样率、声道和时长。

参数和返回值：

- `ic`：由 `avformat_open_input()` 返回的输入容器上下文；
- `options`：每条流对应的格式/解码选项，项目使用 `nullptr` 表示默认行为；
- 返回非负值表示分析完成；
- 返回负值表示无法读取或分析输入信息；
- 即使容器打开成功，如果该方法失败，也不能继续创建解码器。

### 8.3 av_find_best_stream()

函数原型：

```cpp
int av_find_best_stream(
        AVFormatContext *ic,
        enum AVMediaType type,
        int wanted_stream_nb,
        int related_stream,
        const AVCodec **decoder_ret,
        int flags);
```

方法作用：从容器的多条流中选择最合适的一条。例如 MP4 可能同时包含视频、音频、字幕和封面流，该函数可以选择音频流。

```cpp
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

AVStream *inputStream =
        input->streams[audioIndex];
```

不能假设第 0 条流就是音频，因为 MP4、MKV 等容器可能包含视频、音频、字幕和封面流。

参数含义：

- `ic`：输入容器上下文；
- `type`：希望查找的媒体类型，本项目传 `AVMEDIA_TYPE_AUDIO`；
- `wanted_stream_nb`：指定流索引，传 `-1` 表示由 FFmpeg 自动选择；
- `related_stream`：相关流索引，通常传 `-1`；
- `decoder_ret`：可选的解码器输出指针，本项目传 `nullptr`，之后单独查找解码器；
- `flags`：查找选项，本项目传 `0`；
- 返回值是选中的流索引，负值表示没有合适的音频流。

`type` 的常见固定值：

| 值 | 含义 | 本项目是否使用 |
| --- | --- | --- |
| `AVMEDIA_TYPE_UNKNOWN` | 未知媒体类型 | 否 |
| `AVMEDIA_TYPE_VIDEO` | 视频流 | 否 |
| `AVMEDIA_TYPE_AUDIO` | 音频流 | 是 |
| `AVMEDIA_TYPE_DATA` | 数据流 | 否 |
| `AVMEDIA_TYPE_SUBTITLE` | 字幕流 | 否 |
| `AVMEDIA_TYPE_ATTACHMENT` | 附件流，例如字体 | 否 |
| `AVMEDIA_TYPE_NB` | 枚举数量边界，不是有效媒体类型 | 不可传 |

`wanted_stream_nb` 和 `related_stream` 是整数选择项：传 `-1` 表示不指定具体流、由 FFmpeg 选择；传 `0` 或其他非负索引表示指定容器中的某条流。项目传 `-1`，再使用返回的音频流索引。

`flags` 是预留的整型标志参数。根据 FFmpeg 7.1 的头文件，`av_find_best_stream()` 当前没有定义可用的 flags 常量，因此只能传 `0`；不要把 `AVSEEK_FLAG_BACKWARD` 或其他 API 的 flags 混用到这里。

### 8.4 avcodec_find_decoder()

函数原型：

```cpp
const AVCodec *avcodec_find_decoder(enum AVCodecID id);
```

方法作用：根据输入流的 codec ID 查找对应的解码器描述。它只查找解码器，不分配上下文，也不会打开解码器。

```cpp
const AVCodec *decoderCodec =
        avcodec_find_decoder(
                inputStream->codecpar->codec_id);
if (decoderCodec == nullptr) {
    throw std::runtime_error(
        "找不到输入音频解码器");
}
```

参数和返回值：

- `id`：输入流的编码器 ID，例如 MP3、FLAC 或 Vorbis 的 codec ID；
- 返回非空 `AVCodec*` 表示找到解码器；
- 返回 `nullptr` 表示当前 FFmpeg 构建没有包含对应解码器；
- 返回的 `AVCodec` 由 FFmpeg 管理，调用方不能用 `av_free()` 释放。

项目音频输入中常见的 `AVCodecID` 值：

| 值 | 含义 | 常见来源 |
| --- | --- | --- |
| `AV_CODEC_ID_MP3` | MPEG Layer I/II/III 音频，项目主要表现为 MP3 | `.mp3`、MPEG Audio |
| `AV_CODEC_ID_PCM_S16LE` | little-endian signed 16-bit PCM | 项目的 WAV 输出 |
| `AV_CODEC_ID_FLAC` | FLAC 无损音频 | `.flac` |
| `AV_CODEC_ID_VORBIS` | Vorbis 音频编码 | Ogg/Vorbis `.ogg` |
| `AV_CODEC_ID_OPUS` | Opus 音频编码 | Ogg/Opus `.opus` |
| `AV_CODEC_ID_NONE` | 未知或未设置 codec ID | 不能直接打开解码器 |

`AVCodecID` 是 FFmpeg 的内部枚举，不要把文件扩展名字符串直接转换成该枚举。输入流的 `codecpar->codec_id` 由解复用器提供，再传给 `avcodec_find_decoder()`。

### 8.5 avcodec_alloc_context3()

函数原型：

```cpp
AVCodecContext *avcodec_alloc_context3(
        const AVCodec *codec);
```

方法作用：分配一个 `AVCodecContext`，用于保存解码器或编码器的运行参数和内部状态。它只分配上下文，不等于已经打开编解码器。

```cpp
AVCodecContext *decoder =
        avcodec_alloc_context3(decoderCodec);
if (decoder == nullptr) {
    throw std::runtime_error(
            "无法分配解码器上下文");
}
```

此时解码器还没有打开。

参数和返回值：

- `codec`：要使用的 `AVCodec` 描述；可以传找到的 decoder/encoder，也可以传 `nullptr` 后再手动配置；
- 返回非空指针表示分配成功；
- 返回 `nullptr` 通常表示 native 内存不足；
- 成功后必须使用 `avcodec_free_context(&context)` 释放。

### 8.6 avcodec_parameters_to_context()

函数原型：

```cpp
int avcodec_parameters_to_context(
        AVCodecContext *codec,
        const AVCodecParameters *par);
```

方法作用：把输入流的静态参数复制到解码器上下文，使解码器知道如何解释输入的压缩包。

```cpp
result = avcodec_parameters_to_context(
        decoder,
        inputStream->codecpar);
if (result < 0) {
    throw std::runtime_error(
            "无法配置解码器: " +
            ffmpegError(result));
}
```

它把输入流的静态参数复制到解码器上下文。

参数和返回值：

- `codec`：已经通过 `avcodec_alloc_context3()` 分配的解码器上下文；
- `par`：输入流的 `codecpar`，包含 codec ID、extradata、采样率、声道布局等信息；
- 返回 `0` 表示复制成功；
- 返回负值表示参数非法或复制失败；
- 该方法不会打开解码器，后续仍然必须调用 `avcodec_open2()`。

### 8.7 avcodec_open2()

函数原型：

```cpp
int avcodec_open2(
        AVCodecContext *avctx,
        const AVCodec *codec,
        AVDictionary **options);
```

方法作用：根据上下文参数真正初始化并打开解码器或编码器。打开成功后，编码器的 `frame_size`、实际采样格式等运行参数才可靠。

```cpp
result = avcodec_open2(
        decoder,
        decoderCodec,
        nullptr);
if (result < 0) {
    throw std::runtime_error(
            "无法打开解码器: " +
            ffmpegError(result));
}
```

MP3 解码后可能得到：

```cpp
sample_rate = 44100
ch_layout   = stereo
sample_fmt  = fltp
nb_samples  = 1152
```

参数和返回值：

- `avctx`：待打开的解码器或编码器上下文；
- `codec`：要使用的 `AVCodec`，通常传 `avcodec_find_decoder()` 或 `avcodec_find_encoder_by_name()` 返回的指针；
- `options`：编码器/解码器选项字典，本项目传 `nullptr` 使用默认选项；
- 返回 `0` 表示成功；
- 返回负值表示采样率、声道布局、采样格式、码率或其他选项不被支持；
- 成功后不能随意修改影响编码器配置的字段，若要重新配置，应先关闭并释放上下文。

`options` 不是一个固定枚举，而是键值选项字典；键和值由具体 codec 决定。常见控制项包括 `bit_rate`、`compression_level`、`profile`、`vbr` 等，但不能把某个编码器的选项字典直接套到另一个编码器。项目传 `nullptr` 使用默认配置；如果要指定选项，应使用 `av_dict_set(&options, "key", "value", 0)`，调用后检查并释放未被 codec 消费的条目。

---

## 9. 输出侧 Native API

输出侧 API 负责创建目标编码器、创建目标容器、配置输出流并写入文件头。基本依赖关系是：

```cpp
avcodec_find_encoder_by_name()
  -> avcodec_alloc_context3()
  -> 配置 sample_rate/sample_fmt/ch_layout/time_base
  -> avcodec_open2()
  -> avformat_alloc_output_context2()
  -> avformat_new_stream()
  -> avcodec_parameters_from_context()
  -> avio_open()
  -> avformat_write_header()
```

### 9.1 avcodec_find_encoder_by_name()

函数原型：

```cpp
const AVCodec *avcodec_find_encoder_by_name(
        const char *name);
```

方法作用：根据编码器名称查找目标音频编码器。项目根据 `targetFormat` 将 `mp3`、`wav`、`flac`、`ogg`、`opus` 映射到具体编码器名称。

```cpp
const AVCodec *encoderCodec =
        avcodec_find_encoder_by_name(codecName);
if (encoderCodec == nullptr) {
    throw std::runtime_error(
            "找不到目标编码器: " +
            std::string(codecName));
}
```

项目的编码器名称：

```text
mp3  -> libmp3lame
wav  -> pcm_s16le
flac -> flac
ogg  -> libvorbis
opus -> libopus
```

参数和返回值：

- `name`：编码器短名称，例如 `flac`、`libmp3lame` 或 `libopus`；
- 返回非空指针表示找到了编码器；
- 返回 `nullptr` 表示当前 FFmpeg 构建没有该编码器；
- 返回的 `AVCodec` 由 FFmpeg 管理，不需要调用方释放；
- 找到编码器不代表它已经可用，仍然要配置上下文并调用 `avcodec_open2()`。

`name` 是固定的注册名称，不是目标文件扩展名。项目支持的目标值如下：

| `name` | 目标 key | 编码内容 | 常见输出容器/扩展名 | 说明 |
| --- | --- | --- | --- | --- |
| `libmp3lame` | `mp3` | MP3 | MPEG Audio / `.mp3` | 外部 LAME 编码器，有损 |
| `pcm_s16le` | `wav` | signed 16-bit little-endian PCM | RIFF/WAV / `.wav` | 未压缩，文件较大 |
| `flac` | `flac` | FLAC | FLAC / `.flac` | 无损压缩 |
| `libvorbis` | `ogg` | Vorbis | Ogg / `.ogg` | OGG 是容器，Vorbis 是编码器 |
| `libopus` | `opus` | Opus | Ogg/Opus / `.opus` | 适合语音、音乐和网络传输 |

编码器名称还取决于 FFmpeg 构建配置。可以通过 `nativeConfiguration()` 查看配置，通过 `avcodec_find_encoder_by_name()` 判断具体编码器是否实际注册；`nullptr` 表示该值不可用。不要把 `"mp3"` 当成 `libmp3lame` 的替代名称，除非当前构建确实注册了名为 `mp3` 的其他编码器。

### 9.2 avformat_alloc_output_context2()

函数原型：

```cpp
int avformat_alloc_output_context2(
        AVFormatContext **avformat,
        const AVOutputFormat *oformat,
        const char *format_name,
        const char *filename);
```

第一个参数在项目调用中传入 `&output`。

方法作用：创建输出容器的 `AVFormatContext`，用于确定输出容器、流列表、文件 IO 和封装规则。

```cpp
AVFormatContext *output = nullptr;

result = avformat_alloc_output_context2(
        &output,
        nullptr,
        nullptr,
        outputPath.c_str());
if (result < 0 || output == nullptr) {
    throw std::runtime_error(
            "无法创建输出容器: " +
            ffmpegError(result));
}
```

format 参数为 nullptr 时，FFmpeg 通常根据扩展名推断容器。更严格的实现可以显式指定 flac、wav 或 ogg。

参数和返回值：

- 第一个参数：输出的 `AVFormatContext*`；成功后由 FFmpeg 分配；
- `oformat`：显式指定 `AVOutputFormat`，传 `nullptr` 表示自动选择；
- `format_name`：容器名称，例如 `flac`、`wav`、`ogg`，传 `nullptr` 表示自动探测；
- `filename`：输出路径，FFmpeg 会根据扩展名辅助推断格式；
- 返回 `0` 表示成功，负值表示找不到输出格式或参数非法；
- 成功后用 `avformat_free_context(output)` 释放上下文。

`oformat`、`format_name` 和 `filename` 的选择关系：

| 参数设置 | 含义 | 项目建议 |
| --- | --- | --- |
| `oformat != nullptr` | 直接使用调用方给定的输出封装器 | 只有已通过 `av_guess_format()` 或 `av_muxer_iterate()` 得到有效指针时使用 |
| `format_name != nullptr` | 按封装器短名称创建输出格式，例如 `wav`、`flac`、`ogg` | 需要强制容器时使用；名称必须是已注册 muxer |
| `format_name == nullptr` 且 `filename != nullptr` | 根据文件名扩展名猜测输出容器 | 当前项目的第一次调用方式 |
| 三者都无法确定 | 无法创建输出上下文 | 返回负错误码 |

项目输出容器与 `format_name` 的推荐值：

| 目标格式 | 编码器 name | 推荐 `format_name` | 说明 |
| --- | --- | --- | --- |
| MP3 | `libmp3lame` | `mp3` 或让 `.mp3` 扩展名自动推断 | 编码器和容器名称不同 |
| WAV | `pcm_s16le` | `wav` | PCM 编码放入 RIFF/WAV |
| FLAC | `flac` | `flac` | 原生 FLAC 容器 |
| OGG | `libvorbis` | `ogg` | Ogg 容器承载 Vorbis |
| OPUS | `libopus` | `opus` | Ogg/Opus 输出 |

`avformat_alloc_output_context2()` 的 `oformat` 优先级高于 `format_name`，`format_name` 优先级高于通过 `filename` 扩展名推断。生产实现应避免同时传入互相矛盾的值，例如 `format_name = "wav"` 却使用 `.flac` 文件名。

### 9.3 配置编码器

这部分不是单个 FFmpeg 函数，而是调用 `avcodec_open2()` 之前必须完成的一组配置。至少要确定：

- `sample_rate`：目标采样率；
- `sample_fmt`：目标编码器接受的采样格式；
- `ch_layout`：目标声道布局；
- `time_base`：编码器时间基；
- `bit_rate`：MP3、Vorbis、Opus 等有损编码器的目标码率。

选择编码器支持的采样格式：

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
```

配置采样率、采样格式、时间基和声道布局：

```cpp
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
if (result < 0 ||
    encoder->ch_layout.nb_channels <= 0) {
    av_channel_layout_uninit(
            &encoder->ch_layout);
    av_channel_layout_default(
            &encoder->ch_layout,
            2);
    result = encoder->ch_layout.nb_channels > 0 ? 0 : AVERROR(EINVAL);
}
if (result < 0) {
    throw std::runtime_error(
            "无法设置编码器声道布局");
}
```

设置有损编码器码率：

```cpp
if (targetFormat == "mp3") {
    encoder->bit_rate = 192000;
} else if (targetFormat == "ogg") {
    encoder->bit_rate = 128000;
} else if (targetFormat == "opus") {
    encoder->bit_rate = 96000;
}
```

打开编码器：

```cpp
result = avcodec_open2(
        encoder,
        encoderCodec,
        nullptr);
if (result < 0) {
    throw std::runtime_error(
        "无法打开编码器: " +
        ffmpegError(result));
}
```

配置注意事项：

- `encoderCodec->sample_fmts` 是编码器支持的采样格式列表，不能假定所有编码器都支持 `fltp`；
- 如果目标编码器不支持输入采样率，应先选择合法的目标采样率，再用 `SwrContext` 重采样；
- `ch_layout` 必须包含有效的声道数；
- 配置完成后调用 `avcodec_open2()`，打开后再读取 `encoder->frame_size` 和 capability；
- `encoder->time_base = {1, sample_rate}` 时，PTS 的单位就是每个采样。

### 9.3.1 采样率、码率和声道布局的可选值

这三个参数不是同一种“固定枚举”：

| 参数 | 可选值 | 选择规则 |
| --- | --- | --- |
| `sample_rate` | 由 `encoderCodec->supported_samplerates` 给出的整数 Hz，例如 44100、48000 | 如果列表为空，编码器通常接受任意正采样率；如果输入值不在列表中，应选择最近的支持值并用 `SwrContext` 重采样 |
| `bit_rate` | 由具体编码器支持的整数 bit/s，例如项目 MP3 192000、Vorbis 128000、Opus 96000 | 只对有损编码器有主要意义；FLAC 和 PCM 通常不按该字段控制压缩 |
| `ch_layout` | 编码器支持的布局，常见为 mono、stereo、5.1、7.1 | 应优先从编码器支持列表和输入布局中选择；项目先复制输入布局，失败时回退 stereo |

查询编码器支持的采样率：

```cpp
if (encoderCodec->supported_samplerates != nullptr) {
    for (const int *rate =
                 encoderCodec->supported_samplerates;
         *rate != 0;
         ++rate) {
        // *rate 是一个可设置的采样率，单位 Hz
    }
}
```

`supported_samplerates` 以 `0` 作为结束标记；`sample_fmts` 以 `AV_SAMPLE_FMT_NONE` 作为结束标记。不能把结束标记当成有效配置。码率通常不是枚举，不能从一个统一的全局列表列出所有值，应参考具体编码器文档和 `avcodec_open2()` 的返回结果。

### 9.4 avformat_new_stream()

函数原型：

```cpp
AVStream *avformat_new_stream(
        AVFormatContext *s,
        const AVCodec *c);
```

方法作用：在输出容器中创建一条新的流。本项目只创建一条音频流，随后把编码器参数复制到该流。

```cpp
AVStream *outputStream =
        avformat_new_stream(output, nullptr);
if (outputStream == nullptr) {
    throw std::runtime_error(
            "无法创建输出音频流");
}

outputStream->time_base =
        encoder->time_base;
```

参数和返回值：

- `s`：输出容器上下文；
- `c`：关联的编码器描述，通常可以传 `nullptr`，之后使用 `avcodec_parameters_from_context()` 设置参数；
- 返回新建的 `AVStream*`；
- 返回 `nullptr` 表示内存不足或容器不允许创建流；
- `outputStream->index` 会被 FFmpeg 分配，写包时应设置到 `packet->stream_index`。

### 9.5 avcodec_parameters_from_context()

函数原型：

```cpp
int avcodec_parameters_from_context(
        AVCodecParameters *par,
        const AVCodecContext *codec);
```

方法作用：把已经配置好的编码器上下文复制为输出流的 codec 参数，使容器知道如何解释编码包。

```cpp
result = avcodec_parameters_from_context(
        outputStream->codecpar,
        encoder);
if (result < 0) {
    throw std::runtime_error(
        "无法设置输出音频参数: " +
        ffmpegError(result));
}
```

参数和返回值：

- `par`：输出流的 `codecpar`；
- `codec`：已经配置并通常已经打开的编码器上下文；
- 返回 `0` 表示复制成功；
- 返回负值表示编码器参数不完整或复制失败；
- 该函数不会写文件，也不会替代 `avformat_write_header()`。

### 9.6 avio_open()

函数原型：

```cpp
int avio_open(
        AVIOContext **s,
        const char *url,
        int flags);
```

方法作用：打开输出文件的底层 IO，创建 `AVIOContext` 并保存到 `output->pb`。

```cpp
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
```

参数和返回值：

- `s`：输出的 `AVIOContext*`，项目传 `&output->pb`；
- `url`：输出路径，项目传临时文件绝对路径；
- `flags`：IO 模式，写文件使用 `AVIO_FLAG_WRITE`；
- 返回 `0` 表示成功，负值表示路径不可写、目录不存在或权限不足；
- 成功后使用 `avio_closep(&output->pb)` 关闭；
- 如果输出格式带有 `AVFMT_NOFILE`，则不应重复调用 `avio_open()`。

`flags` 是位标志，可以选择：

| 值 | 含义 | 典型用途 |
| --- | --- | --- |
| `AVIO_FLAG_READ` | 以只读方式打开 | 读取底层 IO |
| `AVIO_FLAG_WRITE` | 以写入方式打开 | 本项目创建输出文件 |
| `AVIO_FLAG_READ_WRITE` | 同时读写 | 特殊协议或需要回读的容器 |
| `AVIO_FLAG_NONBLOCK` | 尽量使用非阻塞 IO | 网络或特殊协议场景，普通文件通常不需要 |
| `AVIO_FLAG_DIRECT` | 尽量绕过 AVIO 缓冲 | 特殊低延迟/直接 IO 场景，不是普通文件默认选项 |

本项目只应传 `AVIO_FLAG_WRITE`。不要把 `AVFMT_NOFILE` 当作 `avio_open()` 的 flags；它是输出格式的能力标志，用于判断封装器是否自己管理 IO。

### 9.7 avformat_write_header()

函数原型：

```cpp
int avformat_write_header(
        AVFormatContext *s,
        AVDictionary **options);
```

方法作用：根据输出格式、流参数和 metadata 写入输出容器的文件头。调用成功后，才可以通过 `av_interleaved_write_frame()` 写数据包。

```cpp
result = avformat_write_header(
        output,
        nullptr);
if (result < 0) {
    throw std::runtime_error(
            "无法写入输出文件头: " +
            ffmpegError(result));
}
```

必须先写 header，再写编码数据包。

参数和返回值：

- `s`：输出容器上下文，必须已经创建输出流；
- `options`：封装器选项字典，项目传 `nullptr` 使用默认选项；
- 返回 `0` 表示 header 写入成功；
- 返回负值表示容器参数不完整、编码器与容器不匹配或底层 IO 失败；
- header 写入后发生异常时，仍然要关闭 IO 并释放输出上下文。

---

## 10. SwrContext：采样率、声道和采样格式转换

`SwrContext` 负责把解码器输出的 PCM 转换为编码器需要的 PCM。它可以同时完成采样率转换、声道布局转换和采样格式转换。典型关系是：

```text
decoder 输出格式
  -> SwrContext
  -> encoder 输入格式
```

`SwrContext` 的输入参数来自 decoder，输出参数来自 encoder。输入和输出方向写反，会导致重采样失败或生成错误音频。

本节中几个枚举型参数的常见取值：

`AVSampleFormat` 由 `sample_fmt` 指定，常见值如下：

| 值 | 含义 | 数据布局 |
| --- | --- | --- |
| `AV_SAMPLE_FMT_U8` | 无符号 8 位整数 | packed |
| `AV_SAMPLE_FMT_S16` | 有符号 16 位整数 | packed |
| `AV_SAMPLE_FMT_S32` | 有符号 32 位整数 | packed |
| `AV_SAMPLE_FMT_FLT` | 32 位浮点数 | packed |
| `AV_SAMPLE_FMT_DBL` | 64 位浮点数 | packed |
| `AV_SAMPLE_FMT_U8P` | 无符号 8 位整数 | planar |
| `AV_SAMPLE_FMT_S16P` | 有符号 16 位整数 | planar |
| `AV_SAMPLE_FMT_S32P` | 有符号 32 位整数 | planar |
| `AV_SAMPLE_FMT_FLTP` | 32 位浮点数 | planar |
| `AV_SAMPLE_FMT_DBLP` | 64 位浮点数 | planar |
| `AV_SAMPLE_FMT_S64` | 有符号 64 位整数 | packed |
| `AV_SAMPLE_FMT_S64P` | 有符号 64 位整数 | planar |
| `AV_SAMPLE_FMT_NONE` | 无效/结束标记 | 不能用于实际 PCM |

`AV_SAMPLE_FMT_NB` 是格式数量边界，也不是可用于音频帧的采样格式。

其中 `p` 后缀表示 planar。不能仅依据位深判断是否需要 `p`；必须使用编码器的 `codec->sample_fmts` 支持列表。`AV_SAMPLE_FMT_S16` 与 `AV_SAMPLE_FMT_S16P` 的样本精度相同，但数据指针布局不同。

`AVChannelLayout` 不是一个简单枚举。常见布局可设置为：

| 布局 | 声道数 | 常见含义 |
| --- | ---: | --- |
| `mono` | 1 | 单声道 |
| `stereo` | 2 | 左/右双声道 |
| `2.1` | 3 | 左/右/低频 |
| `quad` | 4 | 四声道 |
| `5.1` | 6 | 左/右/中置/低频/左右环绕 |
| `7.1` | 8 | 七点一声道 |

也可以用 `av_channel_layout_default()` 根据声道数量生成默认布局。项目默认复制解码器布局，复制失败时回退到双声道 `stereo`。如果编码器不支持输入布局，应在实际项目中显式选择编码器支持的布局，并同步通过 `SwrContext` 做声道重映射。

### 10.1 创建和初始化

函数原型：

```cpp
int swr_alloc_set_opts2(
        SwrContext **ps,
        const AVChannelLayout *out_ch_layout,
        enum AVSampleFormat out_sample_fmt,
        int out_sample_rate,
        const AVChannelLayout *in_ch_layout,
        enum AVSampleFormat in_sample_fmt,
        int in_sample_rate,
        int log_offset,
        void *log_ctx);
```

方法作用：一次性分配并设置重采样器的输入、输出参数。它只负责创建和配置，之后还必须调用 `swr_init()`。

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

参数顺序：

```text
输出声道布局、输出采样格式、输出采样率、
输入声道布局、输入采样格式、输入采样率
```

参数含义：

- `ps`：输出 `SwrContext*` 的地址，成功后由 FFmpeg 分配；
- `out_ch_layout`：编码器要求的输出声道布局；
- `out_sample_fmt`：编码器要求的输出采样格式；
- `out_sample_rate`：编码器要求的输出采样率；
- `in_ch_layout`：解码器实际输出的输入声道布局；
- `in_sample_fmt`：解码器实际输出的输入采样格式；
- `in_sample_rate`：解码器实际输出的输入采样率；
- `log_offset`、`log_ctx`：日志上下文，本项目使用 `0` 和 `nullptr`；
- 返回 `0` 表示创建成功，负值表示参数非法或分配失败；
- 成功后用 `swr_free(&resampler)` 释放。

`log_offset` 是日志级别偏移量，通常为 `0`；`log_ctx` 是 FFmpeg 日志上下文指针，传 `nullptr` 表示使用默认上下文。它们不改变重采样算法，不能用来选择采样格式或声道。

函数原型：

```cpp
int swr_init(SwrContext *s);
```

方法作用：根据 `swr_alloc_set_opts2()` 的配置初始化内部重采样状态。只有返回成功后才能调用 `swr_convert()`。

参数和返回值：

- `s`：已经配置好的重采样器；
- 返回 `0` 表示初始化成功；
- 返回负值表示输入/输出采样率、采样格式或声道布局组合不支持；
- 初始化失败时不能继续处理音频，应释放该上下文并报告错误。

### 10.2 计算输出容量

函数原型：

```cpp
int64_t swr_get_delay(
        const SwrContext *s,
        int64_t base);
```

方法作用：查询重采样器内部尚未输出的延迟样本数。计算下一次输出缓冲区大小时，必须把这部分 delay 加入输入样本数。

参数和返回值：

- `s`：已初始化的重采样器；
- `base`：返回值使用的时间基，项目传输入采样率，例如 `decoder->sample_rate`；
- 返回当前延迟对应的样本数量；
- 返回值不是字节数，也不是所有声道样本总数。

```cpp
const int outputCapacity =
        static_cast<int>(av_rescale_rnd(
                swr_get_delay(
                        resampler,
                        decoder->sample_rate) +
                        source->nb_samples,
                encoder->sample_rate,
                decoder->sample_rate,
                AV_ROUND_UP));
```

不能直接使用 source->nb_samples 作为输出容量，尤其是在采样率变化时。

### 10.3 调用 swr_convert()

函数原型：

```cpp
int swr_convert(
        SwrContext *s,
        uint8_t *const out_arg[SWR_CH_MAX],
        int out_count,
        const uint8_t *const in_arg[SWR_CH_MAX],
        int in_count);
```

方法作用：把输入 PCM 转换成目标采样率、声道布局和采样格式的 PCM。它可能一次只消耗部分输入或产生部分输出，因此必须根据返回值和 `swr_get_delay()` 继续处理。

```cpp
const int samples = swr_convert(
        resampler,
        converted->data,
        outputCapacity,
        const_cast<const uint8_t **>(
                source->extended_data),
        source->nb_samples);
if (samples < 0) {
    throw std::runtime_error(
            "音频重采样失败: " +
            ffmpegError(samples));
}
```

返回值是输出的每声道采样数，不是字节数。

参数和返回值：

- `s`：已经初始化的 `SwrContext`；
- `out_arg`：输出数据平面，通常传 `converted->data`；
- `out_count`：输出缓冲区最多容纳的每声道采样数；
- `in_arg`：输入数据平面，通常传 `source->extended_data`；
- `in_count`：输入的每声道采样数，即 `source->nb_samples`；
- 返回非负值表示实际输出的每声道采样数；
- 返回负值表示转换失败；
- 输入结束时传 `in_arg = nullptr`、`in_count = 0`，用于排空内部延迟样本；
- `out_count` 不得超过实际分配的输出缓冲区容量。

---

## 11. AVAudioFifo：解决编码帧大小不匹配

`AVAudioFifo` 是按“每个声道的采样数”工作的音频 FIFO，不是普通的字节 FIFO。它位于解码器/重采样器和编码器之间，负责解决输入帧大小与编码器固定帧大小不同的问题。

> 实现状态说明：当前仓库中的 `app/src/main/cpp/ffmpeg_jni.cpp` 仍是“重采样后直接调用 `avcodec_send_frame()`”的旧实现，并没有真正创建 `AVAudioFifo`。因此，本节的 FIFO 代码是复现 MP3→FLAC 等固定帧编码转换时应采用的修正版参考实现；如果只按当前源码运行，仍可能出现“提交编码帧失败: Invalid argument”。

### 11.1 为什么需要 FIFO

输入解码器帧大小由输入格式决定，输出编码器帧大小由目标编码器决定：

```text
MP3 decoder frame: 1152 samples/channel
FLAC encoder frame: 4096 samples/channel
```

如果把 1152 个采样直接送给要求 4096 的 FLAC 编码器，avcodec_send_frame() 可能返回：

```cpp
AVERROR(EINVAL)
提交编码帧失败: Invalid argument
```

FIFO 将输入端任意大小的解码帧重新组织为编码器要求的帧：

```text
1152 -> FIFO
1152 -> FIFO
1152 -> FIFO
1152 -> FIFO
...
FIFO >= 4096
取出 4096 -> FLAC encoder
```

### 11.2 创建 FIFO

函数原型：

```cpp
AVAudioFifo *av_audio_fifo_alloc(
        enum AVSampleFormat sample_fmt,
        int channels,
        int nb_samples);
```

方法作用：创建一个保存 PCM 样本的 FIFO。

```cpp
#include <algorithm>

extern "C" {
#include "libavutil/audio_fifo.h"
#include "libavutil/samplefmt.h"
}

const int initialCapacity =
        std::max(encoder->frame_size, 1024);

AVAudioFifo *fifo = av_audio_fifo_alloc(
        encoder->sample_fmt,
        encoder->ch_layout.nb_channels,
        initialCapacity);
if (fifo == nullptr) {
    throw std::runtime_error(
            "无法创建音频 FIFO");
}
```

参数和返回值：

- `sample_fmt`：FIFO 中数据的采样格式，必须与写入数据的格式一致；
- `channels`：声道数量，必须与 `encoder->ch_layout.nb_channels` 一致；
- `nb_samples`：初始容量，单位是每个声道的采样数；
- 返回非空指针表示创建成功；
- 返回 `nullptr` 表示内存分配失败；
- FIFO 会自动扩容，但初始容量至少应覆盖一个编码帧；
- 使用 `av_audio_fifo_free(fifo)` 释放。

`sample_fmt` 的可选值与 `SwrContext` 相同，必须选择编码器实际接受的值，通常来自 `encoder->sample_fmt`；`channels` 可设置为 1、2、6、8 等正整数，但必须与声道布局和每个数据平面的含义一致；`nb_samples` 是初始容量，不是限制值，FIFO 空间不足时会自动扩容。项目推荐：

```cpp
sample_fmt = encoder->sample_fmt
channels   = encoder->ch_layout.nb_channels
nb_samples = max(encoder->frame_size, 1024)
```

不要把 `channels` 写成字节数，也不要把 `nb_samples` 写成所有声道样本数之和。

### 11.3 把重采样结果写入 FIFO

函数原型：

```cpp
int av_audio_fifo_write(
        AVAudioFifo *af,
        void *const *data,
        int nb_samples);
```

方法作用：把重采样后的 PCM 样本追加到 FIFO 尾部，供后续编码消费。

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

FIFO 的容量单位是每个声道的采样数，不是字节数。

参数和返回值：

- `af`：已创建的音频 FIFO；
- `data`：输入 PCM 的数据平面，必须与 FIFO 的 sample format 和声道数匹配；
- `nb_samples`：写入的每声道采样数；
- 返回实际写入的采样数；
- 返回值小于 `nb_samples` 表示写入不完整，应视为错误；
- packed/planar 数据都应通过 `frame->data` 传入，不能自行改变平面顺序。

### 11.4 从 FIFO 读取编码帧

在读取之前常用两个 API：

函数原型：

```cpp
int av_audio_fifo_size(const AVAudioFifo *af);
```

方法作用：查询 FIFO 当前可读取的每声道采样数。

参数和返回值：

- `af`：音频 FIFO；
- 返回非负的采样数；
- 返回 `0` 表示 FIFO 为空；
- 这个值不能当作字节数使用。

函数原型：

```cpp
int av_audio_fifo_read(
        AVAudioFifo *af,
        void *const *data,
        int nb_samples);
```

方法作用：从 FIFO 头部取出 PCM 样本，复制到编码帧的数据平面，并从 FIFO 中移除这些样本。

参数和返回值：

- `af`：音频 FIFO；
- `data`：目标编码帧的数据平面；
- `nb_samples`：要读取的每声道采样数；
- 返回实际读取的采样数；
- 返回值小于请求值表示读取不完整；
- 正常阶段应读取 `encoder->frame_size`，最终 drain 阶段才可能读取更小的尾帧。

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
    
        if (!finalDrain &&
            !variableFrameSize &&
            available < encoder->frame_size) {
            return;
        }
    
        int frameSamples = 0;
    
        if (variableFrameSize) {
            frameSamples = finalDrain
                    ? available
                    : std::min(
                            available,
                            encoder->frame_size > 0
                                ? encoder->frame_size
                                : available);
        } else if (finalDrain &&
                   available < encoder->frame_size &&
                   !smallLastFrame) {
            frameSamples = encoder->frame_size;
        } else {
            frameSamples = finalDrain
                    ? std::min(
                            available,
                            encoder->frame_size)
                    : encoder->frame_size;
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
    
        const int samplesAvailable =
                av_audio_fifo_size(fifo);
        const int samplesToRead =
                std::min(samplesAvailable,
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
            av_samples_set_silence(
                    frame->data,
                    samplesToRead,
                    frameSamples - samplesToRead,
                    encoder->ch_layout.nb_channels,
                    encoder->sample_fmt);
        } else {
            frame->nb_samples = samplesToRead;
        }
    
        *nextPts += samplesToRead;
    
        sendFrameAndWritePackets(
                encoder,
                output,
                stream,
                frame,
                packet);
    
        av_frame_free(&frame);
    }
}
```

FLAC 支持 AV_CODEC_CAP_SMALL_LAST_FRAME，因此最终小帧不需要补静音。对于不支持小尾帧的编码器，才需要补齐。

### 11.5 av_audio_fifo_free()

函数原型：

```cpp
void av_audio_fifo_free(AVAudioFifo *af);
```

方法作用：释放 FIFO 对象及其内部保存的 PCM 缓冲区。它不会释放写入 FIFO 的原始 `AVFrame`，因为 FIFO 已经复制了样本数据；调用方仍需按照自己的生命周期释放源帧。

参数含义：

- `af`：由 `av_audio_fifo_alloc()` 返回的 FIFO 指针；传入 `nullptr` 是安全的；
- 无返回值；
- 释放后不能继续调用 `av_audio_fifo_size()`、`av_audio_fifo_read()` 或 `av_audio_fifo_write()`；
- 异常路径和正常路径都必须调用。

---

## 12. 解码、编码和 flush

本节展示 FFmpeg send/receive API 的状态机。一次 `send` 不一定立即对应一次 `receive`，因此解码和编码都必须循环读取输出。

### 12.1 读取并解码压缩包

函数原型：

```cpp
int av_read_frame(
        AVFormatContext *s,
        AVPacket *pkt);
```

方法作用：从输入容器读取下一个压缩数据包。返回的包可能属于视频、音频、字幕或封面流，所以必须检查 `packet->stream_index`。

参数和返回值：

- `s`：已经打开并读取流信息的输入容器；
- `pkt`：输出包，成功后其中包含压缩数据和时间戳；
- 返回 `0` 或非负值表示读取成功；
- 返回 `AVERROR_EOF` 表示所有输入数据已经读完；
- 其他负值表示底层 IO 或解复用失败；
- 每次处理完包后都要调用 `av_packet_unref(pkt)`，否则会泄漏包数据。

```cpp
AVPacket *packet = av_packet_alloc();
AVFrame *decoded = av_frame_alloc();

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

函数原型：

```cpp
int avcodec_send_packet(
        AVCodecContext *avctx,
        const AVPacket *avpkt);
```

方法作用：把一个压缩包提交给解码器。解码器可能缓存包，必须随后循环调用 `avcodec_receive_frame()` 取出所有可用 PCM 帧。

参数和返回值：

- `avctx`：已经打开的解码器上下文；
- `avpkt`：压缩输入包；传 `nullptr` 表示输入结束，进入 decoder flush；
- 返回 `0` 表示包已接受；
- 返回 `AVERROR(EAGAIN)` 表示需要先读取解码器输出，再重新发送；
- 返回 `AVERROR_EOF` 表示解码器已经进入结束状态；
- 其他负值表示提交或解码错误。

函数原型：

```cpp
int avcodec_receive_frame(
        AVCodecContext *avctx,
        AVFrame *frame);
```

方法作用：从解码器取出一帧已经解码的 PCM。必须循环调用，直到返回 `EAGAIN` 或 `EOF`。

参数和返回值：

- `avctx`：已经打开的解码器上下文；
- `frame`：输出音频帧，FFmpeg 会填充 data、extended_data、nb_samples、format 等字段；
- 返回 `0` 表示成功得到一帧；
- 返回 `AVERROR(EAGAIN)` 表示当前包暂时没有更多帧；
- 返回 `AVERROR_EOF` 表示 decoder 已经结束；
- 其他负值表示解码失败；
- 处理完帧后可用 `av_frame_unref(frame)` 清空引用并复用帧对象。

即使是非音频包，也必须调用 av_packet_unref()。

### 12.2 刷新解码器

发送 `nullptr` 后，必须继续调用 `avcodec_receive_frame()`，直到收到 `EAGAIN` 或 `EOF`，否则 decoder 内部缓存的尾部帧会丢失。

```cpp
result = avcodec_send_packet(
        decoder,
        nullptr);
if (result < 0 && result != AVERROR_EOF) {
    throw std::runtime_error(
            "刷新解码器失败: " +
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
```

### 12.3 排空重采样器

本函数使用前面介绍的 `swr_get_delay()` 和 `swr_convert(..., nullptr, 0)`。它的作用是把 SwrContext 尚未输出的样本继续写入 FIFO，保证采样率转换不会丢失音频尾部。

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
                static_cast<int>(av_rescale_rnd(
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

忽略这一步可能导致采样率转换时输出尾部少几个采样。

### 12.4 完整刷新顺序

```text
刷新 decoder
  -> 排空 SwrContext
  -> 编码 FIFO 中的完整帧
  -> 编码 FIFO 中的最后小帧
  -> 刷新 encoder
  -> 写 trailer
```

---

## 13. 编码包和输出容器

编码侧的状态机是：

```cpp
avcodec_send_frame()
  -> avcodec_receive_packet() 循环
  -> av_packet_rescale_ts()
  -> av_interleaved_write_frame()
```

### 13.1 发送编码帧

函数原型：

```cpp
int avcodec_send_frame(
        AVCodecContext *avctx,
        const AVFrame *frame);
```

方法作用：把一帧未压缩 PCM 提交给目标编码器。编码器可能缓存这帧，输出必须通过 `avcodec_receive_packet()` 取出。

```cpp
int result = avcodec_send_frame(
        encoder,
        frame);
if (result < 0) {
    throw std::runtime_error(
            "提交编码帧失败: " +
            ffmpegError(result));
}
```

发送前应检查：

- frame->format 等于 encoder->sample_fmt；
- frame->sample_rate 正确；
- frame->ch_layout 有效；
- frame->nb_samples 符合 encoder->frame_size；
- 编码器没有进入 flush 状态。

参数和返回值：

- `avctx`：已经打开的目标编码器上下文；
- `frame`：待编码的 PCM 帧；传 `nullptr` 表示输入结束并刷新编码器；
- 返回 `0` 表示帧已接受；
- 返回 `AVERROR(EAGAIN)` 表示必须先调用 `avcodec_receive_packet()` 读取输出；
- 返回 `AVERROR_EOF` 表示编码器已经 flush，不能再发送普通帧；
- 返回 `AVERROR(EINVAL)` 常见于编码器未打开、帧参数无效或固定帧大小不匹配；
- 返回其他负值表示编码器内部错误。

### 13.2 接收编码包

函数原型：

```cpp
int avcodec_receive_packet(
        AVCodecContext *avctx,
        AVPacket *avpkt);
```

方法作用：从目标编码器取出已经生成的压缩包。一次发送一帧可能产生零个、一个或多个包，因此必须循环调用。

一次送帧可能产生 0 个、1 个或多个包，必须循环接收：

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

EAGAIN 表示当前没有更多输出，不是编码失败。

参数和返回值：

- `avctx`：已经打开的编码器上下文；
- `avpkt`：输出压缩包；成功后由编码器填充数据、大小、PTS、DTS 等字段；
- 返回 `0` 表示得到一个包；
- 返回 `AVERROR(EAGAIN)` 表示当前没有更多包，需要继续发送下一帧；
- 返回 `AVERROR_EOF` 表示编码器已经完成；
- 其他负值表示编码失败；
- 写入容器后必须调用 `av_packet_unref(packet)`，以便复用包。

### 13.3 刷新编码器

发送 `nullptr` 后，编码器进入 flush 状态。必须继续调用 `avcodec_receive_packet()`，直到没有更多输出。

```cpp
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
```

### 13.4 写入文件尾

函数原型：

```cpp
int av_write_trailer(
        AVFormatContext *s);
```

方法作用：结束输出容器，写入文件尾、最终索引、数据长度或编码器收尾信息。它是一次成功转换的最后一个 FFmpeg 写操作。

```cpp
result = av_write_trailer(output);
if (result < 0) {
    throw std::runtime_error(
            "无法写入输出文件尾: " +
            ffmpegError(result));
}
```

不调用 trailer 可能导致 WAV、Ogg、FLAC 等文件缺少收尾信息。

参数和返回值：

- `s`：已经写过 header 的输出容器上下文；
- 返回 `0` 表示文件尾写入成功；
- 返回负值表示底层 IO 或封装器写入失败；
- 成功或失败后都要关闭 IO 并释放输出上下文；
- 只有在 header 成功并且转换流程正常结束时，才将该文件复制到用户选择的 URI。

### 13.5 av_interleaved_write_frame()

函数原型：

```cpp
int av_interleaved_write_frame(
        AVFormatContext *s,
        AVPacket *pkt);
```

方法作用：把一个已经编码的压缩包交给输出容器。对于包含多条流的容器，FFmpeg 会根据时间戳进行交错；当前项目只有音频流，但仍然使用该通用 API。

参数和返回值：

- `s`：已经写过 header 的输出容器上下文；
- `pkt`：编码器产生的输出包，写入前应已经完成时间基转换并设置 `stream_index`；
- 返回 `0` 表示写入成功；
- 返回负值表示容器封装或底层 IO 失败；
- 函数可能接管或引用包中的数据，调用返回后应使用 `av_packet_unref(pkt)` 准备复用。

写包的标准顺序：

```cpp
avcodec_receive_packet()
  -> av_packet_rescale_ts()
  -> packet->stream_index = outputStream->index
  -> av_interleaved_write_frame()
  -> av_packet_unref()
```

---

## 14. 帧、数据包和声道布局 API

这些 API 是转码循环中的基础内存和参数操作。它们本身不执行解码或编码，但使用不正确会导致崩溃、内存泄漏或 `EINVAL`。

### 14.1 av_frame_alloc()

函数原型：

```cpp
AVFrame *av_frame_alloc(void);
```

方法作用：分配一个空的 `AVFrame` 结构体。它只分配帧对象，不会自动分配音频数据缓冲区。

参数和返回值：

- 无参数；
- 返回非空指针表示成功；
- 返回 `nullptr` 表示内存不足；
- 使用 `av_frame_free(&frame)` 释放；
- 要复用已经分配的帧，可以用 `av_frame_unref(frame)` 清除旧数据引用。

### 14.2 av_frame_get_buffer()

函数原型：

```cpp
int av_frame_get_buffer(
        AVFrame *frame,
        int align);
```

方法作用：根据 `frame->format`、`frame->nb_samples`、`frame->ch_layout` 等字段分配音频数据平面。

调用前必须先设置：

```cpp
frame->format
frame->sample_rate
frame->nb_samples
frame->ch_layout
```

参数和返回值：

- `frame`：已经填写音频格式描述的帧；
- `align`：数据对齐要求，项目传 `0` 表示使用默认对齐；
- 返回 `0` 表示缓冲区分配成功；
- 返回负值表示参数非法或内存分配失败；
- 分配后的数据应通过 `frame->data`、`frame->extended_data` 访问，不要自行计算平面地址。

`align` 的常见设置：

| 值 | 含义 | 建议 |
| --- | --- | --- |
| `0` | 使用 FFmpeg 默认对齐方式 | 本项目和大多数普通音频处理场景使用 |
| `1` | 最小对齐，不额外要求特定边界 | 只有明确不需要 SIMD 对齐时使用 |
| `16`、`32` 等正整数 | 要求数据地址/行大小按指定边界对齐 | 只有底层 SIMD 或硬件接口明确要求时使用 |

`align` 不是采样位数，也不是声道数；不要把 `16` 误写成“16 位采样”。

### 14.3 av_frame_unref() 和 av_frame_free()

函数原型：

```cpp
void av_frame_unref(AVFrame *frame);
void av_frame_free(AVFrame **frame);
```

方法作用：

- `av_frame_unref()`：释放帧引用的数据和 side data，但保留帧对象，适合循环复用；
- `av_frame_free()`：释放帧对象本身，并将指针置为 `nullptr`。

参数含义：

- `frame`：待清空或释放的帧；
- `av_frame_free()` 使用二级指针，是为了在释放后自动把调用方指针置空。

典型用法：

```cpp
while (avcodec_receive_frame(decoder, decoded) >= 0) {
    process(decoded);
    av_frame_unref(decoded);
}
```

### 14.4 av_packet_alloc()、av_packet_unref() 和 av_packet_free()

函数原型：

```cpp
AVPacket *av_packet_alloc(void);
void av_packet_unref(AVPacket *pkt);
void av_packet_free(AVPacket **pkt);
```

方法作用：

- `av_packet_alloc()`：分配一个空的压缩数据包对象；
- `av_packet_unref()`：释放包引用的数据，保留包对象供下一次读取或编码复用；
- `av_packet_free()`：释放包对象本身。

参数和返回值：

- `av_packet_alloc()` 无参数，返回空指针表示分配失败；
- `av_packet_unref(pkt)` 的 `pkt` 必须是有效包指针；
- `av_packet_free(&pkt)` 释放后会把调用方指针置空；
- `av_read_frame()`、`avcodec_receive_packet()` 使用同一个包对象时，每次处理结束都必须 `av_packet_unref()`。

### 14.5 av_channel_layout_copy()

函数原型：

```cpp
int av_channel_layout_copy(
        AVChannelLayout *dst,
        const AVChannelLayout *src);
```

方法作用：复制声道布局，包括声道数量、布局类型和具体声道信息。

参数和返回值：

- `dst`：目标声道布局；
- `src`：源声道布局；
- 返回 `0` 表示复制成功；
- 返回负值表示布局无效或内存分配失败；
- 复制完成后，目标布局拥有自己的资源，适当时应调用 `av_channel_layout_uninit()`。

项目中的典型用途：

```cpp
av_channel_layout_copy(
        &encoder->ch_layout,
        &decoder->ch_layout);
```

### 14.6 av_channel_layout_default()

函数原型：

```cpp
void av_channel_layout_default(
        AVChannelLayout *ch_layout,
        int nb_channels);
```

方法作用：根据声道数量生成默认布局。例如 1 个声道生成 mono，2 个声道生成 stereo。

参数和返回值：

- `ch_layout`：输出布局；
- `nb_channels`：声道数量，必须大于 0；
- 该函数没有返回值；调用后通过 `ch_layout->nb_channels` 和布局字段确认结果；
- `nb_channels` 无效时不会提供可检查的错误码，因此调用前必须保证声道数大于 0。

当输入文件没有可靠的声道布局，但知道声道数时，可以用它作为回退布局。

### 14.7 av_channel_layout_uninit()

函数原型：

```cpp
void av_channel_layout_uninit(
        AVChannelLayout *channel_layout);
```

方法作用：释放声道布局内部可能持有的动态资源，并将布局恢复为未初始化状态。

参数含义：

- `channel_layout`：需要清理的布局；
- 可安全用于已经是空布局的对象；
- 对通过 `av_channel_layout_copy()`、`av_channel_layout_default()` 或其他 API 初始化的布局，在不再使用时调用。

### 14.8 av_packet_rescale_ts()

函数原型：

```cpp
void av_packet_rescale_ts(
        AVPacket *pkt,
        AVRational tb_src,
        AVRational tb_dst);
```

方法作用：把包的 PTS、DTS 和 duration 从一个时间基转换到另一个时间基。

参数含义：

- `pkt`：需要转换时间戳的编码包；
- `tb_src`：包当前使用的时间基，项目中是 `encoder->time_base`；
- `tb_dst`：包即将写入的流时间基，项目中是 `outputStream->time_base`；
- 该函数没有返回值；
- 必须在 `av_interleaved_write_frame()` 前调用，否则输出时长和播放速度可能错误。

---

### 14.9 av_channel_layout_describe()

函数原型：

```cpp
int av_channel_layout_describe(
        const AVChannelLayout *channel_layout,
        char *buf,
        size_t buf_size);
```

方法作用：把声道布局转换成人类可读的字符串，例如 `mono`、`stereo` 或 `5.1`，主要用于日志、probe 结果和错误信息；它不改变布局，也不分配返回字符串。

参数含义：

- `channel_layout`：待描述的声道布局；
- `buf`：调用方提供的字符缓冲区；
- `buf_size`：`buf` 的字节容量；
- 返回值是写入完整字符串所需的字节数；如果返回值大于 `buf_size`，说明字符串被截断；负值表示布局无效或其他错误；
- 如果返回失败，不能直接把 `buf` 当成有效布局名称。

### 14.10 av_get_sample_fmt_name()

函数原型：

```cpp
const char *av_get_sample_fmt_name(
        enum AVSampleFormat sample_fmt);
```

方法作用：把采样格式枚举转换为名称，例如 `s16`、`fltp` 或 `s16p`，用于日志和调试。返回的字符串由 FFmpeg 管理，调用方不能释放。

参数含义：

- `sample_fmt`：采样格式枚举值；
- 返回格式名称的常量指针；
- 返回 `nullptr` 表示传入的枚举值无效；
- 该函数只查询名称，不改变音频数据，也不执行格式转换。

### 14.11 av_rescale_rnd() 和 av_rescale_q()

函数原型：

```cpp
int64_t av_rescale_rnd(
        int64_t a,
        int64_t b,
        int64_t c,
        enum AVRounding rnd);

int64_t av_rescale_q(
        int64_t a,
        AVRational bq,
        AVRational cq);
```

方法作用：执行带 64 位整数保护的比例换算。`av_rescale_rnd()` 计算约为 `a * b / c` 的值，并根据 `rnd` 指定舍入方式；`av_rescale_q()` 按两个 `AVRational` 时间基换算时间戳或时长。

参数含义：

- `a`：待换算的数值，音频转换中通常是样本数、时间戳或容器时长；
- `b`、`c`：比例的分子和分母，必须避免 `c == 0`；
- `rnd`：舍入策略，例如 `AV_ROUND_UP` 表示向上取整，重采样输出容量通常使用它避免缓冲区不足；
- `bq`：输入时间基；
- `cq`：输出时间基；
- 返回换算后的整数值；这些函数没有错误码返回，因此调用前必须保证时间基和分母有效。

`AVRounding` 的常见值：

| 值 | 含义 | 音频转换中的用途 |
| --- | --- | --- |
| `AV_ROUND_ZERO` | 向零舍入 | 可能丢掉不足一个单位的余量 |
| `AV_ROUND_INF` | 向无穷远舍入 | 不常用于容量计算 |
| `AV_ROUND_DOWN` | 向负无穷舍入 | 时间戳特殊处理 |
| `AV_ROUND_UP` | 向正无穷舍入 | 计算重采样输出容量，避免缓冲区不足 |
| `AV_ROUND_NEAR_INF` | 向最近整数舍入，正好中间时远离零 | 一般时长换算 |
| `AV_ROUND_PASS_MINMAX` | 保留 `AV_NOPTS_VALUE` 等特殊极值 | 时间戳换算时与其他舍入位组合 |

这些值中，前五个基础舍入方式互斥；`AV_ROUND_PASS_MINMAX` 是附加标志，可以与基础方式按位或组合。容量计算通常使用 `AV_ROUND_UP`，而不是默认的近似舍入。

### 14.12 av_samples_set_silence()

函数原型：

```cpp
int av_samples_set_silence(
        uint8_t *const *audio_data,
        int offset,
        int nb_samples,
        int nb_channels,
        enum AVSampleFormat sample_fmt);
```

方法作用：把音频数据平面的一段区域填充为静音。它只适用于编码器不接受小尾帧、需要把最后不足 `frame_size` 的样本补齐的场景。

参数含义：

- `audio_data`：目标音频数据平面；
- `offset`：从每个声道的第几个样本开始填充；
- `nb_samples`：需要填充的每声道样本数；
- `nb_channels`：声道数；
- `sample_fmt`：数据实际使用的采样格式，必须与缓冲区匹配；
- 返回 `0` 表示成功，负值表示参数非法。

不要在 FLAC 等支持 `AV_CODEC_CAP_SMALL_LAST_FRAME` 的编码器上无条件补静音，否则可能使输出尾部比输入更长。

### 14.13 av_strerror()

函数原型：

```cpp
int av_strerror(
        int errnum,
        char *errbuf,
        size_t errbuf_size);
```

方法作用：把 FFmpeg 的负错误码转换为可读文本，例如把 `AVERROR(EINVAL)` 转成 `Invalid argument`。它不改变错误码，也不会抛出 C++ 异常。

参数含义：

- `errnum`：FFmpeg 返回的错误码；
- `errbuf`：调用方提供的输出字符缓冲区；
- `errbuf_size`：输出缓冲区字节数；
- 返回 `0` 表示文本写入成功；
- 失败时应使用固定的兜底错误信息，不能读取未初始化的缓冲区。

### 14.14 资源释放 API

以下函数不负责处理音频，但决定 native 转换是否稳定。二级指针形式的释放函数会在释放后把调用方指针置为 `nullptr`。

| API | 参数含义 | 方法作用 |
| --- | --- | --- |
| `avformat_close_input(AVFormatContext **s)` | 输入容器上下文地址 | 关闭输入 IO，并释放输入容器及其流信息 |
| `avformat_free_context(AVFormatContext *s)` | 输出或未打开的格式上下文 | 释放格式上下文；输出 IO 关闭前不能调用 |
| `avcodec_free_context(AVCodecContext **avctx)` | 编解码器上下文地址 | 关闭并释放解码器或编码器上下文 |
| `avio_closep(AVIOContext **s)` | 输出 IO 上下文地址 | 关闭文件并释放 `AVIOContext` |
| `swr_free(SwrContext **s)` | 重采样器地址 | 释放重采样器及内部缓冲 |
| `av_frame_free(AVFrame **frame)` | 帧地址 | 释放帧对象及其引用的数据 |
| `av_packet_free(AVPacket **pkt)` | 包地址 | 释放包对象及其引用的数据 |
| `av_audio_fifo_free(AVAudioFifo *af)` | FIFO 指针 | 释放 FIFO 和内部 PCM 缓冲 |

参数和调用顺序的关键点：

1. 输入上下文必须用 `avformat_close_input()`，不能只用 `avformat_free_context()` 代替。
2. 输出上下文的 `output->pb` 在普通文件格式下先用 `avio_closep(&output->pb)` 关闭，再用 `avformat_free_context(output)` 释放。
3. `av_packet_unref()` 和 `av_frame_unref()` 只清除当前数据引用、保留对象；`*_free()` 才释放对象本身。
4. 无论转换成功、FFmpeg 返回错误，还是 C++ 抛出异常，都必须执行同样的释放逻辑。

---

## 15. MP3 转 FLAC 错误详解

项目出现过：

```text
提交编码帧失败: Invalid argument
```

这条错误来自 avcodec_send_frame() 返回负值。它不是 Android MediaCodec 错误，通常也不是找不到 FLAC 编码器。

### 15.1 错误链路

MP3 解码器可能输出：

```text
nb_samples = 1152
```

FLAC 编码器可能要求：

```text
frame_size = 4096
```

旧实现直接发送：

```text
converted->nb_samples = samples;
encodeFrame(
        encoder,
        output,
        outputStream,
        converted,
        packet);
```

如果 samples 为 1152，而编码器要求 4096，avcodec_send_frame() 可能返回 AVERROR(EINVAL)。

### 15.2 正确修复

在 decoder 和 encoder 之间加入 AVAudioFifo：

```cpp
解码帧 1152
  -> 重采样
  -> FIFO

解码帧 1152
  -> 重采样
  -> FIFO

FIFO 累计到 4096
  -> 读取 4096
  -> 创建编码 AVFrame
  -> 送给 FLAC 编码器
```

不要把一个解码帧当成一个编码帧。

### 15.3 编码器能力标志

如果编码器具有 AV_CODEC_CAP_VARIABLE_FRAME_SIZE，可以接受不同数量的样本。

如果没有该能力，普通帧必须满足：

```cpp
frame->nb_samples == encoder->frame_size
```

如果编码器具有 AV_CODEC_CAP_SMALL_LAST_FRAME，可以接受最后一个小于 frame_size 的尾帧。FLAC 支持小尾帧，因此最后一帧按实际剩余样本数提交，不应无条件补静音。

### 15.4 编码器 capability 标志的可选值

`encoder->codec->capabilities` 是位掩码，不是只能取一个值的普通枚举。当前修正版重点使用以下标志：

| 标志 | 含义 | 对 FIFO/flush 的影响 |
| --- | --- | --- |
| `AV_CODEC_CAP_VARIABLE_FRAME_SIZE` | 编码器允许每帧使用不同数量的样本 | `frame_size` 可能为 0，可以按 FIFO 当前可用样本数组帧 |
| `AV_CODEC_CAP_SMALL_LAST_FRAME` | 编码器允许最后一帧小于固定 `frame_size` | drain 时可以直接提交尾部小帧，不必补静音 |
| `AV_CODEC_CAP_DELAY` | 编解码器内部可能缓存数据 | 输入结束必须 flush，继续 receive 直到 `EAGAIN/EOF` |
| `AV_CODEC_CAP_EXPERIMENTAL` | 编解码器被标记为实验性 | 必要时设置 `strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL` |
| `AV_CODEC_CAP_ENCODER_FLUSH` | 编码器支持通过 `avcodec_flush_buffers()` 清空内部状态 | 需要复用同一编码器上下文时，可以调用 flush buffers；正常文件结尾仍要发送空帧并 drain 输出包 |

判断位标志的方法：

```cpp
const bool variableFrameSize =
        (encoder->codec->capabilities &
         AV_CODEC_CAP_VARIABLE_FRAME_SIZE) != 0;
```

多个能力可以同时存在，因此不要使用 `capabilities == AV_CODEC_CAP_SMALL_LAST_FRAME` 这种相等比较；应使用按位与判断某一位是否存在。

---

## 16. 五种目标格式的编码特点

### 16.1 MP3

```cpp
codecName = "libmp3lame";
encoder->bit_rate = 192000;
```

特点：

- 有损编码；
- 常见扩展名为 .mp3；
- 码率影响音质和文件大小；
- 可能有 encoder delay 和尾部 padding；
- MP3→MP3 会再次有损编码。

### 16.2 WAV

```cpp
codecName = "pcm_s16le";
encoder->sample_fmt = AV_SAMPLE_FMT_S16;
```

特点：

- 通常是未压缩 PCM；
- 文件较大；
- 适合编辑和中间处理；
- 本项目的 wav 目标具体表示 signed 16-bit little-endian PCM；
- 不代表所有 WAV 子格式。

### 16.3 FLAC

```cpp
codecName = "flac";
```

特点：

- 无损压缩；
- 比 WAV 更节省空间；
- 不能恢复有损输入已经丢失的信息；
- 需要正确处理固定帧大小、FIFO 和最后小帧。

### 16.4 OGG/Vorbis

```cpp
codecName = "libvorbis";
encoder->bit_rate = 128000;
```

特点：

- Ogg 是容器；
- Vorbis 是实际编码；
- 项目输出扩展名为 .ogg；
- OGG→OGG 仍然是重新编码。

### 16.5 OPUS

```cpp
codecName = "libopus";
encoder->bit_rate = 96000;
```

特点：

- 有损编码；
- 适合语音、音乐和网络传输；
- 常见目标采样率为 48000 Hz；
- 输出通常是 Ogg/Opus；
- 项目扩展名为 .opus。

如果输入是 44100 Hz，实际实现通常将 Opus 编码器采样率统一为 48000 Hz，再使用 SwrContext 重采样。

---

## 17. 25 种组合的行为说明

### 17.1 MP3 作为输入

```text
MP3 -> MP3   解码后再次使用 libmp3lame，有损重编码
MP3 -> WAV   解码后输出 pcm_s16le
MP3 -> FLAC  解码后经 FIFO 对齐，再使用 FLAC 无损压缩
MP3 -> OGG   解码后使用 libvorbis 有损编码
MP3 -> OPUS  解码后重采样，再使用 libopus 编码
```

MP3→FLAC 只能无损保存 MP3 解码后的结果。

### 17.2 WAV 作为输入

```text
WAV -> MP3   PCM 经过 libmp3lame 有损压缩
WAV -> WAV   统一为 pcm_s16le 后重新写 WAV
WAV -> FLAC  PCM 无损压缩
WAV -> OGG   使用 Vorbis 有损压缩
WAV -> OPUS  重采样后使用 Opus 压缩
```

WAV→WAV 不是原文件复制，输出参数由 encoder 配置决定。

### 17.3 FLAC 作为输入

```text
FLAC -> MP3   解码后进行 MP3 有损压缩
FLAC -> WAV   解码后输出 pcm_s16le
FLAC -> FLAC  解码后重新 FLAC 编码，音频内容仍然无损
FLAC -> OGG   使用 Vorbis 有损压缩
FLAC -> OPUS  使用 Opus 有损压缩
```

### 17.4 OGG/Vorbis 作为输入

```text
OGG -> MP3   Vorbis 解码后使用 MP3 编码
OGG -> WAV   解码后输出 pcm_s16le
OGG -> FLAC  解码后无损压缩当前 PCM
OGG -> OGG   Vorbis 解码后再次 Vorbis 编码
OGG -> OPUS  Vorbis 解码后使用 Opus 编码
```

### 17.5 OPUS 作为输入

```text
OPUS -> MP3   Opus 解码后使用 MP3 编码
OPUS -> WAV   Opus 解码后输出 pcm_s16le
OPUS -> FLAC  Opus 解码后无损压缩当前 PCM
OPUS -> OGG   Opus 解码后使用 Vorbis 编码
OPUS -> OPUS  Opus 解码后再次 Opus 编码
```

无损输出只能保证不再增加新的量化损失，不能恢复有损输入已经丢失的信息。

---

## 18. 时间戳和音频时长

### 18.1 编码器时间基

项目通常使用：

```cpp
encoder->time_base =
        AVRational{1, encoder->sample_rate};
```

对于 44100 Hz：

```text
PTS = 0       -> 0 秒
PTS = 44100   -> 1 秒
PTS = 88200   -> 2 秒
```

### 18.2 按采样数推进 PTS

```cpp
frame->pts = nextPts;
nextPts += frame->nb_samples;
```

nextPts 表示已经提交给编码器的每声道样本数。

### 18.3 写包前转换时间基

```cpp
av_packet_rescale_ts(
        packet,
        encoder->time_base,
        outputStream->time_base);
```

忘记转换可能导致播放速度错误、时长异常或时间戳不递增。

MP3、Opus 等编码器还可能有编码延迟和尾部 padding。实现裁剪或拼接时，要额外考虑 input start_time、decoder delay、encoder delay、end padding 和容器中的有效样本数。

---

## 19. 元数据、封面和 Stream Copy

### 19.1 复制标签

流级标签：

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

容器级标签应从 input->metadata 复制到 output->metadata。

### 19.2 封面

MP3 或 FLAC 的封面可能是额外的视频流或图片块，不是普通音频帧的一部分。仅复制音频流 metadata 不能完整保留封面，需要单独读取和封装。

### 19.3 Stream copy

如果只想换容器而不重新编码，可以采用：

```cpp
输入复用器 -> AVPacket -> 输出复用器
```

当前 convert() 会运行 decoder、SwrContext 和 encoder，不是 stream copy。

---

## 20. 资源生命周期

主要资源：

```cpp
AVFormatContext *input
AVCodecContext *decoder
AVFormatContext *output
AVCodecContext *encoder
SwrContext *resampler
AVAudioFifo *fifo
AVPacket *packet
AVFrame *decoded
AVFrame *converted
```

推荐释放顺序：

```cpp
AVFrame / AVPacket
AVAudioFifo
SwrContext
encoder AVCodecContext
decoder AVCodecContext
output AVIOContext
output AVFormatContext
input AVFormatContext
```

对应 API：

```cpp
av_frame_free(&frame);
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

成功和异常路径都必须释放资源。Kotlin 层在 finally 中删除临时输出文件。

---

## 21. CMake、ABI 和静态库

项目通过 ffmpeg_jni 动态库链接 FFmpeg 静态库：

```cmake
add_library(ffmpeg_jni SHARED ffmpeg_jni.cpp)

set(FFMPEG_ROOT ...)
set(FFMPEG_INCLUDE_DIR ...)
set(FFMPEG_LIB_DIR ...)

target_include_directories(
        ffmpeg_jni
        PRIVATE
        FFMPEG_INCLUDE_DIR
)

set(FFMPEG_STATIC_LIBS
        libavfilter.a
        libavformat.a
        libavcodec.a
        libswresample.a
        libswscale.a
        libavutil.a
        libvorbisenc.a
        libvorbis.a
        libopus.a
        libmp3lame.a
        libogg.a
)

target_link_libraries(
        ffmpeg_jni
        PRIVATE
        "-Wl,--start-group"
        FFMPEG_STATIC_LIBS
        "-Wl,--end-group"
        log
        m
        z
        dl
        android
)
```

实际项目中的 CMake 变量路径请以 app/src/main/cpp/CMakeLists.txt 为准。

库职责：

| 库 | 作用 |
| --- | --- |
| libavformat | 解复用、封装、文件头、时间戳和 IO |
| libavcodec | 音频解码器和编码器 |
| libavutil | AVFrame、采样格式、声道布局和 FIFO |
| libswresample | 重采样、声道转换和采样格式转换 |
| libavfilter | 音频滤镜 |
| libmp3lame | MP3 编码器依赖 |
| libvorbis、libvorbisenc | Vorbis 编码器依赖 |
| libopus | Opus 编码器依赖 |
| libogg | Ogg 相关依赖 |

FLAC 编码器由 libavcodec 提供，当前不需要额外链接 libFLAC。

当前 ABI：

```groovy
ndk {
    abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86_64'
}
```

每个 ABI 都必须存在对应的 app/src/main/cpp/ffmpeg/static/<abi> 静态库。

---

## 22. 错误排查

| 错误 | 常见原因 | 检查方法 |
| --- | --- | --- |
| 无法打开输入文件 | 路径错误、文件为空、复制失败 | 检查 File.isFile 和 length |
| 文件中没有音频流 | 输入不是音频或没有音轨 | 检查流列表 |
| 找不到目标编码器 | 构建时未包含编码器 | 检查 avcodec_find_encoder_by_name |
| 无法打开编码器 | 采样率、声道或格式不支持 | 检查 encoder 参数 |
| 提交编码帧失败 | nb_samples 不符合 frame_size | 检查 FIFO、frame_size 和 frame 参数 |
| 音频重采样失败 | 输入或输出参数无效 | 检查 swr_alloc_set_opts2 |
| 写入输出文件失败 | 路径不可写或空间不足 | 检查临时目录 |
| 输出无法播放 | header、trailer 或时间基错误 | 检查输出 API 顺序 |
| 输出短少尾部 | 未刷新 decoder 或 SwrContext | 补充 flush 和 drain |
| 输出多出静音 | 末帧无条件补齐 | 仅在需要时补齐 |

送帧失败时建议记录：

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

错误信息：

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

---

## 23. 五格式转换矩阵测试

建议建立矩阵测试：

```cpp
for input in mp3 wav flac ogg opus:
    for output in mp3 wav flac ogg opus:
        执行转换
        probe 输出
        再次解码输出
        检查大小、时长、采样率、声道和编码器
```

每个组合至少验证：

1. native 调用在后台线程；
2. 输入文件非空；
3. 输出文件大小大于 0；
4. 输出编码器与目标格式匹配；
5. 输出容器与扩展名匹配；
6. 输出可以被 probe；
7. 输出可以被重新解码；
8. 输出时长基本正确；
9. 转换失败时临时文件被删除；
10. 没有明显 native 内存或文件描述符泄漏。

建议额外测试：

- 短音频；
- 长音频；
- 单声道；
- 双声道；
- 44100 Hz；
- 48000 Hz；
- 末尾不足一个编码帧的文件；
- 中文路径；
- 带标签的文件；
- 带封面的文件；
- 输入输出格式相同的场景。

---

## 24. 总结

1. 扩展名不等于编码格式，真正转换必须经过解码和重新编码。
2. AVPacket 是压缩数据，AVFrame 是 PCM 数据。
3. 输入解码帧大小和输出编码帧大小通常不同。
4. AVAudioFifo 用于采样级缓存和重新分帧。
5. 采样率、采样格式和声道布局必须同时配置。
6. SwrContext 输入参数来自 decoder，输出参数来自 encoder。
7. receive_frame 和 receive_packet 返回 EAGAIN 通常是正常状态。
8. 写包前必须使用 av_packet_rescale_ts 转换时间基。
9. 输入结束后必须依次刷新 decoder、SwrContext、FIFO 和 encoder。
10. Android content URI 先复制成临时文件，native 层只处理普通路径。
11. 当前 UI 支持 MP3、WAV、FLAC、OGG/Vorbis、OPUS 五种输出格式，共有 25 种五格式之间的转换组合。
12. FLAC 是无损编码，但不能恢复 MP3、Vorbis 或 Opus 已经损失的音频信息。
