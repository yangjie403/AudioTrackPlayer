# 音频编辑 Native 实现说明

本文只介绍音频编辑功能的 native 相关代码，不介绍 Activity、Compose 页面、波形控件布局或文件选择页面。
涉及的主要文件是：

- `app/src/main/java/com/bigjelly/temporun/player/FfmpegBridge.kt`：Kotlin 到 JNI 的 API 封装。
- `app/src/main/cpp/ffmpeg_jni.cpp`：FFmpeg 解码、滤镜、重采样、FIFO、编码和 JNI 异常处理。
- `app/src/main/cpp/CMakeLists.txt`：FFmpeg 静态库、JNI 动态库和链接参数配置。

## 1. Native 层解决的问题

编辑功能的 Kotlin 代码只负责传入文件路径和处理参数，真正的音频处理由 FFmpeg 完成：

1. 读取输入容器，找到最佳音频流。
2. 使用对应解码器把压缩数据解码成 `AVFrame`。
3. 根据功能把音频帧送入滤镜图，完成裁剪、变速、变调或拼接。
4. 把滤镜输出统一成编码器可以稳定接收的音频格式。
5. 通过 `AVAudioFifo` 按编码器要求的帧大小组装 PCM 帧。
6. 使用 `libmp3lame` 编码为 MP3，并把编码包写入输出容器。
7. 在所有失败路径释放 FFmpeg 资源，并将错误转换为 Kotlin 异常。

编辑操作不是对 MP3 字节做简单截取。MP3 是带帧结构和编码延迟的有损压缩格式，任意毫秒位置通常需要先解码，再按采样点处理，最后重新编码。因此裁剪、变速变调和拼接默认属于“解码—处理—重新编码”流程。

## 2. Kotlin 与 JNI 的边界

### 2.1 为什么 native 接收普通文件路径

`avformat_open_input()` 接收的是文件系统路径。Android 的 `content://` 是由文档提供者管理的 Uri，不一定对应 native 可以直接打开的真实路径，所以 `FfmpegBridge` 的 API 约定如下：

- `inputPath` 必须是应用可读的普通文件路径。
- `outputPath` 必须是应用可写的普通文件路径。
- native 层不直接处理 `Uri`、`ContentResolver` 或 SAF 权限。
- Android 侧应先把 Uri 内容复制到 `cacheDir`，native 写完后再把普通输出文件复制到目标 Uri。

临时文件的扩展名不是主要依据。FFmpeg 会通过文件内容、容器探测和音频流参数识别格式；不过输出文件仍建议使用与目标容器相符的扩展名，例如 `.mp3`。

### 2.2 JNI 方法映射

Kotlin 对象中的 external 方法与 C++ 导出函数一一对应：

| Kotlin API | JNI 函数 | C++ 内部实现 |
| --- | --- | --- |
| `version()` | `nativeVersion` | `av_version_info()` |
| `configuration()` | `nativeConfiguration` | `avformat_configuration()` |
| `probe(path)` | `nativeProbe` | `probeFile()` |
| `convert(input, output, format)` | `nativeConvert` | `transcodeFile()` |
| `waveform(path, pointCount)` | `nativeWaveform` | `waveformFile()` |
| `trim(input, output, startMs, endMs)` | `nativeTrim` | `buildSingleFilter()` + `transcodeFiltered()` |
| `tempoPitch(...)` | `nativeTempoPitch` | `buildSingleFilter()` + `transcodeFiltered()` |
| `concat(first, second, output)` | `nativeConcat` | `concat` 滤镜 + `transcodeFiltered()` |

JNI 函数名称使用完整包名：

```text
Java_com_bigjelly_temporun_player_FfmpegBridge_nativeTrim
```

如果修改 Kotlin 包名、对象名或 external 方法名，JNI 导出名称也必须同步修改；否则运行到 `System.loadLibrary()` 后调用方法时会出现 `UnsatisfiedLinkError`。

### 2.3 参数校验与异常

Kotlin 层做第一轮参数校验，C++ JNI 入口再做一轮校验。这样即使未来出现其他调用方，也不会绕过 native 的安全边界。

