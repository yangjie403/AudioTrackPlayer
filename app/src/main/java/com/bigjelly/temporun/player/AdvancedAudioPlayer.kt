package com.bigjelly.temporun.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlin.math.abs

class AdvancedAudioPlayer {

    companion object {
        private const val TAG = "AdvancedAudioPlayer"
    }

    // 状态与控制标记。
    // 播放、暂停、Seek 和停止操作可能由 UI 线程调用，而真正的解码和写入
    // AudioTrack 在 workerThread 中执行，因此这些变量会被多个线程同时访问。
    @Volatile
    // true 表示播放线程仍然应该继续工作；stop() 将它设置为 false 后，
    // 解码循环会退出，并在 finally 中释放 MediaCodec、AudioTrack 等资源。
    private var isRunning = false

    @Volatile
    // true 表示当前处于暂停状态。暂停时既要暂停 AudioTrack 的实际输出，
    // 也要让解码线程等待，避免继续解码并向 AudioTrack 写入数据。
    private var isPaused = false

    @Volatile
    // 是否循环播放。读取到输入数据末尾时，如果为 true，就把 Extractor
    // 定位回开头并刷新解码器，重新开始解码。
    private var isLooping = false

    @Volatile
    // 待处理的 Seek 目标位置，单位为微秒（us）。-1 表示当前没有等待处理的 Seek。
    // Seek 操作通常由其他线程发起，这里只记录目标位置，真正的 seekTo()、flush()
    // 和 AudioTrack 操作统一放在 workerThread 中执行。
    private var pendingSeekUs: Long = -1L

    // 用于协调“暂停等待”和“继续播放/Seek/停止”之间的线程通信。
    // workerThread 在暂停时调用 wait() 释放锁并进入等待；resume()、seekTo()、stop()
    // 修改状态后调用 notifyAll() 唤醒它。
    private val pauseLock = Object()
    // 所有 MediaExtractor、MediaCodec 和 AudioTrack 的核心操作都在这个线程中执行，
    // 避免多个线程同时操作解码器或音频输出对象。
    private var workerThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    // 进度回调。Handler 绑定主线程 Looper，确保 UI 回调不会直接在音频解码线程中执行。
    private val mainHandler = Handler(Looper.getMainLooper())
    // currentMs：当前输出 PCM 对应的时间位置；totalMs：音频总时长。
    // 调用方可以用这两个值更新 SeekBar、播放时间文本等 UI。
    var onProgressListener: ((currentMs: Long, totalMs: Long) -> Unit)? = null
    // 解码器输出带有 BUFFER_FLAG_END_OF_STREAM，且所有输出数据处理完成后触发。
    var onCompletionListener: (() -> Unit)? = null

    // 音频总时长，单位为毫秒。private set 表示外部只能读取，不能修改；
    // 它会在成功读取 MediaFormat 后由播放器内部设置。
    var durationMs: Long = 0L
        private set

