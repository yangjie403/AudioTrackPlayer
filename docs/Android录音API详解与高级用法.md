# Android 录音 API 详解与高级用法

本文以当前 AudioTrackPlayer 项目为背景，系统介绍 Android 原生录音 API、基本使用流程和高级用法。当前项目使用 Kotlin、minSdk 23、targetSdk 36，并已经包含：

~~~text
com.bigjelly.temporun.recoder.PcmRecoder
com.bigjelly.temporun.recoder.WavFileWriter
com.bigjelly.temporun.recoder.WavPcmPlayer
com.bigjelly.temporun.recoder.WaveformView
com.bigjelly.temporun.RecorderActivity
~~~

本文覆盖：

- MediaRecorder：快速录制 AAC/AMR 等编码文件；
- AudioRecord：采集原始 PCM，适合 WAV、波形和实时音频处理；
- AudioFormat、AudioDeviceInfo、AudioManager；
- 权限、线程、生命周期、缓冲区和错误处理；
- 实时音量、RMS、峰值、VAD 和波形；
- 系统降噪、回声消除、自动增益；
- 暂停继续、设备切换、前台服务和性能优化。

---

## 一、先选择录音 API

Android 录音主要有两条路线：

~~~text
MediaRecorder：麦克风 → 系统编码器 → AAC/AMR → m4a/3gp 文件

AudioRecord：麦克风 → PCM
                       ├─→ WAV
                       ├─→ 实时波形
                       ├─→ 降噪/人声增强
                       ├─→ 网络传输
                       └─→ 自定义编码
~~~

| 需求 | 推荐 API | 输出 | 控制能力 |
|---|---|---|---|
| 快速得到可播放文件 | MediaRecorder | AAC/AMR 等编码文件 | 低 |
| 获取每一块 PCM | AudioRecord | 原始 PCM | 高 |
| 实时绘制波形 | AudioRecord | PCM + 振幅 | 高 |
| 自己写 WAV | AudioRecord + Writer | PCM → WAV | 高 |
| 实时降噪或回声消除 | AudioRecord + 音频处理 | 处理后的 PCM | 高 |
| 后台长时间录音 | 前台 Service + 录音 API | 取决于录音 API | 高 |

### 1. 选择 MediaRecorder

只需要“开始录音、停止录音、得到一个较小的音频文件”时，MediaRecorder 最简单。系统负责编码和封装，应用拿不到连续的 PCM 数据。

### 2. 选择 AudioRecord

如果需要下面任何能力，就使用 AudioRecord：

- 实时波形、音量或 VU 表；
- PCM/WAV 文件；
- 自定义降噪、EQ、压缩器或人声增强；
- 实时上传音频帧；
- 接入 WebRTC、RNNoise、TFLite 或 JNI 音频处理；
- 精确控制采样率、声道和位深。

当前项目的“PCM → WAV → AudioTrack 播放”属于这条路线。

---

## 二、权限、目录与录音生命周期

### 1. Manifest 权限

在 AndroidManifest.xml 的 manifest 节点下声明：

~~~xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
~~~

Android 6.0（API 23）及以上还必须申请运行时权限：

~~~kotlin
private companion object {
    const val REQUEST_RECORD_AUDIO = 1001
}

private fun hasRecordPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private fun startOrRequestRecordPermission() {
    if (!hasRecordPermission()) {
        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO
        )
        return
    }
    startRecording()
}

override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != REQUEST_RECORD_AUDIO) return

    if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
        startRecording()
    } else {
        showMessage("没有录音权限，无法访问麦克风")
    }
}
~~~

权限被永久拒绝时，可以引导用户打开应用设置：

~~~kotlin
fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:" + context.packageName)
    )
    context.startActivity(intent)
}
~~~

### 2. 文件保存目录

应用私有目录不需要传统公共存储权限：

~~~kotlin
val recordingDirectory = File(filesDir, "recordings")
check(recordingDirectory.exists() || recordingDirectory.mkdirs())

val wavFile = File(
    recordingDirectory,
    "record_" + System.currentTimeMillis() + ".wav"
)
~~~

目录选择建议：

| 目录 | 用途 | 特点 |
|---|---|---|
| filesDir | 重要的应用私有录音 | 应用卸载时删除，不需要存储权限 |
| cacheDir | 临时录音或预览 | 可能被系统清理 |
| getExternalFilesDir() | 应用专属外部存储 | 空间可能更大，仍不直接公开 |
| MediaStore | 用户可见的公共音频 | 通过 Uri 写入 |
| SAF | 用户选择导出位置 | 得到 Uri，不能假设它是真实路径 |

### 3. 推荐的状态机

不要只用一个 Boolean 表示录音状态：

~~~kotlin
enum class RecordingState {
    IDLE,
    STARTING,
    RECORDING,
    PAUSED,
    STOPPING,
    COMPLETED,
    ERROR
}
~~~

基本状态流转：

~~~text
IDLE → STARTING → RECORDING → STOPPING → COMPLETED → IDLE
                    ↓
                  PAUSED

任何阶段发生错误 → ERROR → 释放资源 → IDLE
~~~

stop() 被调用只代表发出了停止请求；只有录音线程退出、底层资源释放、WAV 头回填完成后，文件才可以交给播放模块。

---

## 三、MediaRecorder：快速生成编码文件

### 1. 状态和调用顺序

