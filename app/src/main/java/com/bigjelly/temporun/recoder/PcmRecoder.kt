package com.bigjelly.temporun.recoder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 使用 [AudioRecord] 从麦克风采集原始 PCM 音频，并交给 [WavFileWriter] 保存。
 *
 * 这个类体现了录音功能的基本流程：
 *
 *  * 计算合适的缓冲区大小；
 *  * 创建并启动 [AudioRecord]；
 *  * 在后台线程中不断读取 PCM 样本；
 *  * 将样本写入 WAV 文件，同时计算音量并通知界面；
 *  * 停止录音后释放录音对象，并补写 WAV 文件头中的长度字段。
 *
 * 这里不负责申请运行时录音权限。调用 [start] 之前，调用者必须先获得
 * `Manifest.permission.RECORD_AUDIO`。
 */
class PcmRecoder {

    data class Config(
        /** 采样率，表示每秒采集多少个样本，例如 44,100 Hz。 */
        val sampleRate: Int = 44_100,
        /** 输入声道掩码；[AudioFormat.CHANNEL_IN_MONO] 表示单声道。 */
        val channelMask: Int = AudioFormat.CHANNEL_IN_MONO,
        /** 声道数量，必须和 channelMask 表示的实际声道数一致。 */
        val channelCount: Int = 1,
        /** 每个样本占用的位数；当前实现只处理 16-bit PCM。 */
        val bitsPerSample: Int = 16
    ) {
        init {
            require(sampleRate > 0)
            require(channelCount == 1) { "The sample code records mono audio" }
            require(bitsPerSample == 16) { "The sample code records PCM 16-bit" }
        }
    }

    enum class State {
        IDLE,
        RECORDING,
        STOPPING
    }

    /** 录音状态会被录音线程和界面线程共同访问，因此需要可见性保证。 */
    @Volatile
    var state: State = State.IDLE
        private set

    /** 每次成功读取一批 PCM 样本后回调，参数范围为 0.0 到 1.0。 */
    var onAmplitude: ((Float) -> Unit)? = null
    /** 状态变化回调；回调通常发生在调用线程或录音线程。 */
    var onStateChanged: ((State) -> Unit)? = null
    /** WAV 文件关闭并完成头部回填后回调。 */
    var onStopped: ((File) -> Unit)? = null
    /** 初始化、读取或保存过程中出现异常时回调。 */
    var onError: ((Throwable) -> Unit)? = null

    /** 保护状态切换，避免 start() 和 stop() 同时修改录音生命周期。 */
    private val stateLock = Any()
    /** 后台录音循环使用该标志，stop() 将它设为 true 来请求循环退出。 */
    private val stopRequested = AtomicBoolean(false)

    /** 当前正在使用的 AudioRecord；录音结束后会释放并置空。 */
    @Volatile
    private var audioRecord: AudioRecord? = null
    /** 执行阻塞式 read() 的后台线程。 */
    private var recordingThread: Thread? = null