- 空路径：拒绝处理。
- `startMs < 0`：拒绝处理。
- `endMs <= startMs`：拒绝处理。
- `speed` 必须是有限值且大于 0。
- `pitchSemitones` 必须是有限值。
- `pointCount` 必须大于 0。

C++ 处理异常时使用两种 Java 异常：

- `IllegalArgumentException`：参数为空或参数范围无效。
- `IOException`：无法打开文件、找不到编解码器、滤镜失败、编码失败或写文件失败。

`ffmpegError()` 用 `av_strerror()` 把 FFmpeg 负错误码转换成可读文本，因此上层通常能看到类似“无法配置音频滤镜：xxx”的具体原因。

## 3. FfmpegBridge API 说明

### 3.1 `version()`

```kotlin
val version = FfmpegBridge.version()
```

返回 FFmpeg 版本字符串。它不参与编辑逻辑，适合在诊断页面或日志中确认当前加载的 native 库版本。

### 3.2 `configuration()`

```kotlin
val configuration = FfmpegBridge.configuration()
```

返回 FFmpeg 编译配置。排查“找不到 `libmp3lame`”“滤镜不可用”等问题时，可以检查构建参数和静态库是否包含所需组件。

### 3.3 `probe(path)`

```kotlin
val metadata = FfmpegBridge.probe(inputPath)
```

`probe()` 打开文件并调用 `avformat_find_stream_info()`，再使用 `av_find_best_stream()` 查找最佳音频流。返回的 `FfmpegProbe` 字段如下：

| 字段 | 含义 | 来源或单位 |
| --- | --- | --- |
| `durationMs` | 媒体时长 | 毫秒；优先使用容器时长，否则使用音频流时长 |
| `format` | 容器短名称 | 如 `mp3`、`wav` |
| `formatLong` | 容器描述 | FFmpeg 提供的长名称 |
| `bitRate` | 输入码率 | bit/s；可能来自音频流或容器 |
| `codec` | 编码器短名称 | 如 `mp3`、`aac` |
| `codecLong` | 编码器描述 | FFmpeg descriptor 的长名称 |
| `sampleRate` | 采样率 | Hz |
| `channels` | 声道数 | 例如 1 或 2 |
| `channelLayout` | 声道布局 | 例如 `mono`、`stereo` |
| `sampleFmt` | 容器声明的采样格式 | 例如 `fltp`、`s16` |
| `streams` | 容器流数量 | 视频、音频、字幕等全部流 |
| `tags` | 音频流标签 | 键值对 |

某些容器不提供完整时长、码率或采样格式，返回值可能为 0 或空字符串，调用方不能把这些字段当成一定存在。

### 3.4 `waveform(path, pointCount)`

```kotlin
val points = FfmpegBridge.waveform(inputPath, pointCount = 160)
```

参数：

- `path`：普通输入文件路径。
- `pointCount`：返回数组长度，默认 160；数值越大，波形细节越多，同时返回数据和绘制工作也会增加。

实现过程：

1. `openFilterInput()` 打开容器和音频解码器。
2. 逐个读取音频 packet，并通过 `avcodec_send_packet()` / `avcodec_receive_frame()` 解码。
3. 对每个 `AVFrame` 遍历所有采样和所有声道，计算绝对值最大的峰值。
4. 对 planar 格式从 `extended_data[channel]` 读取；对 packed 格式从 `extended_data[0]` 按交错布局读取。
5. `sampleToFloat()` 把 `u8`、`s16`、`s32`、`s64`、`flt`、`dbl` 转换到约 `-1.0～1.0`。
6. 将所有解码帧峰值压缩到 `pointCount` 个桶，每个桶保留最大峰值。

返回数组表达的是“每个时间区域的峰值”，不是原始 PCM 波形，也不是 RMS 音量。它适合绘制编辑器概览波形，但不适合精确到采样点的分析。

波形读取结束时同样需要 flush 解码器：向 `avcodec_send_packet()` 传入 `nullptr`，再读取解码器内部缓存的最后几帧，否则文件尾部可能不参与峰值统计。