~~~text
new MediaRecorder()
  ↓ setAudioSource()
  ↓ setOutputFormat()
  ↓ setAudioEncoder()
  ↓ setAudioSamplingRate()/setAudioEncodingBitRate()
  ↓ setOutputFile()
  ↓ prepare()
  ↓ start()
  ↓ stop()
  ↓ reset()/release()
~~~

调用顺序不能随意调换。尤其是 stop() 只能在 start() 成功后调用；录音时间太短时，编码器可能还没有有效帧，stop() 可能抛 RuntimeException。

### 2. AAC/M4A 完整示例

~~~kotlin
class AacMediaRecorder {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    @Synchronized
    fun start(file: File): Boolean {
        if (recorder != null) return false
        file.parentFile?.mkdirs()

        val candidate = MediaRecorder()
        return try {
            candidate.setAudioSource(MediaRecorder.AudioSource.MIC)
            candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            candidate.setAudioSamplingRate(44_100)
            candidate.setAudioEncodingBitRate(128_000)
            candidate.setOutputFile(file.absolutePath)
            candidate.prepare()
            candidate.start()

            recorder = candidate
            outputFile = file
            true
        } catch (error: Throwable) {
            try {
                candidate.reset()
            } catch (_: Throwable) {
            }
            candidate.release()
            false
        }
    }

    @Synchronized
    fun stop(): File? {
        val current = recorder ?: return null
        val file = outputFile

        try {
            current.stop()
        } catch (_: RuntimeException) {
            // 编码数据不足或设备编码器失败，删除不完整文件。
            file?.delete()
            return null
        } finally {
            try {
                current.reset()
            } catch (_: Throwable) {
            }
            current.release()
            recorder = null
            outputFile = null
        }

        return file?.takeIf { it.isFile && it.length() > 0L }
    }

    @Synchronized
    fun release() {
        recorder?.let { current ->
            try {
                current.reset()
            } catch (_: Throwable) {
            }
            current.release()
        }
        recorder = null
        outputFile = null
    }
}
~~~

调用：

~~~kotlin
val file = File(filesDir, "recordings/voice.m4a")
val recorder = AacMediaRecorder()

if (recorder.start(file)) {
    // 用户点击停止按钮时
    val savedFile = recorder.stop()
    Log.d("Recorder", "saved=" + savedFile)
}
~~~

### 3. 常用编码参数

~~~kotlin
MediaRecorder.OutputFormat.MPEG_4
MediaRecorder.OutputFormat.THREE_GPP

MediaRecorder.AudioEncoder.AAC
MediaRecorder.AudioEncoder.AMR_NB
MediaRecorder.AudioEncoder.AMR_WB
~~~

普通录音一般使用 MPEG_4 + AAC，文件扩展名为 .m4a。实际可用的采样率、编码器和码率取决于设备，不能仅依赖文档中的组合。

### 4. MediaRecorder 的边界

MediaRecorder 不适合：

- 实时绘制 PCM 波形；
- 自己计算每个音频块的 RMS；
- 自定义 WAV 头；
- 将裸 PCM 上传到服务器；
- 在每个采样点执行降噪或人声处理。

这些场景应改用 AudioRecord。

---

## 四、AudioRecord：采集原始 PCM

### 1. 创建流程

~~~kotlin
val sampleRate = 44_100
val channelMask = AudioFormat.CHANNEL_IN_MONO
val encoding = AudioFormat.ENCODING_PCM_16BIT

val minBufferSize = AudioRecord.getMinBufferSize(
    sampleRate,
    channelMask,
    encoding
)
require(minBufferSize > 0) {
    "Unsupported format: minBufferSize=" + minBufferSize
}

val requestedReadBytes = sampleRate / 50 * 2 // 约 20ms，单声道 16-bit
val bufferSize = maxOf(minBufferSize, requestedReadBytes * 2)

val audioRecord = AudioRecord.Builder()
    .setAudioSource(MediaRecorder.AudioSource.MIC)
    .setAudioFormat(
        AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .setEncoding(encoding)
            .build()
    )
    .setBufferSizeInBytes(bufferSize)
    .build()

check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
    "AudioRecord initialization failed"
}
~~~

### 2. 重要 API

| API | 作用 |
|---|---|
| getMinBufferSize() | 查询系统建议的最小缓冲区，单位是字节 |
| startRecording() | 开始采集 |
| read() | 读取 PCM 到 ShortArray、ByteArray 或 Buffer |
| stop() | 停止采集，通常用于唤醒阻塞的 read() |
| release() | 释放麦克风和底层资源 |
| state | STATE_INITIALIZED 表示初始化成功 |
| recordingState | 当前是否在录音 |
| audioSessionId | 连接系统音频效果 |
| setPreferredDevice() | API 23+ 设置首选输入设备 |
| activeMicrophones | API 28+ 获取实际麦克风信息 |
| getTimestamp() | API 24+ 获取帧位置和时间戳 |

### 3. 参数选择

常见采样率：

~~~text
8,000 Hz   电话语音
16,000 Hz  语音识别
44,100 Hz  普通音乐/录音
48,000 Hz  视频、WebRTC、现代音频链路
~~~

普通人声录音建议先用单声道。双声道会增加数据量，也不能保证手机的两个物理麦克风按预期映射。

常用编码：

~~~kotlin
AudioFormat.ENCODING_PCM_8BIT
AudioFormat.ENCODING_PCM_16BIT
AudioFormat.ENCODING_PCM_FLOAT
~~~