    /**
     * 开始录音。成功返回true，已在录音或初始化失败时返回false
     */
    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun start(outputFile: File, config: Config = Config()): Boolean {
        synchronized(stateLock) {
            // 一个 PcmRecoder 实例同一时间只允许存在一条录音会话。
            if (state != State.IDLE) return false

            // 这是系统建议的最小 AudioRecord 缓冲区大小，单位是字节。
            // 它不是录音数据本身，只用于帮助我们决定 AudioRecord 的内部缓冲区大小。
            val minBufferSize = AudioRecord.getMinBufferSize(
                config.sampleRate,
                config.channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBufferSize <= 0) {
                onError?.invoke(IllegalStateException("AudioRecord.getMinBufferSize failed"))
                return false
            }

            // 录音线程每次读取约 20ms。AudioRecord 内部缓冲区至少取系统建议值的两倍，
            // 可以降低后台线程调度不及时造成溢出的概率。
            val samplesPerRead = (config.sampleRate / 50) * config.channelCount
            // 16-bit PCM 的一个样本占 2 字节；这里的 requestBytes 是一次读取的数据量。
            val requestBytes = samplesPerRead * 2
            val audioBufferSize = maxOf(minBufferSize, requestBytes * 2)

            var record: AudioRecord? = null
            var writer: WavFileWriter? = null
            try {
                outputFile.parentFile?.mkdirs()
                // WavFileWriter 会先写入 44 字节占位头，录音结束时再填入真实数据长度。
                writer = WavFileWriter(
                    file = outputFile,
                    sampleRate = config.sampleRate,
                    channelCount = config.channelCount,
                    bitsPerSample = config.bitsPerSample
                )
                // AudioRecord 负责从系统音频输入设备取得原始 PCM 数据。
                record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(config.sampleRate)
                            .setChannelMask(config.channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(audioBufferSize)
                    .build()
                val readyRecord = requireNotNull(record)
                val readyWriter = requireNotNull(writer)
                // 构造成功不代表录音对象一定可用，还要检查初始化状态。
                check(readyRecord.state == AudioRecord.STATE_INITIALIZED) {
                    "AudioRecord initialization failed"
                }
                stopRequested.set(false)
                // 从这里开始，系统才真正开始向 AudioRecord 的缓冲区写入麦克风数据。
                readyRecord.startRecording()
                check(readyRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "AudioRecord did not enter recording state"
                }

                audioRecord = readyRecord
                state = State.RECORDING
                onStateChanged?.invoke(State.RECORDING)
                // read() 可能阻塞，不能放在 UI 线程；使用后台线程持续取出音频数据。
                val thread = Thread({
                    recordLoop(
                        record = readyRecord,
                        writer = readyWriter,
                        outputFile = outputFile,
                        samplesPerRead = samplesPerRead
                    )
                }, "pcm-recorder")
                recordingThread = thread
                thread.start()
                return true
            } catch (e: Throwable) {
                // 初始化任一步骤失败时，释放已经创建的对象并关闭文件。
                try {
                    record?.release()
                } catch (_: Throwable) {
                }
                try {
                    writer?.closeAndFinalize()
                } catch (_: Throwable) {
                }
                onError?.invoke(e)
                return false
            }
        }
    }

    fun stop() {
        synchronized(stateLock) {
            if (state != State.RECORDING) return

            state = State.STOPPING
            // 先通知循环退出，再调用 stop() 唤醒可能正在阻塞的 read()。
            stopRequested.set(true)
            onStateChanged?.invoke(State.STOPPING)
            try {
                audioRecord?.stop()
            } catch (error: IllegalStateException) {
                onError?.invoke(error)
            }
        }
    }

    private fun recordLoop(
        record: AudioRecord,
        writer: WavFileWriter,
        outputFile: File,
        samplesPerRead: Int
    ) {
        // AudioRecord.read() 的 length 参数对 ShortArray 来说是“样本数”，不是字节数。
        val buffer = ShortArray(samplesPerRead.coerceAtLeast(1))
        var recordingError: Throwable? = null

        try {
            while (!stopRequested.get()) {
                // READ_BLOCKING 表示没有足够数据时等待；读取成功后再写入 WAV 文件。
                val readCount = record.read(
                    buffer, 0, buffer.size, AudioRecord.READ_BLOCKING
                )
                when {
                    readCount > 0 -> {
                        // readCount 是实际得到的 Short 样本数量，不一定等于 buffer.size。
                        writer.writePcm16(buffer, 0, readCount)
                        // 这里的回调发生在录音线程；更新 View 时应切回主线程。
                        onAmplitude?.invoke(calculatePeak(buffer, readCount))
                    }

                    readCount == AudioRecord.ERROR_INVALID_OPERATION -> {
                        error("AudioRecord returned ERROR_INVALID_OPERATION")
                    }

                    readCount == AudioRecord.ERROR_BAD_VALUE -> {
                        error("AudioRecord returned ERROR_BAD_VALUE")
                    }

                    readCount == AudioRecord.ERROR_DEAD_OBJECT -> {
                        error("AudioRecord returned ERROR_DEAD_OBJECT")
                    }

                    else -> {
                        error("AudioRecord.read failed: $readCount")
                    }
                }
            }
        } catch (error: Throwable) {
            // 如果是主动停止导致的异常，不把它当作录音失败上报。
            if (!stopRequested.get()) recordingError = error
        } finally {
            // 无论正常停止还是发生异常，都必须按顺序停止、释放 AudioRecord，
            // 然后关闭 WAV 写入器，否则文件头中的 dataSize 可能不会被正确回填。
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (_: Throwable) {
            }
            try {
                record.release()
            } catch (_: Throwable) {
            }
            audioRecord = null
            try {
                writer.closeAndFinalize()
            } catch (e: Throwable) {
                if (recordingError == null) recordingError = e
            }
            state = State.IDLE
            recordingThread = null
            recordingError?.let { onError?.invoke(it) }
            onStateChanged?.invoke(State.IDLE)
            onStopped?.invoke(outputFile)
        }
    }

    private fun calculatePeak(sample: ShortArray, count: Int): Float {
        // 16-bit PCM 的取值范围约为 -32768 到 32767，取绝对值最大值作为当前批次音量。
        var maxAbs = 0
        for (index in 0 until count) {
            maxAbs = maxOf(maxAbs, abs(sample[index].toInt()))
        }
        return (maxAbs / 32768f).coerceIn(0f, 1f)
    }
}