### 3.5 `trim(inputPath, outputPath, startMs, endMs)`

```kotlin
FfmpegBridge.trim(
    inputPath = inputPath,
    outputPath = outputPath,
    startMs = 12_500L,
    endMs = 35_000L,
)
```

参数单位是毫秒：

- `startMs`：包含的起始时间。
- `endMs`：结束边界，必须大于 `startMs`。
- 输出固定为 MP3。

正常情况下 native 构造单输入滤镜并重新编码。若满足下面全部条件，则直接二进制复制：

- 输入容器是 MP3。
- `startMs == 0`。
- 能够获取有效时长。
- `endMs` 覆盖完整时长，允许 1 ms 的误差。

直接复制只适用于完整文件不需要任何处理的情况；真正的区间裁剪仍然需要解码和重新编码。由于 MP3 是有损格式，重新编码后的文件大小不一定与原文件成比例，质量还会受输出码率影响。

### 3.6 `tempoPitch(inputPath, outputPath, startMs, endMs, speed, pitchSemitones)`

```kotlin
FfmpegBridge.tempoPitch(
    inputPath = inputPath,
    outputPath = outputPath,
    startMs = 0L,
    endMs = 10_000L,
    speed = 1.25,
    pitchSemitones = -2.0,
)
```

参数含义：

- `startMs`、`endMs`：裁剪区间，单位为毫秒。
- `speed`：速度倍率。`1.0` 为原速，`2.0` 为两倍速，必须大于 0。
- `pitchSemitones`：半音变化量。`0` 不变调，正值升调，负值降调。

当速度为 1、音调为 0，且选区覆盖完整 MP3 时，同样走直接复制快速路径；只要改变速度、改变音调或裁剪区间，就会重新编码。

### 3.7 `concat(firstInputPath, secondInputPath, outputPath)`

```kotlin
FfmpegBridge.concat(firstPath, secondPath, outputPath)
```

两个输入会被解码并按参数顺序拼接。输入可以有不同采样率、采样格式和声道布局，滤镜末端会统一成 `44100 Hz + stereo + fltp`，然后输出 MP3。拼接不能使用整文件直接复制，因为两个 MP3 文件的帧序列和编码参数可能不同，必须生成新的连续输出流。

## 4. 通用转码流程：`transcodeFile()`

`nativeConvert()` 调用 `transcodeFile()`，这是不带编辑滤镜的通用转码管线。

### 4.1 打开输入并初始化解码器

核心 FFmpeg API：

| API | 作用 |
| --- | --- |
| `avformat_open_input()` | 打开输入容器 |
| `avformat_find_stream_info()` | 读取流信息和编解码参数 |
| `av_find_best_stream()` | 查找最佳音频流 |
| `avcodec_find_decoder()` | 根据 `codec_id` 查找解码器 |
| `avcodec_alloc_context3()` | 分配解码器上下文 |
| `avcodec_parameters_to_context()` | 将容器中的 `AVCodecParameters` 复制给解码器上下文 |
| `avcodec_open2()` | 打开解码器 |

解码器输出的 `sample_fmt`、`sample_rate`、`ch_layout` 取决于输入文件，不应直接假定为某一种格式。

### 4.2 创建输出容器和编码器

`codecForFormat()` 当前映射：

| 目标格式 | 编码器 |
| --- | --- |
| `mp3` | `libmp3lame` |
| `wav` | `pcm_s16le` |
| `flac` | `flac` |
| `ogg` | `libvorbis` |
| `opus` | `libopus` |

`avformat_alloc_output_context2()` 根据输出路径或目标格式创建容器，`avformat_new_stream()` 创建输出流，`avcodec_parameters_from_context()` 把编码器参数写入输出流。

编码器的 `time_base` 设置为：

```text
{ 1, sample_rate }
```

也就是一个时间戳单位代表一个采样点。编码包写入容器前使用 `av_packet_rescale_ts()` 把时间戳转换到输出流时间基。

### 4.3 `SwrContext` 重采样

输入解码器和输出编码器的格式可能不同。`swr_alloc_set_opts2()` 配置以下转换：