兼容性和处理便利性优先时使用 PCM_16BIT；一个 16-bit 样本占 2 字节，适合 ShortArray 和 WAV。

### 4. 尝试多个采样率

设备不一定支持所有采样率，可以按优先级尝试：

~~~kotlin
fun chooseSampleRate(channelMask: Int): Int {
    val candidates = intArrayOf(48_000, 44_100, 16_000, 8_000)
    for (sampleRate in candidates) {
        val size = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (size > 0) return sampleRate
    }
    error("No supported sample rate")
}
~~~

getMinBufferSize() 返回正数也不等于创建一定成功；仍然需要检查 state，并在真实设备上验证。

---

## 五、AudioRecord.read：读取 PCM

### 1. 使用 ShortArray

对于单声道 PCM 16-bit：

~~~kotlin
val framesPerRead = sampleRate / 50 // 20ms
val samplesPerRead = framesPerRead // 单声道，所以 sample = frame
val buffer = ShortArray(samplesPerRead)

audioRecord.startRecording()

try {
    while (isRecording) {
        val count = audioRecord.read(
            buffer,
            0,
            buffer.size,
            AudioRecord.READ_BLOCKING
        )

        when {
            count > 0 -> processPcm(buffer, count)
            count == AudioRecord.ERROR_DEAD_OBJECT -> break
            count < 0 -> throw IOException(
                "AudioRecord.read failed: " + count
            )
        }
    }
} finally {
    try {
        audioRecord.stop()
    } catch (_: Throwable) {
    }
    audioRecord.release()
}
~~~

### 2. 单位必须分清

~~~text
getMinBufferSize()          → 字节
read(ByteArray, size)       → 字节
read(ShortArray, size)      → Short 样本数
read(FloatArray, size)      → Float 样本数
~~~

例如 ShortArray(960) 对 PCM 16-bit 来说是 960 个样本、1920 字节，而不是 960 字节。

### 3. 阻塞和非阻塞

阻塞模式适合专用录音线程：

~~~kotlin
AudioRecord.READ_BLOCKING
~~~

没有足够数据时等待。非阻塞模式立即返回：

~~~kotlin
AudioRecord.READ_NON_BLOCKING
~~~

非阻塞模式可能返回 0，必须避免忙循环：

~~~kotlin
// 错误：没有数据时会占满 CPU
while (true) {
    audioRecord.read(
        buffer,
        0,
        buffer.size,
        AudioRecord.READ_NON_BLOCKING
    )
}
~~~

### 4. 返回值

~~~text
大于 0                       实际读取的样本数或字节数
0                            非阻塞模式下暂时无数据
ERROR_BAD_VALUE              参数非法
ERROR_INVALID_OPERATION      当前状态不允许读取
ERROR_DEAD_OBJECT            底层对象失效，需要释放并重建
~~~

负数不能写进 WAV，也不能当作有效 PCM 继续处理。

---

## 六、完整的 PCM → WAV 录音器

下面的示例展示完整链路：AudioRecord 读取 PCM，调用当前项目的 WavFileWriter 保存，并回调峰值。录音线程不直接操作 UI。