    /**
     * 播放 assets 目录音频
     */
    fun playFromAssets(context: Context, assetName: String, loop: Boolean = false) {
        val afd = context.assets.openFd(assetName)
        startInternal(loop) { extractor ->
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
    }

    /**
     * 播放本地文件路径
     */
    fun playFromFilePath(path: String, loop: Boolean = false) {
        startInternal(loop) { extractor ->
            extractor.setDataSource(path)
        }
    }

    /**
     * 跳转到指定进度
     */
    fun seekTo(positionMs: Long) {
        // 对外使用毫秒，对 MediaExtractor/MediaCodec 使用微秒，因此需要换算。
        // 这里没有立即操作 Extractor，因为 Extractor 只应由 workerThread 操作；
        // 先保存目标位置，再由解码循环安全地执行跳转。
        pendingSeekUs = positionMs * 1000L
        // 如果当前处于暂停等待状态，唤醒 workerThread，让它先处理 Seek。
        // 如果线程没有在等待，notifyAll() 不会积累通知，但 pendingSeekUs 会保留，
        // workerThread 下一轮循环仍然会检查到它。
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    fun setLooping(loop: Boolean) {
        // 循环播放标志可以在播放期间修改；下一次读到输入末尾时会使用最新值。
        this.isLooping = loop
    }

    fun pause() {
        if (isRunning && !isPaused) {
            // 先修改共享状态，再暂停 AudioTrack。workerThread 下一次循环会看到
            // isPaused=true，并在 pauseLock 上等待。
            isPaused = true
            // AudioTrack.pause() 暂停音频输出，并保留其内部缓冲区中的数据。
            // 但本实现随后会让解码线程等待，因此不会继续产生新的 PCM 数据。
            audioTrack?.pause()
        }
    }

    fun resume() {
        if (isRunning && isPaused) {
            // 清除暂停标记，允许解码循环继续执行。
            isPaused = false
            // AudioTrack.play() 从暂停位置继续播放。对于 MODE_STREAM，仍然需要
            // 解码线程继续 write() PCM 数据，play() 不会自动生成音频数据。
            audioTrack?.play()
            synchronized(pauseLock) {
                // 唤醒 wait() 中的 workerThread。被唤醒后它会重新检查 while 条件，
                // 防止虚假唤醒或多个控制操作造成错误状态。
                pauseLock.notifyAll()
            }
        }
    }

    fun stop() {
        // stop() 是“请求停止”，这里只修改状态并唤醒/中断工作线程；真正的
        // AudioTrack.stop()/release() 和 MediaCodec.stop()/release() 在 workerThread
        // 的 finally 中执行，以便集中清理资源。
        isRunning = false
        isPaused = false
        pendingSeekUs = -1L
        synchronized(pauseLock) {
            // 如果线程正因暂停而 wait()，仅修改 isRunning 不会自动唤醒它，
            // 所以必须 notifyAll()。
            pauseLock.notifyAll()
        }
        // 如果线程正阻塞在某些可中断操作上，interrupt() 尝试让它尽快返回。
        // 之后 finally 仍负责释放底层资源。
        workerThread?.interrupt()
        workerThread = null
    }

    private fun startInternal(loop: Boolean, dataSourceSetter: (MediaExtractor) -> Unit) {
        // 开始新播放前先停止旧播放，避免多个线程同时操作共享的 audioTrack，
        // 也避免旧音频和新音频同时输出。
        stop()
        this.isLooping = loop
        this.isRunning = true
        this.isPaused = false
        this.pendingSeekUs = -1L

        workerThread = Thread {
            // 音频播放线程使用较高的线程优先级，降低解码或写入不及时导致的卡顿概率。
            // 这不是实时线程保证，也不能替代合理的缓冲区和错误处理。
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null

            try {
                dataSourceSetter(extractor)
                // 获取音轨信息与总时长
                var audioTrackIndex = -1
                var audioFormat: MediaFormat? = null
                var mime = ""
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val trackMime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (trackMime.startsWith("audio/")) {
                        audioTrackIndex = i
                        audioFormat = format
                        mime = trackMime
                        break
                    }
                }
                if (audioTrackIndex == -1 || audioFormat == null) {
                    Log.e(TAG, "No audio track found in $mime")
                    return@Thread
                }
                extractor.selectTrack(audioTrackIndex)

                // 获取时长。MediaFormat 中的 duration 单位是微秒，转换为毫秒后
                // 对外提供给 UI 使用。
                val durationUs =
                    if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                        audioFormat.getLong(MediaFormat.KEY_DURATION)
                    } else 0L
                durationMs = durationUs / 1000L

                val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val channelConfig =
                    if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO
                    else AudioFormat.CHANNEL_OUT_STEREO

                // 构建 AudioTrack。它负责把解码器输出的 PCM 数据送到系统音频设备；
                // MediaCodec 负责解码，二者通过后面的 write() 连接起来。
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufferSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                // 构建 MediaCodec 解码器，并进入执行状态。
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(audioFormat, null, null, 0)
                decoder.start()
                // 让 AudioTrack 进入播放状态。MODE_STREAM 下后续仍需不断 write() PCM。
                audioTrack?.play()

                val bufferInfo = MediaCodec.BufferInfo()
                val timeoutUs = 10000L
                var isEOS = false
                var lastProgressUpdateMs = 0L

                // 核心逻辑：
                // 1. 优先处理外部发起的 Seek；
                // 2. 如果暂停则等待；
                // 3. 把压缩数据送入 MediaCodec；
                // 4. 取出解码后的 PCM，写入 AudioTrack，并报告进度。
                while (isRunning) {
                    // 检查是否触发了 Seek。Seek 的实际执行必须在工作线程中完成，
                    // 以保证 MediaExtractor、MediaCodec 和 AudioTrack 的操作顺序一致。
                    if (pendingSeekUs >= 0) {
                        val seekTarget = pendingSeekUs
                        // 先取出并清除请求，避免同一个 Seek 被重复执行。
                        pendingSeekUs = -1L

                        // 将 Extractor 定位到目标时间附近。SEEK_TO_CLOSEST_SYNC 表示
                        // 定位到距离目标最近的同步点（音频通常是可独立解码的帧边界）。
                        // 因为压缩数据可能只能从特定边界开始解码，所以实际位置可能
                        // 与请求位置存在少量差异。
                        extractor.seekTo(seekTarget, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                        // 清理解码器中残留的输入/输出状态，防止跳转前的数据在 Seek 后
                        // 继续输出。flush() 后，之前取得的缓冲区索引不能继续使用，
                        // 后续必须重新 dequeue 缓冲区。
                        decoder.flush()

                        // 先暂停 AudioTrack，避免清空缓冲区时继续输出旧音频。
                        audioTrack?.pause()

                        // flush() 清空 AudioTrack 内部尚未播放的 PCM 数据。
                        // 这样 Seek 后不会先听到跳转前残留的声音。
                        audioTrack?.flush()

                        // 如果当前不是暂停状态，Seek 完成后恢复 AudioTrack 输出；
                        // 如果用户本来就在暂停，则保持暂停，等待 resume()。
                        if (!isPaused) {
                            audioTrack?.play()
                        }
                        // Seek 后允许继续向解码器输入数据，即使之前已经到达过 EOS。
                        isEOS = false
                    }

                    // 暂停控制逻辑。
                    // synchronized 保证检查状态和调用 wait() 之间不会错过通知。
                    // while 而不是 if 是必要的：线程被唤醒后必须重新确认状态，
                    // 因为 wait() 可能发生虚假唤醒，也可能由 Seek/Stop 唤醒。
                    synchronized(pauseLock) {
                        while (isPaused && isRunning && pendingSeekUs < 0) {
                            // wait() 会释放 pauseLock，让 resume()/seekTo()/stop() 能够
                            // 进入 synchronized 区域；被 notifyAll() 唤醒后会重新抢锁并
                            // 再次检查 while 条件。
                            pauseLock.wait()
                        }
                    }
                    // stop() 可能在等待结束后被调用，因此退出等待后还要再次检查。
                    if (!isRunning) break

                    // 输入端：从 MediaExtractor 读取压缩音频，并送入 MediaCodec。
                    // 暂停时线程不会走到这里，因此暂停期间不会继续消耗音频数据。
                    if (!isEOS) {
                        // 获取一个可写的输入缓冲区。没有缓冲区时返回负值，本轮稍后重试。
                        val inputIndex = decoder.dequeueInputBuffer(timeoutUs)
                        if (inputIndex >= 0) {
                            // 取得输入 ByteBuffer，写入一块 MP3/AAC 等压缩数据。
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                // 将 Extractor 当前样本复制到 MediaCodec 输入缓冲区，
                                // 返回值是读取的字节数，负值表示输入数据已经读完。
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    if (isLooping) {
                                        // 循环播放：回到文件开头并刷新解码器中的状态。
                                        // 不发送 EOS，因为还要继续向同一个解码器输入新一轮数据。
                                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                        decoder.flush()
                                    } else {
                                        // 非循环播放：提交一个“空数据 + EOS 标志”的输入
                                        // 缓冲区，通知解码器不会再有新的压缩数据。
                                        decoder.queueInputBuffer(
                                            inputIndex,
                                            0,
                                            0,
                                            0L,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                        isEOS = true
                                    }
                                } else {
                                    // 提交当前压缩样本。sampleTime 是该样本的时间戳，
                                    // 单位为微秒；解码后的输出 BufferInfo 会携带对应时间信息。
                                    decoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        sampleSize,
                                        extractor.sampleTime,
                                        0
                                    )
                                    // 提交成功后移动到下一个媒体样本。
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    // 输出端：从 MediaCodec 取出解码后的 PCM，并写入 AudioTrack。
                    // 输入和输出是流水线关系：即使已经提交输入，也可能暂时没有输出，
                    // 所以 dequeueOutputBuffer() 需要持续轮询。
                    val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    if (outputIndex >= 0) {
                        // 根据输出索引获取 PCM ByteBuffer。bufferInfo 描述其中有效数据的
                        // offset、size、presentationTimeUs 和 flags。
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // 只读取输出缓冲区中由 MediaCodec 标记为有效的范围。
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            // AudioTrack.write() 将 PCM 复制到 AudioTrack 的内部缓冲区，
                            // 后续由系统音频线程按照采样率播放。WRITE_BLOCKING 表示内部
                            // 缓冲区空间不足时等待，而不是立即丢弃或只写入一部分。
                            audioTrack?.write(
                                outputBuffer,
                                bufferInfo.size,
                                AudioTrack.WRITE_BLOCKING
                            )

                            // 节流分发播放进度给 UI，大约每 100ms 刷新一次。
                            // presentationTimeUs 是当前 PCM 对应的媒体时间戳，不是文件
                            // 字节偏移；因此可用于显示播放进度。
                            val currentPositionMs = bufferInfo.presentationTimeUs / 1000L
                            if (abs(currentPositionMs - lastProgressUpdateMs) >= 100) {
                                lastProgressUpdateMs = currentPositionMs
                                // Handler.post() 把回调投递到主线程，避免 UI 控件被后台
                                // 解码线程直接访问。
                                mainHandler.post {
                                    onProgressListener?.invoke(currentPositionMs, durationMs)
                                }
                            }
                        }
                        // 输出缓冲区使用完毕后必须释放，否则 MediaCodec 可能没有可用的
                        // 输出缓冲区，导致后续解码停滞。false 表示不渲染到 Surface，
                        // 因为这里的输出是音频 PCM，需要手动交给 AudioTrack。
                        decoder.releaseOutputBuffer(outputIndex, false)
                        // 输出 EOS 表示解码器已经把此前输入的数据全部输出完毕；
                        // 此时才可以认为播放完成并退出循环。
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            // 同样切换到主线程通知 UI，例如更新播放按钮或进度条状态。
                            mainHandler.post { onCompletionListener?.invoke() }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback Exception", e)
            } finally {
                // 无论正常播放结束、用户 stop()，还是发生异常，都统一进入这里清理。
                // 先更新状态，防止外部继续认为播放器仍在运行。
                isRunning = false
                isPaused = false
                try {
                    // 停止并释放 AudioTrack。release() 后该对象不能再次使用，
                    // 下一次播放需要重新创建。
                    audioTrack?.stop()
                    audioTrack?.release()
                    audioTrack = null
                    // 停止并释放 MediaCodec。release() 用于归还系统编解码器资源。
                    decoder?.stop()
                    decoder?.release()
                    // 释放 MediaExtractor 打开的媒体数据源。
                    extractor.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error while releasing resources", e)
                }
            }
        }.apply { start() }
    }
}