- 输入声道布局、采样格式、采样率：来自 decoder。
- 输出声道布局、采样格式、采样率：来自 encoder。

`swr_convert()` 将输入音频帧转换成编码器需要的 PCM。处理完输入后必须调用 `drainResamplerIntoFifo()`，通过 `swr_convert(..., nullptr, 0)` 取出 SwrContext 内部缓存的延迟样本，否则结尾可能缺少一小段音频。

### 4.4 `AVAudioFifo` 为什么必要

解码器输出帧大小和编码器要求的帧大小通常不同。例如一个 MP3 解码帧可能有 1152 个 sample，而 MP3 编码器可能按自己的 `frame_size` 接收输入。不能假设一个 decoder frame 对应一个 encoder frame。

`AVAudioFifo` 的作用是按“每声道 sample 数”暂存转换后的 PCM：

```text
decoder AVFrame
    ↓ swr_convert
AVAudioFifo
    ↓ 按 encoder->frame_size 取样本
encoder AVFrame
    ↓ avcodec_send_frame
MP3 AVPacket
```

`encodeAvailableFifo()` 有两种模式：

- 普通处理：FIFO 样本数不足一个完整编码帧时先保留，不补静音。
- 最终排空：所有真实输入都结束后，处理剩余样本；如果编码器不支持小尾帧，就补静音到完整帧。

`nextPts` 只按实际读取的真实样本数推进，不把补的静音计算进时间戳。

### 4.5 解码器和编码器 flush 顺序

完整结束流程必须按以下顺序执行：

1. 向 decoder 发送 `nullptr`，取出解码器缓存的最后帧。
2. 排空 `SwrContext` 的延迟样本到 FIFO。
3. 排空 FIFO 中的完整帧和尾帧。
4. 向 encoder 发送 `nullptr`，取出编码器缓存的最后 packet。
5. 调用 `av_write_trailer()` 完成容器尾部写入。

如果提前 flush encoder，FIFO 中尚未编码的样本会丢失；如果不 flush decoder 或重采样器，输入尾部也可能丢失。

## 5. 编辑滤镜流程：`transcodeFiltered()`

裁剪、变速变调和拼接都使用 `transcodeFiltered()`。它与通用转码的区别是：解码后的 `AVFrame` 先进入 FFmpeg 音频滤镜图，再从 `abuffersink` 取出处理结果。

整体流程如下：

```text
输入文件
  ↓ avformat_open_input + avcodec 解码
AVFrame
  ↓ abuffer
音频滤镜图
  ↓ abuffersink
fltp / 44100 Hz / stereo
  ↓ AVAudioFifo
MP3 编码器
  ↓ AVPacket
MP3 输出容器
```

### 5.1 `FilterInput`

每个输入文件对应一个 `FilterInput`，保存：

- `AVFormatContext *format`：输入容器。
- `AVCodecContext *decoder`：输入音频解码器。
- `AVStream *stream`：目标音频流。
- `audioIndex`：音频流下标。
- `AVFilterContext *source`：滤镜图中的 `abuffer`。
- `nextPts`：没有可靠输入时间戳时的连续采样点计数。
- `sourceEnded`：当前滤镜输入是否已经收到 EOF。

`closeFilterInput()` 释放 decoder 和 format；滤镜图本身统一由 `avfilter_graph_free()` 释放。

### 5.2 创建 `abuffer` 和 `abuffersink`

`configureFilterSources()` 对每个输入创建 `abuffer`，参数包括：

- `time_base=1/sample_rate`：输入帧时间戳的时间基。
- `sample_rate`：解码器采样率。
- `sample_fmt`：解码器采样格式名称。
- `channel_layout`：输入声道布局。

输出端创建 `abuffersink`，并限制：

```text
sample_fmts = fltp
sample_rates = 44100
ch_layouts = stereo
```

这组限制必须和后面的编码器及 FIFO 一致。当前 MP3 编辑管线明确设置：

- 滤镜输出：`AV_SAMPLE_FMT_FLTP`
- 编码器：`AV_SAMPLE_FMT_FLTP`
- FIFO：`encoder->sample_fmt`，即 `FLTP`
- 采样率：44100 Hz
- 声道布局：stereo