~~~kotlin
class PcmWavRecorder(
    private val onAmplitude: (Float) -> Unit = {},
    private val onFinished: (File) -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) {
    private val lock = Any()

    @Volatile
    private var recording = false

    @Volatile
    private var record: AudioRecord? = null

    private var thread: Thread? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(outputFile: File): Boolean {
        synchronized(lock) {
            if (recording) return false

            val sampleRate = 44_100
            val channelMask = AudioFormat.CHANNEL_IN_MONO
            val channelCount = 1
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                channelMask,
                encoding
            )

            if (minBuffer <= 0) {
                onError(IllegalStateException("Invalid AudioRecord buffer size"))
                return false
            }

            var candidateRecord: AudioRecord? = null
            var writer: WavFileWriter? = null

            try {
                val samplesPerRead = sampleRate / 50
                val bufferSize = maxOf(minBuffer, samplesPerRead * 2 * 2)

                outputFile.parentFile?.mkdirs()
                writer = WavFileWriter(
                    file = outputFile,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    bitsPerSample = 16
                )

                candidateRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .setEncoding(encoding)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                val readyRecord = requireNotNull(candidateRecord)
                val readyWriter = requireNotNull(writer)
                check(readyRecord.state == AudioRecord.STATE_INITIALIZED)

                readyRecord.startRecording()
                check(
                    readyRecord.recordingState ==
                        AudioRecord.RECORDSTATE_RECORDING
                )

                record = readyRecord
                recording = true

                thread = Thread({
                    val pcm = ShortArray(samplesPerRead)
                    var failure: Throwable? = null

                    try {
                        while (recording) {
                            val count = readyRecord.read(
                                pcm,
                                0,
                                pcm.size,
                                AudioRecord.READ_BLOCKING
                            )

                            when {
                                count > 0 -> {
                                    readyWriter.writePcm16(pcm, 0, count)
                                    onAmplitude(calculatePeak(pcm, count))
                                }

                                count == AudioRecord.ERROR_DEAD_OBJECT -> {
                                    throw IOException("AudioRecord is dead")
                                }

                                count < 0 -> {
                                    throw IOException(
                                        "AudioRecord.read failed: " + count
                                    )
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        if (recording) failure = error
                    } finally {
                        try {
                            if (readyRecord.recordingState ==
                                AudioRecord.RECORDSTATE_RECORDING
                            ) {
                                readyRecord.stop()
                            }
                        } catch (_: Throwable) {
                        }

                        readyRecord.release()
                        record = null
                        recording = false

                        try {
                            readyWriter.closeAndFinalize()
                        } catch (error: Throwable) {
                            if (failure == null) failure = error
                        }

                        if (failure == null) {
                            onFinished(outputFile)
                        } else {
                            onError(requireNotNull(failure))
                        }
                    }
                }, "pcm-wav-recorder")

                thread?.start()
                return true
            } catch (error: Throwable) {
                recording = false

                try {
                    candidateRecord?.release()
                } catch (_: Throwable) {
                }

                try {
                    writer?.closeAndFinalize()
                } catch (_: Throwable) {
                }

                onError(error)
                return false
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!recording) return
            recording = false

            // stop() 用于唤醒可能阻塞在 read() 的线程。
            try {
                record?.stop()
            } catch (_: Throwable) {
            }
        }
    }

    private fun calculatePeak(buffer: ShortArray, count: Int): Float {
        var maxAbs = 0
        for (index in 0 until count) {
            maxAbs = maxOf(maxAbs, abs(buffer[index].toInt()))
        }
        return (maxAbs / 32768f).coerceIn(0f, 1f)
    }
}
~~~

说明：

- 项目中已有更完整的 PcmRecoder，实际实现时优先复用它；
- WavFileWriter 关闭时才会回填 WAV 头；
- stop() 只请求停止，onFinished 才表示文件已经完成；
- onAmplitude 来自录音线程，必须切回主线程更新 View。

调用示例：

~~~kotlin
private val recorder = PcmWavRecorder(
    onAmplitude = { amplitude ->
        mainHandler.post {
            waveformView.addAmplitude(amplitude)
        }
    },
    onFinished = { file ->
        mainHandler.post {
            statusText.text = "保存完成：" + file.name
        }
    },
    onError = { error ->
        mainHandler.post {
            statusText.text = "录音失败：" + error.message
        }
    }
)

fun startButtonClicked() {
    recorder.start(
        File(filesDir, "recordings/record.wav")
    )
}

fun stopButtonClicked() {
    recorder.stop()
}
~~~

---

## 七、实时音量、波形和 VU 表

### 1. 峰值

峰值适合绘制波形包络：

~~~kotlin
fun peak(samples: ShortArray, count: Int): Float {
    var max = 0
    for (i in 0 until count) {
        max = maxOf(max, abs(samples[i].toInt()))
    }
    return (max / 32768f).coerceIn(0f, 1f)
}
~~~

### 2. RMS

RMS 更接近一小段音频的平均响度：

~~~kotlin
fun rms(samples: ShortArray, count: Int): Float {
    if (count <= 0) return 0f

    var sum = 0.0
    for (i in 0 until count) {
        val value = samples[i] / 32768.0
        sum += value * value
    }

    return sqrt(sum / count).toFloat().coerceIn(0f, 1f)
}
~~~

### 3. dBFS

~~~kotlin
fun toDbfs(amplitude: Float): Float {
    if (amplitude <= 0f) return -120f
    return (20f * log10(amplitude)).coerceIn(-120f, 0f)
}
~~~

~~~text
1.0  → 0 dBFS
0.5  → 约 -6 dBFS
0.1  → 约 -20 dBFS
~~~

### 4. 平滑显示

直接显示每个 PCM 块的峰值会抖动，可以使用指数平滑：

~~~kotlin
class AmplitudeSmoother(
    private val attack: Float = 0.7f,
    private val release: Float = 0.15f
) {
    private var current = 0f

    fun update(value: Float): Float {
        val coefficient = if (value > current) attack else release
        current += (value - current) * coefficient
        return current
    }
}
~~~

### 5. 简单 VAD

~~~kotlin
fun isSpeechLike(rms: Float, threshold: Float = 0.02f): Boolean {
    return rms >= threshold
}
~~~

能量阈值会受到环境噪声影响。正式 VAD 应使用 WebRTC VAD 或神经网络，并增加前置缓冲、尾部保持时间和最短语音时长。

### 6. UI 线程和刷新频率

录音回调通常来自后台线程，不能直接更新 View：

~~~kotlin
private val mainHandler = Handler(Looper.getMainLooper())

onAmplitude = { value ->
    mainHandler.post {
        waveformView.addAmplitude(value)
    }
}
~~~

如果每 10 ms 都 post 一次，长时间录音可能让主线程消息堆积。建议限制波形刷新在 30～60 FPS，只保存最新振幅：

~~~kotlin
private val latestAmplitude = AtomicReference(0f)

fun onAudioAmplitude(value: Float) {
    latestAmplitude.set(value)
}

// 主线程每 33ms 调用一次
fun drawTick() {
    waveformView.addAmplitude(latestAmplitude.get())
}
~~~

---

## 八、AudioFormat 和音频数据计算

### 1. 创建 AudioFormat

~~~kotlin
val format = AudioFormat.Builder()
    .setSampleRate(44_100)
    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
    .build()
~~~

对于 PCM：

~~~text
bytesPerSample = bitsPerSample / 8
blockAlign     = channelCount × bytesPerSample
byteRate       = sampleRate × blockAlign
~~~

44100 Hz、双声道、16-bit：

~~~text
bytesPerSample = 2
blockAlign     = 2 × 2 = 4 字节
byteRate       = 44100 × 4 = 176400 字节/秒
~~~

### 2. 输入和输出声道不能混用

录音：

~~~kotlin
AudioFormat.CHANNEL_IN_MONO
AudioFormat.CHANNEL_IN_STEREO
~~~

播放：

~~~kotlin
AudioFormat.CHANNEL_OUT_MONO
AudioFormat.CHANNEL_OUT_STEREO
~~~

创建 AudioRecord 时传 CHANNEL_OUT_MONO 是常见错误。

### 3. PCM 16-bit 转 little-endian 字节

~~~kotlin
fun shortsToPcmBytes(samples: ShortArray, count: Int): ByteArray {
    val buffer = ByteBuffer
        .allocate(count * 2)
        .order(ByteOrder.LITTLE_ENDIAN)

    for (i in 0 until count) {
        buffer.putShort(samples[i])
    }
    return buffer.array()
}
~~~

WAV 使用 little-endian。不要用 ByteOrder.nativeOrder() 猜文件格式的字节序。

---

## 九、音频源和系统预处理

### 1. 常用音频源

~~~kotlin
MediaRecorder.AudioSource.MIC
MediaRecorder.AudioSource.VOICE_RECOGNITION
MediaRecorder.AudioSource.VOICE_COMMUNICATION
MediaRecorder.AudioSource.UNPROCESSED
~~~

| 音频源 | 场景 | 注意事项 |
|---|---|---|
| MIC | 普通录音 | 最通用 |
| VOICE_RECOGNITION | 语音识别 | 设备可能选择识别优化链路 |
| VOICE_COMMUNICATION | 双向通话 | 可能启用通信预处理 |
| UNPROCESSED | 尽量原始的输入 | API 24+，设备不一定支持 |

UNPROCESSED 不保证每台设备都是真正原始输入；VOICE_COMMUNICATION 也不保证一定有相同的 AEC/NS。需要通过实际录音结果验证。

### 2. 系统音频效果

可以根据 AudioRecord.audioSessionId 创建 NoiseSuppressor、AcousticEchoCanceler 和 AutomaticGainControl：

~~~kotlin
class SystemAudioEffects {
    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null

    fun attach(record: AudioRecord) {
        val sessionId = record.audioSessionId

        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(sessionId)?.apply {
                enabled = true
            }
        }

        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sessionId)?.apply {
                enabled = true
            }
        }

        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(sessionId)?.apply {
                enabled = true
            }
        }
    }

    fun release() {
        ns?.release()
        aec?.release()
        agc?.release()
        ns = null
        aec = null
        agc = null
    }
}
~~~

使用时：

~~~kotlin
val effects = SystemAudioEffects()
val record = createAudioRecord()

try {
    effects.attach(record)
    record.startRecording()
    // read PCM
} finally {
    effects.release()

    try {
        record.stop()
    } catch (_: Throwable) {
    }

    record.release()
}
~~~

系统效果有明显的设备差异：

- isAvailable() 不代表各厂商效果一致；
- AGC 会改变录音电平；
- 多个降噪器叠加可能出现水声和人声损失；
- AEC 只有在有播放参考信号、正确配置通信链路时才有效。

建议把效果做成开关，保存原始和处理后录音进行 A/B 测试。

---

## 十、暂停、继续和音频中断

### 1. 暂停和继续

简单方式：

~~~kotlin
fun pause(record: AudioRecord) {
    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        record.stop()
    }
}

fun resume(record: AudioRecord) {
    record.startRecording()
    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
}
~~~

实际项目中需要把这两个操作放进状态机，并使用同一把锁保护。暂停期间通常不写 PCM，因此 WAV 中没有静音；如果要保留时间轴，应主动写入零采样。

### 2. 电话或其他应用占用麦克风

可能出现：

- 创建失败；
- read() 返回 ERROR_DEAD_OBJECT；
- PCM 全为 0；
- 系统把客户端静音；
- 输入设备发生切换。

处理流程：

~~~text
检测错误
  ↓
停止录音循环
  ↓
释放 AudioRecord 和效果器
  ↓
关闭或保存当前文件
  ↓
提示用户
  ↓
用户主动重试时重新创建录音器
~~~

不要在一个无限循环中无延迟重试创建 AudioRecord。

---

## 十一、音频输入设备和路由

### 1. 查询输入设备

API 23+：

~~~kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    val manager = getSystemService(AudioManager::class.java)
    val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)

    inputs.forEach { device ->
        Log.d(
            "AudioDevice",
            "id=" + device.id +
                ", type=" + device.type +
                ", name=" + device.productName +
                ", address=" + device.address
        )
    }
}
~~~

常见设备类型：

~~~kotlin
AudioDeviceInfo.TYPE_BUILTIN_MIC
AudioDeviceInfo.TYPE_WIRED_HEADSET
AudioDeviceInfo.TYPE_USB_DEVICE
AudioDeviceInfo.TYPE_BLUETOOTH_SCO
AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
~~~

蓝牙 A2DP 主要是播放链路，不能简单当作低延迟录音输入；蓝牙通话常用 SCO/HFP，质量和采样率可能降低。

### 2. 设置首选输入设备

~~~kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    val accepted = audioRecord.setPreferredDevice(selectedDevice)
    Log.d("AudioDevice", "accepted=" + accepted)
}
~~~

返回 true 只代表请求被接受，不代表硬件一定按期望工作，应结合实际设备和 activeMicrophones 验证。

### 3. 监听设备变化