采样格式不一致时，虽然指针类型可能仍然可以传递，但同一块内存会按错误的每采样字节数解释，容易产生非常明显的滋滋声、爆音和失真。

## 6. 裁剪与变速变调滤镜

### 6.1 单段滤镜结构

`buildSingleFilter()` 生成的滤镜大致如下：

```text
[in0]
atrim=start=开始秒:end=结束秒,
asetpts=PTS-STARTPTS,
可选的 asetrate=调整采样率,
可选的 aresample=44100,
atempo=速度补偿,
aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo
[out]
```

### 6.2 `atrim`

`startMs` 和 `endMs` 先除以 1000 转成秒：

```text
start = startMs / 1000.0
end   = endMs / 1000.0
```

`atrim=start:end` 保留指定区间。它按照音频时间戳工作，实际输出边界仍会受到采样点和 MP3 解码帧边界影响，不能把它理解为任意位置的字节级切割。

### 6.3 `asetpts=PTS-STARTPTS`

裁剪后如果继续使用原始时间戳，输出可能从非零时间开始，影响后续拼接、编码和播放器定位。`asetpts=PTS-STARTPTS` 把裁剪结果重新从 0 开始，保证每次编辑输出都是从时间 0 播放。

### 6.4 半音到采样率比例

半音转换为频率比例的公式是：

```text
pitchRatio = 2^(pitchSemitones / 12)
```

例如：

- `+12` 半音：比例约为 2，升高一个八度。
- `-12` 半音：比例约为 0.5，降低一个八度。
- `0` 半音：比例为 1，不执行音调采样率变化。

当比例不为 1 时，native 层把输入采样率乘以该比例，得到 `shiftedRate`，再使用：

```text
asetrate=shiftedRate,aresample=44100
```

`asetrate` 改变音调，同时会改变播放时长；`aresample` 将结果恢复到固定输出采样率。为抵消这一步对速度的影响，后续 `atempo` 使用：

```text
atempo = speed / pitchRatio
```

这样可以分别控制最终速度和最终音调。

### 6.5 `atempo` 范围拆分

FFmpeg 的 `atempo` 单个实例适合处理 0.5～2.0 范围。`appendAtempo()` 会把更大的变化拆成多个滤镜：

```text
目标因子 4.0
→ atempo=2.0,atempo=2.0
```

小于 0.5 时同理串联多个 `atempo=0.5`，最后再追加剩余因子。这样既支持更宽的参数范围，也避免把非法倍率直接交给滤镜图。

## 7. 拼接滤镜

`nativeConcat()` 使用的滤镜描述为：

```text
[in0]asetpts=PTS-STARTPTS[a0];
[in1]asetpts=PTS-STARTPTS[a1];
[a0][a1]concat=n=2:v=0:a=1,
aresample=44100,
aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[out]
```

各部分作用：

- 两个 `asetpts`：分别把两段输入的时间戳归零。
- `concat=n=2`：声明有两段输入。
- `v=0`：没有视频流。
- `a=1`：有一个音频输出流。
- `aresample=44100`：统一采样率。
- `aformat`：统一采样格式和声道布局。

处理循环会先完整喂入第一段，再完整喂入第二段。每个输入结束后向对应 `abuffer` 发送空帧表示 EOF，最后调用 `consumeFilteredFrames(..., untilEof=true)`，确保 concat 滤镜把缓存的最后数据全部输出。

## 8. 滤镜 EOF 问题的处理

### 8.1 为什么会出现 “写入音频滤镜失败：end of file”

带有结束边界的 `atrim` 可能在底层解码器还没有读到输入文件真正 EOF 时，就已经产生了足够的选区数据并关闭下游滤镜。因此继续执行：

```cpp
av_buffersrc_add_frame_flags(source, frame, AV_BUFFERSRC_FLAG_KEEP_REF)
```

可能返回 `AVERROR_EOF`。这不一定代表输入文件损坏，而可能只表示“当前滤镜选区已经正常结束”。