~~~kotlin
private val deviceCallback = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(
        devices: Array<out AudioDeviceInfo>
    ) {
        refreshInputDevices()
    }

    override fun onAudioDevicesRemoved(
        devices: Array<out AudioDeviceInfo>
    ) {
        refreshInputDevices()
    }
}

fun registerDeviceCallback() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        getSystemService(AudioManager::class.java)
            .registerAudioDeviceCallback(
                deviceCallback,
                Handler(Looper.getMainLooper())
            )
    }
}

fun unregisterDeviceCallback() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        getSystemService(AudioManager::class.java)
            .unregisterAudioDeviceCallback(deviceCallback)
    }
}
~~~

API 28+ 查看麦克风：

~~~kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    audioRecord.activeMicrophones.forEach { microphone ->
        Log.d(
            "Microphone",
            "id=" + microphone.id +
                ", type=" + microphone.type +
                ", location=" + microphone.location +
                ", direction=" + microphone.directionality
        )
    }
}
~~~

---

## 十二、录音配置回调与时间戳

### 1. 监听系统录音配置

API 24+：

~~~kotlin
private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
    override fun onRecordingConfigChanged(
        configurations: List<AudioRecordingConfiguration>
    ) {
        configurations.forEach { configuration ->
            Log.d(
                "RecordingConfig",
                "source=" + configuration.clientAudioSource +
                    ", silenced=" + configuration.isClientSilenced +
                    ", device=" + configuration.audioDevice
            )
        }
    }
}

fun registerRecordingCallback() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        getSystemService(AudioManager::class.java)
            .registerAudioRecordingCallback(
                recordingCallback,
                Handler(Looper.getMainLooper())
            )
    }
}

fun unregisterRecordingCallback() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        getSystemService(AudioManager::class.java)
            .unregisterAudioRecordingCallback(recordingCallback)
    }
}
~~~

isClientSilenced 可以帮助诊断“录音线程正常但 PCM 全是 0”。

### 2. 获取录音时间戳

API 24+：

~~~kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
    val timestamp = AudioTimestamp()

    if (audioRecord.getTimestamp(
            timestamp,
            AudioTimestamp.TIMEBASE_MONOTONIC
        ) == AudioRecord.SUCCESS
    ) {
        val elapsedMs =
            timestamp.framePosition * 1000L / sampleRate

        Log.d(
            "AudioTimestamp",
            "frames=" + timestamp.framePosition +
                ", elapsedMs=" + elapsedMs +
                ", nanoTime=" + timestamp.nanoTime
        )
    }
}
~~~

时间戳适合做录音进度、音画同步、网络音频时间线和丢帧诊断。

---

## 十三、后台录音和前台服务

Activity 销毁时停止录音适合简单页面。如果要锁屏或切到其他应用后继续，需要前台 Service。

### 1. Manifest

针对较新的目标 SDK，通常需要：

~~~xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application>
    <service
        android:name=".recording.RecordingService"
        android:exported="false"
        android:foregroundServiceType="microphone" />
</application>
~~~

实际发布时应以目标 SDK 对前台服务类型和启动限制的要求为准。

### 2. Service 核心示例

~~~kotlin
class RecordingService : Service() {
    companion object {
        const val ACTION_START = "recording.START"
        const val ACTION_STOP = "recording.STOP"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "recording"
    }

    private val recorder = PcmRecoder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                val notification = buildNotification("正在录音")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                startRecordingIfNeeded()
            }

            ACTION_STOP -> {
                recorder.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startRecordingIfNeeded() {
        val file = File(
            filesDir,
            "recordings/service_" +
                System.currentTimeMillis() +
                ".wav"
        )
        recorder.start(file)
    }

    override fun onDestroy() {
        recorder.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
~~~

创建通知：

~~~kotlin
private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "录音",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}

private fun buildNotification(text: String): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("录音机")
        .setContentText(text)
        .setOngoing(true)
        .build()
}
~~~

前台服务需要较快调用 startForeground()，并且通常应在用户可见的 Activity 中先获得麦克风权限，再启动服务。NotificationCompat 需要 AndroidX Core 依赖，也可以改成原生 Notification.Builder。

从 Activity 启动：

~~~kotlin
val intent = Intent(this, RecordingService::class.java).apply {
    action = RecordingService.ACTION_START
}
ContextCompat.startForegroundService(this, intent)
~~~

---

## 十四、资源释放和生命周期

### 1. 安全释放顺序

~~~text
设置停止标志
  ↓
调用 AudioRecord.stop()，唤醒 read()
  ↓
等待录音线程退出
  ↓
释放 NoiseSuppressor/AEC/AGC
  ↓
释放 AudioRecord
  ↓
回填并关闭 WAV
  ↓
通知 UI 文件完成
~~~

幂等释放示例：

~~~kotlin
fun releaseAudioRecord(record: AudioRecord?) {
    if (record == null) return

    try {
        if (record.recordingState ==
            AudioRecord.RECORDSTATE_RECORDING
        ) {
            record.stop()
        }
    } catch (_: Throwable) {
    }

    try {
        record.release()
    } catch (_: Throwable) {
    }
}
~~~

### 2. 页面内录音

~~~kotlin
override fun onDestroy() {
    recorder.stop()
    player.stop()
    super.onDestroy()
}
~~~

如果旋转屏幕后仍要继续录音，应把录音器放到 ViewModel 或前台 Service；Activity 不应持有长期线程，也不应被录音线程通过回调永久持有。

### 3. 暂停继续

AudioRecord.stop() 后，可以再次 startRecording()：

~~~kotlin
fun pause() {
    synchronized(lock) {
        audioRecord?.stop()
        state = RecordingState.PAUSED
    }
}

fun resume() {
    synchronized(lock) {
        audioRecord?.startRecording()
        check(
            audioRecord?.recordingState ==
                AudioRecord.RECORDSTATE_RECORDING
        )
        state = RecordingState.RECORDING
    }
}
~~~

暂停期间如果不写 PCM，生成的音频时间轴会跳过这段时间；若希望保留静音，需要向 WAV 写入对应的零采样。

---

## 十五、性能、延迟和缓冲区

### 1. 处理块大小

~~~text
10 ms：适合低延迟通话和 WebRTC
20 ms：普通实时录音的常用起点
40～100 ms：更省调度开销，但延迟更高
~~~

44.1 kHz、单声道、16-bit、20 ms：

~~~text
frames = 44100 × 20 / 1000 = 882
bytes  = 882 × 1 × 2 = 1764
~~~

缓冲区太小可能丢帧或初始化失败，太大则增加延迟。可以从系统最小缓冲区的 2 倍开始测试。

### 2. 复用数组

推荐：

~~~kotlin
val buffer = ShortArray(samplesPerRead)

while (recording) {
    val count = audioRecord.read(buffer, 0, buffer.size)
    if (count > 0) process(buffer, count)
}
~~~

不要在每次循环中创建新数组。波形 UI 也不应保存无限数量的 PCM，只保存降采样后的振幅包络。

### 3. 录音线程不要做的事

- 网络请求；
- 大量日志；
- FileChannel.force() 等频繁同步刷盘；
- 等待 UI；
- 无界集合追加；
- 未测量耗时的复杂模型推理。

高负载场景可以采用：

~~~text
AudioRecord 线程 → 有界 PCM 队列 → 处理/写入线程
~~~

队列必须有上限，并定义满载策略。静默丢弃录音数据通常不可接受，应报告错误或停止会话。

### 4. 时间戳和端到端延迟

API 24+：

~~~kotlin
val timestamp = AudioTimestamp()

if (audioRecord.getTimestamp(
        timestamp,
        AudioTimestamp.TIMEBASE_MONOTONIC
    ) == AudioRecord.SUCCESS
) {
    val positionMs =
        timestamp.framePosition * 1000L / sampleRate
}
~~~

延迟来自麦克风硬件、系统缓冲、应用读取块、DSP、文件或网络队列。需要低延迟时优先使用 10～20 ms 处理块，并减少线程切换和内存分配。

---

## 十六、错误处理与参数回退

### 1. 创建失败的回退策略

~~~kotlin
fun createRecordWithFallback(): AudioRecord {
    val candidates = listOf(
        48_000 to AudioFormat.CHANNEL_IN_MONO,
        44_100 to AudioFormat.CHANNEL_IN_MONO,
        16_000 to AudioFormat.CHANNEL_IN_MONO
    )

    for ((rate, channelMask) in candidates) {
        val minSize = AudioRecord.getMinBufferSize(
            rate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minSize <= 0) continue

        try {
            val record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minSize * 2)
                .build()

            if (record.state ==
                AudioRecord.STATE_INITIALIZED
            ) {
                return record
            }
            record.release()
        } catch (_: Throwable) {
            // 尝试下一个参数组合
        }
    }

    error("Unable to create AudioRecord")
}
~~~

### 2. ERROR_DEAD_OBJECT

出现此错误时，继续使用原对象通常无效：

~~~text
停止读取
释放音频效果和 AudioRecord
关闭当前文件
重新检查权限和设备
用户主动重试时重新创建
~~~

若要自动恢复，应增加有限次数、退避时间和会话边界，避免生成时间不连续的文件。

### 3. 检查 PCM 是否有数据

~~~kotlin
fun inspectPcm(samples: ShortArray, count: Int) {
    var min = Short.MAX_VALUE.toInt()
    var max = Short.MIN_VALUE.toInt()
    var nonZero = 0

    for (i in 0 until count) {
        val value = samples[i].toInt()
        min = minOf(min, value)
        max = maxOf(max, value)
        if (value != 0) nonZero++
    }

    Log.d(
        "PCM",
        "count=" + count +
            " min=" + min +
            " max=" + max +
            " nonZero=" + nonZero
    )
}
~~~

PCM 全为 0 的常见原因是权限、系统静音、麦克风被占用、模拟器没有输入或选错音频源。

---

## 十七、音频处理链：降噪和人声增强

如果要加入降噪、人声增强、EQ 或压缩器，可以把 AudioRecord 读取和文件写入之间拆成处理链：

~~~text
AudioRecord.read()
        ↓
去直流/高通滤波
        ↓
AEC（如果存在播放回声）
        ↓
Noise Suppression
        ↓
人声 EQ
        ↓
压缩器 / AGC
        ↓
Limiter
        ↓
WAV 写入 + 波形 + 网络发送
~~~

先定义一个可插拔接口：

~~~kotlin
interface PcmProcessor {
    /**
     * 处理 samples 的前 count 个样本。
     * 示例约定为原地修改。
     */
    fun process(samples: ShortArray, count: Int)
}