### 8.2 当前处理方式

`FilterInput::sourceEnded` 记录当前输入是否已经结束。`pushFrameToFilter()` 的规则是：

1. 如果已经结束，不再向 source 写帧。
2. 如果返回 `AVERROR_EOF`，标记 `sourceEnded=true`，当作正常结束。
3. 其他负值才抛出“写入音频滤镜失败”。
4. 调用方停止继续解码当前输入，并排空 sink 中已经生成的数据。

这样可以避免把正常的裁剪终点误判为输入错误，也避免在滤镜已经关闭后继续写入帧。

## 9. MP3 编码、码率和文件大小

### 9.1 MP3 输出格式

编辑管线使用 `libmp3lame`，编码器配置为：

- 采样率：44100 Hz。
- 采样格式：`AV_SAMPLE_FMT_FLTP`。
- 声道布局：stereo。
- 时间基：`{1, 44100}`。

输出码率从每个输入的 decoder 或 format 中读取，选择最高值，并限制在 32～320 kbps：

```text
输入有可用码率 → 选择最高输入码率
输入没有码率   → 默认 192 kbps
最终限制       → 32 kbps ～ 320 kbps
```

拼接时选择两段音频中的最高码率，避免把高码率输入无意中降到固定低码率。

### 9.2 为什么重新导出的 MP3 可能变小

以下情况都会导致输出文件明显变小：

- 实际裁剪区间比原文件短。
- 输入 MP3 的码率高于 native 读取到的码率，或读取不到码率而回退到 192 kbps。
- 原文件包含较多 ID3 标签、封面或其他元数据，编辑输出只写音频流，不复制这些附加数据。
- MP3 重新编码后帧填充和容器元数据布局不同。

当前实现针对“整段 MP3、起点为 0、没有变速变调”的情况直接复制原文件，因此这种完整无处理操作不会因为二次编码而缩小。真正的区间裁剪仍然会重新编码，不能保证字节级保留原文件大小。

### 9.3 为什么不能用文件大小判断音质

MP3 文件大小主要与时长、码率和编码器设置有关。大小变小不必然表示损坏，大小接近也不代表没有二次编码。应结合 `probe()` 的码率、时长和实际听感判断；如果听到滋滋声，优先检查采样格式、声道布局和编码器配置是否一致。

## 10. 滤镜输出与滋滋声问题

滤镜 sink 固定输出 `fltp`，因此后续所有环节必须一致：

```text
abuffersink: fltp
        ↓
AVAudioFifo: fltp
        ↓
MP3 encoder: fltp
```

如果滤镜产生的是 planar float 数据，却把编码器配置成 `s32p`，编码器会用错误的样本格式解释同一块内存：

- 每个样本的字节数可能不同。
- planar/packed 的数据排列可能不同。
- 声道指针含义可能不同。

结果通常是明显的滋滋声、爆音或强烈失真。当前实现通过 `aformat`、sink 配置和 `encoder->sample_fmt = AV_SAMPLE_FMT_FLTP` 三处固定格式，避免这种不一致。

## 11. 资源释放规则

FFmpeg 使用大量手动管理的 C 资源。当前代码在成功和异常路径都释放：

- `AVFormatContext`：`avformat_close_input()` 或 `avformat_free_context()`。
- `AVCodecContext`：`avcodec_free_context()`。
- `AVFilterGraph`：`avfilter_graph_free()`。
- `AVAudioFifo`：`av_audio_fifo_free()`。
- `AVPacket`：`av_packet_free()`。
- `AVFrame`：`av_frame_free()`。
- `SwrContext`：`swr_free()`。
- 输出文件 IO：`avio_closep()`。

`transcodeFile()` 和 `transcodeFiltered()` 都使用 `try/catch (...)` 做集中清理，然后重新抛出异常；JNI 最外层捕获 `std::exception` 并转换成 Java 异常。新增 FFmpeg 资源时，应同时补充成功和异常两条释放路径。

## 12. CMake 与静态库

`CMakeLists.txt` 创建名为 `ffmpeg_jni` 的共享库，并直接链接项目内的 FFmpeg 静态库。主要组件包括：