class GainProcessor(
    private val gain: Float
) : PcmProcessor {
    override fun process(samples: ShortArray, count: Int) {
        for (index in 0 until count) {
            val value = (samples[index] * gain).toInt()
            samples[index] = value
                .coerceIn(
                    Short.MIN_VALUE.toInt(),
                    Short.MAX_VALUE.toInt()
                )
                .toShort()
        }
    }
}
~~~

在录音循环中使用：

~~~kotlin
val processors: List<PcmProcessor> = listOf(
    GainProcessor(gain = 1.2f)
)

val count = audioRecord.read(buffer, 0, buffer.size)
if (count > 0) {
    processors.forEach { it.process(buffer, count) }
    writer.writePcm16(buffer, 0, count)
}
~~~

原地处理内存分配少，适合实时录音，但会改变原始数据。如果需要同时保存原始和增强版本，应在处理前复制，或者使用两个输出管道。

---

## 十八、当前项目的推荐落地方式

当前工程已有以下类：

~~~text
com.bigjelly.temporun.recoder.PcmRecoder
com.bigjelly.temporun.recoder.WavFileWriter
com.bigjelly.temporun.recoder.WavPcmPlayer
com.bigjelly.temporun.recoder.WaveformView
com.bigjelly.temporun.RecorderActivity
~~~

推荐职责如下：

~~~text
PcmRecoder
  ├─ AudioRecord 采集
  ├─ 后台线程管理
  ├─ 状态和错误回调
  └─ 峰值回调

WavFileWriter
  ├─ 写 PCM 数据
  └─ 停止时回填 WAV 头

WaveformView
  └─ 根据峰值/RMS 绘制实时包络

WavPcmPlayer
  ├─ 解析 WAV
  └─ AudioTrack 播放 PCM
~~~

加入人声增强时，在 AudioRecord.read() 和 WavFileWriter.writePcm16() 之间插入处理链：

~~~text
AudioRecord
  ↓
系统 NoiseSuppressor/AEC/AGC（可选）
  ↓
高通/EQ/压缩器或 WebRTC/RNNoise（可选）
  ↓
WavFileWriter
~~~

如果要保存原始和增强两份文件，应在处理前复制 PCM，或使用两个写入器；实时场景则优先原地处理并复用数组。

---

## 十九、测试清单

### 1. 功能

- 第一次使用能申请权限；
- 拒绝权限后不崩溃；
- 允许后能够开始和停止；
- 说话时峰值和波形变化；
- 停止后 WAV 可播放；
- 连续录制不会覆盖文件；
- 页面销毁后线程和文件正确关闭。

### 2. 参数和设备

~~~text
44100 / 单声道 / PCM 16-bit
48000 / 单声道 / PCM 16-bit
16000 / 单声道 / PCM 16-bit
内置麦克风
有线耳机麦克风
蓝牙耳机
USB 麦克风
模拟器
~~~

还应测试电话打断、插拔耳机、切后台、锁屏、其他应用占用麦克风和系统设置关闭权限。

### 3. 音质和性能

在安静房间、风扇、键盘、交通声、扬声器回声和多人说话场景下记录：

- 是否丢帧；
- 文件时长和 WAV 头是否正确；
- CPU、内存和功耗；
- 端到端延迟；
- 人声清晰度；
- 降噪是否产生水声、金属声或人声损失。

---

## 二十、常见错误速查

### 错误 1：没有运行时权限就创建录音器

先声明并申请 RECORD_AUDIO，再创建 AudioRecord 或 MediaRecorder。

### 错误 2：在主线程调用阻塞式 read()

把 AudioRecord.read() 放到专用录音线程；UI 只接收聚合后的状态和音量。

### 错误 3：混淆字节数、帧数和样本数

ShortArray.size 是 Short 数量；PCM 16-bit 每个 Short 占 2 字节；双声道一个 frame 包含两个声道样本。

### 错误 4：只停止，不释放

在 finally 中释放 AudioRecord、效果器、线程、WAV Writer 和回调资源。

### 错误 5：把 getMinBufferSize() 当录音数据

它只是系统建议的缓冲区大小，不是 PCM 内容。

### 错误 6：WAV 头没有回填

停止录音后必须更新 RIFF ChunkSize 和 data 块长度，之后才能播放。

### 错误 7：假设所有设备支持相同配置

对采样率、声道、音频源和效果器使用回退方案，并在真实设备上验证。

### 错误 8：长时间录音仍由 Activity 持有

页面内录音可以在 onDestroy() 停止；跨页面、锁屏或后台录音应使用前台 Service。

---

## 二十一、总结

~~~text
只需要一个压缩音频文件
  → MediaRecorder

需要 PCM、WAV、波形或实时处理
  → AudioRecord

需要设备选择和路由
  → AudioManager + AudioDeviceInfo

需要降噪、回声消除或增益
  → AudioEffect / WebRTC / RNNoise / 自定义 DSP

需要锁屏和后台录音
  → 前台 Service + microphone 类型 + 持续通知
~~~

当前项目推荐的主链路是：

~~~text
RECORD_AUDIO 权限
        ↓
AudioRecord 采集 PCM
        ↓
后台线程处理
        ├─→ 峰值/RMS → WaveformView
        ├─→ 降噪/人声增强
        └─→ WavFileWriter
        ↓
WavPcmPlayer / AudioTrack 播放
~~~

先掌握 AudioRecord 的参数、返回值、线程模型和生命周期，再逐步加入音频效果、设备路由和前台服务，可以把“能录音”扩展为稳定、可维护的录音系统。