- `libavformat.a`：容器读写。
- `libavcodec.a`：解码和编码。
- `libavfilter.a`：音频滤镜。
- `libswresample.a`：采样率、采样格式和声道转换。
- `libavutil.a`：基础数据结构和工具函数。
- `libmp3lame.a`：MP3 编码。
- `libvorbis.a`、`libopus.a`、`libogg.a`：其他目标格式和容器支持。

使用 `--start-group/--end-group` 链接静态库，是因为 FFmpeg 各组件之间存在循环依赖。`-Wl,-z,max-page-size=16384` 用于 Android 目标的页大小兼容配置。

## 13. 调用示例与注意事项

### 13.1 读取元数据和波形

```kotlin
val path = cachedFile.absolutePath
val probe = FfmpegBridge.probe(path)
val waveform = FfmpegBridge.waveform(path, 160)
```

### 13.2 裁剪并导出

```kotlin
FfmpegBridge.trim(
    inputPath = cachedFile.absolutePath,
    outputPath = outputFile.absolutePath,
    startMs = 5_000L,
    endMs = 20_000L,
)
```

### 13.3 变速变调

```kotlin
FfmpegBridge.tempoPitch(
    inputPath = cachedFile.absolutePath,
    outputPath = previewFile.absolutePath,
    startMs = 0L,
    endMs = probe.durationMs,
    speed = 0.8,
    pitchSemitones = 3.0,
)
```

### 13.4 拼接

```kotlin
FfmpegBridge.concat(
    firstInputPath = firstFile.absolutePath,
    secondInputPath = secondFile.absolutePath,
    outputPath = joinedFile.absolutePath,
)
```

注意：这些方法是同步调用，应该放到后台线程；不要在 Android 主线程直接执行长音频的 probe、波形提取或转码。

## 14. 常见问题排查

### 找不到编码器或滤镜

先检查 `configuration()`，确认构建中包含对应组件，并确认目标 ABI 下的静态库都存在。MP3 编辑至少需要 `libavcodec`、`libavformat`、`libavfilter`、`libswresample`、`libavutil` 和 `libmp3lame`。

### 写入滤镜失败：end of file

先确认当前代码使用了 `sourceEnded` 处理 `AVERROR_EOF`，并且 source 结束后没有继续写入帧。不要简单地把所有 EOF 都改成忽略；`av_buffersink_get_frame()`、decoder 和 encoder 的 EOF 仍然需要按各自的 flush 语义处理。

### 输出有滋滋声

重点检查：

1. sink 的 sample format 是否是 `fltp`。
2. encoder 的 `sample_fmt` 是否同样是 `AV_SAMPLE_FMT_FLTP`。
3. FIFO 是否按 encoder 的格式创建。
4. planar 数据是否被当成 packed 数据读取或写入。
5. 编码器声道布局、采样率与滤镜输出是否一致。

### 完整 MP3 导出大小变化

确认是否命中了完整 MP3 直接复制条件。如果只是部分区间、速度为非 1、音调不为 0，输出属于重新编码；此时应比较时长和码率，而不是要求文件大小完全相同。

### 输出末尾缺少声音

检查是否按顺序执行 decoder flush、重采样器 drain、FIFO drain、encoder flush。尤其不要在 `swr_convert(..., nullptr, 0)` 之前结束编码器。

## 15. 扩展 native 功能时的建议

新增音频滤镜时，建议遵循现有约定：

1. 在 Kotlin API 中写清楚参数单位、取值范围和输出格式。
2. 在 JNI 入口重复校验参数。
3. 让滤镜末端输出固定格式，或同步修改 sink、FIFO、encoder 三处配置。
4. 处理滤镜 source 提前 EOF 和 sink 最终排空。
5. 对 decoder、SwrContext、FIFO、encoder 依次 flush。
6. 所有成功和异常路径都释放 FFmpeg 对象。
7. 用不同采样率、单声道/立体声、不同输入格式和短音频测试。
8. 对 MP3 重新编码结果同时检查可播放性、时长、码率和听感。
