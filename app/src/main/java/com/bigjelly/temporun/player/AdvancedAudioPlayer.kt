package com.bigjelly.temporun.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * 基于 MediaExtractor、MediaCodec 和 AudioTrack 的音频播放器。
 *
 * 播放链路是：
 * 压缩音频文件 -> MediaExtractor 读取音频帧 -> MediaCodec 解码为 PCM ->
 * AudioTrack 将 PCM 写入系统音频输出。
 *
 * 每次调用 playFromAssets()/playFromFilePath() 都会创建一个新的播放会话。
 * 会话独立持有自己的线程和底层资源，旧会话退出后才能开始新会话。
 */
class AdvancedAudioPlayer {

    companion object {
        private const val TAG = "AdvancedAudioPlayer"
        private const val PROGRESS_INTERVAL_MS = 100L
    }

    // 保护 currentSession 的替换。底层音频资源不在这里共享，而是归会话所有。
    private val stateLock = Any()

    // 播放线程暂停时在这里等待；pause()/resume()/seekTo()/stop() 会唤醒它。
    private val pauseLock = Object()

    // 回调必须切换到主线程，调用方可以在回调中直接更新 UI。
    private val mainHandler = Handler(Looper.getMainLooper())

    // 每次开始或停止播放都会改变代数，用于丢弃旧会话已经排队的回调。
    private val generation = AtomicLong(0L)

    // 当前正在使用的会话。停止时先从这里摘除旧会话，再等待它退出。
    @Volatile
    private var currentSession: PlaybackSession? = null

    /** 每解码约 100ms 的音频，回调一次当前实际播放位置和总时长。 */
    var onProgressListener: ((currentMs: Long, totalMs: Long) -> Unit)? = null

    /** 非循环播放完成后回调。停止播放不会触发此回调。 */
    var onCompletionListener: (() -> Unit)? = null

    @Volatile
    var durationMs: Long = 0L
        private set

    /** 一次播放的全部状态和资源。旧会话不得释放新会话的资源。 */
    private class PlaybackSession(val id: Long) {
        // 以下状态可能由 UI/音频焦点线程和播放线程共同访问，因此需要可见性保证。
        @Volatile
        var stopRequested = false
        @Volatile
        var paused = false
        @Volatile
        var looping = false
        @Volatile
        var pausedByFocus = false

        // -1 表示没有待处理的 seek；真正的 seek 在播放线程中执行。
        @Volatile
        var pendingSeekUs = -1L

        // 下面的资源只属于当前会话，由当前会话的线程创建和释放。
        var thread: Thread? = null
        var audioTrack: AudioTrack? = null
        var audioManager: AudioManager? = null
        var audioFocusRequest: AudioFocusRequest? = null
        var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    }

    fun playFromAssets(context: Context, assetName: String, loop: Boolean = false) {
        // 在播放线程中打开并关闭 AssetFileDescriptor，避免文件描述符泄漏。
        startInternal(context, loop) { extractor ->
            context.assets.openFd(assetName).use { afd ->
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
        }
    }

    fun playFromFilePath(path: String, loop: Boolean = false) {
        // 文件路径不需要额外关闭文件描述符；MediaExtractor 会管理数据源。
        startInternal(null, loop) { extractor -> extractor.setDataSource(path) }
    }

    fun seekTo(positionMs: Long) {
        // 不直接操作 MediaExtractor/MediaCodec；所有解码器操作都交给播放线程。
        val session = currentSession ?: return
        val safePositionMs = positionMs.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1000L)
        session.pendingSeekUs = safePositionMs * 1000L
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    fun setLooping(loop: Boolean) {
        // 循环标志在下一次 EOS 处理时读取，因此可以在播放过程中切换。
        currentSession?.looping = loop
    }

    fun pause() {
        val session = currentSession ?: return
        if (!session.stopRequested) {
            // 设置标志后，播放线程会在 pauseLock 上等待，避免继续解码和写入。
            session.paused = true
            session.audioTrack?.pause()
        }
    }

    fun resume() {
        val session = currentSession ?: return
        if (!session.stopRequested && session.paused) {
            // 先恢复状态，再唤醒播放线程；线程被唤醒后会重新检查状态。
            session.paused = false
            session.pausedByFocus = false
            session.audioTrack?.play()
            synchronized(pauseLock) { pauseLock.notifyAll() }
        }
    }

    /**
     * 请求停止，并等待当前播放线程完成资源释放。
     *
     * 等待很重要：如果不等待，旧线程的 finally 可能在新线程启动后释放新线程的
     * AudioTrack 或 MediaCodec。
     */
    fun stop() {
        val session = synchronized(stateLock) {
            val oldSession = currentSession
            currentSession = null
            generation.incrementAndGet()
            oldSession
        }

        // 这些回调只由本播放器的 Handler 管理，可以全部移除。
        mainHandler.removeCallbacksAndMessages(null)
        if (session == null) return

        // 唤醒暂停中的线程，并中断可能正在等待的阻塞操作。
        session.stopRequested = true
        session.paused = false
        synchronized(pauseLock) { pauseLock.notifyAll() }
        session.thread?.interrupt()

        // 播放线程自己不能 join 自己；其他线程必须等它完成清理。
        if (Thread.currentThread() !== session.thread) {
            try {
                session.thread?.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Interrupted while waiting for playback thread", e)
            }
        }
    }

    private fun startInternal(
        context: Context?,
        loop: Boolean,
        dataSourceSetter: (MediaExtractor) -> Unit
    ) {
        // 先完整停止旧会话，再创建新会话，保证资源所有权不会交叉。
        stop()
        val session = synchronized(stateLock) {
            PlaybackSession(generation.incrementAndGet()).also {
                it.looping = loop
                currentSession = it
                durationMs = 0L
            }
        }

        session.thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            // 这些对象全部在当前播放线程中创建、使用和释放。
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            var focusAcquired = false
            try {
                // 第一步：设置数据源，并扫描出第一个音频轨道。
                dataSourceSetter(extractor)
                var trackIndex = -1
                var format: MediaFormat? = null
                var mime = ""
                for (i in 0 until extractor.trackCount) {
                    val candidate = extractor.getTrackFormat(i)
                    val candidateMime = candidate.getString(MediaFormat.KEY_MIME) ?: ""
                    if (candidateMime.startsWith("audio/")) {
                        trackIndex = i
                        format = candidate
                        mime = candidateMime
                        break
                    }
                }
                check(trackIndex >= 0 && format != null) { "No audio track found in $mime" }
                // 选中音频轨道后，Extractor.sampleTime 会提供每个压缩帧的时间戳。
                extractor.selectTrack(trackIndex)

                val audioFormat = requireNotNull(format)
                durationMs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    audioFormat.getLong(MediaFormat.KEY_DURATION) / 1000L
                } else {
                    0L
                }

                val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val channelConfig = when (channelCount) {
                    1 -> AudioFormat.CHANNEL_OUT_MONO
                    2 -> AudioFormat.CHANNEL_OUT_STEREO
                    else -> error("Unsupported channel count: $channelCount")
                }
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                check(minBufferSize > 0) { "Invalid AudioTrack buffer size: $minBufferSize" }

                // 第三步：创建 AudioTrack。它接收的是解码后的 PCM，而不是 MP3 文件。
                session.audioTrack = AudioTrack.Builder()
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
                check(session.audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                    "AudioTrack initialization failed"
                }

                // 播放前申请音频焦点，避免与其他媒体应用无序混音。
                if (context != null) {
                    focusAcquired = requestAudioFocus(context, session)
                    check(focusAcquired) { "Unable to acquire audio focus" }
                }

                // 第二步：创建并启动 MediaCodec，将压缩音频解码为 PCM。
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(audioFormat, null, null, 0)
                decoder.start()
                if (!session.paused) session.audioTrack?.play()

                // 第四步：循环执行“送入压缩帧 -> 取出 PCM -> 写入 AudioTrack”。
                decodeLoop(session, extractor, decoder, sampleRate)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.d(TAG, "Playback interrupted")
            } catch (e: Exception) {
                if (!session.stopRequested) Log.e(TAG, "Playback exception", e)
            } finally {
                releaseSession(session, decoder, extractor, focusAcquired)
            }
        }.apply {
            name = "AdvancedAudioPlayer-${session.id}"
            start()
        }
    }

    private fun decodeLoop(
        session: PlaybackSession,
        extractor: MediaExtractor,
        decoder: MediaCodec,
        sampleRate: Int
    ) {
        // MediaCodec 是流水线：输入端和输出端不是一一同步对应的，
        // 所以即使没有新的输出，也必须继续轮询，直到收到输出 EOS。
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 10_000L

        // 输入 EOS：Extractor 已经没有更多压缩帧。
        // 输出 EOS：Codec 已经把此前收到的压缩帧全部解码输出。
        // 循环播放必须等到“输出 EOS”后才能重新 seek，不能在输入结束时直接 flush。
        var inputEosSent = false

        // AudioTrack 的 playback head 表示已经真正播放到扬声器的 PCM 帧数，
        // 比使用 Codec 的 presentationTimeUs 更接近用户听到的实际进度。
        var lastProgressMs = -PROGRESS_INTERVAL_MS
        var playbackStartFrame = playbackHeadFrames(session.audioTrack)
        var positionBaseMs = 0L

        while (!session.stopRequested) {
            // seek 请求只记录目标位置，实际操作由本播放线程完成，保证顺序安全。
            val seekUs = session.pendingSeekUs
            if (seekUs >= 0L) {
                // 取出这次 seek 请求后立即重置为 -1，表示请求已经被当前线程接管。
                // 如果用户连续拖动进度条，后续请求会覆盖旧请求，播放器只处理最新位置。
                session.pendingSeekUs = -1L

                // MediaExtractor 只能定位到压缩音频允许的同步点附近。
                // 因此实际起始位置可能与 seekUs 存在少量误差，这是压缩音频的正常现象。
                extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                // 清空 MediaCodec 中 seek 之前已经排队的输入和输出数据，避免旧位置的
                // 音频继续被解码或播放。flush 后，之前取得的 buffer 索引不能再使用。
                decoder.flush()

                // 先暂停 AudioTrack，再清空它内部尚未播放的 PCM 缓冲区，防止 seek 后
                // 先听到跳转前残留的声音。AudioTrack 的 flush 不会影响 MediaExtractor。
                session.audioTrack?.pause()
                session.audioTrack?.flush()

                // seek/flush 后重新记录播放头基准。后续计算进度时，当前播放头减去这个
                // 新基准，得到的才是从 seek 目标开始实际播放的 PCM 帧数。
                playbackStartFrame = playbackHeadFrames(session.audioTrack)

                // playbackStartFrame 是 AudioTrack 的物理计数，positionBaseMs 是媒体文件
                // 的逻辑时间。两者结合后，进度条可以从用户指定的 seek 位置继续显示。
                positionBaseMs = seekUs / 1000L

                // seek 后允许重新向解码器输入数据；如果之前已经发送过输入 EOS，
                // 必须清除该状态，否则播放器不会再读取新位置的数据。
                inputEosSent = false

                // 重新从 seek 位置开始节流进度回调，避免沿用 seek 前的时间戳判断。
                lastProgressMs = -PROGRESS_INTERVAL_MS

                // 如果用户不是处于暂停状态，seek 完成后恢复 AudioTrack。
                // 如果当前是暂停状态，则保持暂停，等待 resume() 再播放。
                if (!session.paused) session.audioTrack?.play()
            }

            synchronized(pauseLock) {
                // 暂停时不再向 Codec 取数据，也不再向 AudioTrack 写数据。
                while (session.paused && !session.stopRequested && session.pendingSeekUs < 0L) {
                    pauseLock.wait()
                }
            }
            if (session.stopRequested) break
            if (session.pendingSeekUs >= 0L) {
                continue
            }

            if (!inputEosSent) {
                // 输入阶段：从媒体文件读取一帧压缩数据，送入 Codec 输入缓冲区。
                val inputIndex = decoder.dequeueInputBuffer(timeoutUs)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)
                    if (inputBuffer == null) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                    } else {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            // 输入结束只是发送 EOS 标记，后面仍要继续读取 Codec 输出。
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEosSent = true
                        } else {
                            // sampleTime 是压缩帧的媒体时间戳，单位是微秒。
                            decoder.queueInputBuffer(
                                inputIndex, 0, sampleSize, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }
            }

            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 解码器可能在启动后才确定最终 PCM 格式。当前实现记录该事件；
                // 生产环境还应根据 outputFormat 校验采样率、声道数和 PCM 编码。
                Log.d(TAG, "Decoder output format: ${decoder.outputFormat}")
                continue
            }
            if (outputIndex < 0) continue

            val outputFlags = bufferInfo.flags
            val outputSize = bufferInfo.size
            decoder.getOutputBuffer(outputIndex)?.let { outputBuffer ->
                if (outputSize > 0) {
                    // 输出阶段：Codec 给出 PCM，AudioTrack.write() 把 PCM 放入系统缓冲区。
                    // writeFully() 会处理非阻塞写入导致的部分写入和暂时不可写。
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + outputSize)
                    writeFully(session, outputBuffer, outputSize)
                }
            }
            decoder.releaseOutputBuffer(outputIndex, false)

            if (outputSize > 0) {
                // 这里使用 AudioTrack 的实际播放头计算进度，而不是解码时间戳。
                val currentMs = calculatePlaybackPosition(
                    session, playbackStartFrame, positionBaseMs, sampleRate
                )
                if (abs(currentMs - lastProgressMs) >= PROGRESS_INTERVAL_MS) {
                    lastProgressMs = currentMs
                    postProgress(session, currentMs)
                }
            }

            if (outputFlags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                if (session.looping && !session.stopRequested) {
                    // 只有收到输出 EOS，上一轮数据才算完整 drain；此时再 seek 并 flush。
                    // 不 flush AudioTrack，避免丢掉已经写入但尚未播放的尾部 PCM。
                    extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    decoder.flush()
                    inputEosSent = false
                    lastProgressMs = -PROGRESS_INTERVAL_MS
                } else {
                    // 非循环播放：所有 PCM 输出完成后通知进度和播放完成。
                    postProgress(session, durationMs)
                    postCompletion(session)
                    break
                }
            }
        }
    }

    private fun writeFully(session: PlaybackSession, buffer: ByteBuffer, size: Int) {
        // WRITE_NON_BLOCKING 可能只写入部分字节，甚至暂时返回 0；
        // 必须持续写完当前 PCM buffer，否则会造成声音断裂。
        var written = 0
        while (written < size && !session.stopRequested) {
            val result = session.audioTrack?.write(
                buffer, size - written, AudioTrack.WRITE_NON_BLOCKING
            ) ?: -1
            when {
                result < 0 -> throw IOException("AudioTrack.write failed: $result")
                result == 0 -> Thread.yield()
                else -> written += result
            }
        }
    }

    private fun calculatePlaybackPosition(
        session: PlaybackSession,
        startFrame: Long,
        baseMs: Long,
        sampleRate: Int
    ): Long {
        // 读取 AudioTrack 当前已经实际播放出去的 PCM 音频帧数。
        // 这个值代表“听到的播放位置”，通常比 MediaCodec 的解码时间戳更准确。
        val currentFrame = playbackHeadFrames(session.audioTrack)

        // startFrame 是开始播放或上一次 seek 时记录的帧位置。
        // 两者相减，就得到从该起点开始实际播放了多少帧。
        //
        // 理论上 currentFrame 不应小于 startFrame，但在 AudioTrack flush、设备音频
        // 路由变化或播放头回绕时可能出现负数。这里将负值限制为 0，避免进度倒退。
        val elapsedFrames = (currentFrame - startFrame).coerceAtLeast(0L)

        // 音频帧转换为毫秒：
        //   播放时长 = 音频帧数 / 每秒帧数
        //            = elapsedFrames * 1000 / sampleRate
        // baseMs 用于补偿 seek 后的逻辑起点。例如 seek 到 30 秒后，
        // 即使 AudioTrack 从新的基准帧开始计数，UI 仍然应该从 30,000ms 开始显示。
        val elapsedMs = elapsedFrames * 1000L / sampleRate
        val position = baseMs + elapsedMs

        // 循环播放时，AudioTrack 的播放帧数会持续增加，position 也会超过音频总时长。
        // 使用总时长取模，将进度重新映射到 [0, durationMs) 范围内：
        // 例如总时长 10 秒，当前逻辑位置 12 秒，UI 显示为 2 秒。
        // 非循环播放则直接返回累计位置。
        return if (session.looping && durationMs > 0L) {
            position % durationMs
        } else {
            position
        }
    }

    private fun playbackHeadFrames(track: AudioTrack?): Long {
        // AudioTrack.playbackHeadPosition 表示“已经实际播放出去的 PCM 音频帧数”。
        // 它不是已经写入 AudioTrack 的字节数，也不是 MediaCodec 当前的解码时间戳。
        // 例如采样率为 44,100Hz 时，播放 44,100 个音频帧约等于播放了 1 秒。
        //
        // 该 Android API 的返回类型是 Int，但播放帧计数本质上是无符号的 32 位数。
        // 当计数超过 Int.MAX_VALUE 后，Java/Kotlin 的有符号 Int 会发生回绕，可能表现为
        // 从正数突然变成负数。因此先转成 Long，再用 0xffffffffL 保留低 32 位，
        // 将它解释成无符号数，避免播放进度计算出现负值。
        //
        // track 为空通常表示 AudioTrack 尚未创建，或者已经被释放。此时返回 0，
        // 让调用方可以安全计算进度，而不需要额外处理 NullPointerException。
        return track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0L
    }

    private fun postProgress(session: PlaybackSession, currentMs: Long) {
        // 只允许当前会话更新 UI。stop() 或新播放会递增 generation，旧回调会被丢弃。
        val total = durationMs
        mainHandler.post {
            if (generation.get() == session.id) {
                onProgressListener?.invoke(currentMs.coerceAtLeast(0L), total)
            }
        }
    }

    private fun postCompletion(session: PlaybackSession) {
        // completion 也必须做会话校验，避免旧音频完成时清空新音频的 UI 状态。
        mainHandler.post {
            if (generation.get() == session.id) {
                onCompletionListener?.invoke()
            }
        }
    }

    private fun requestAudioFocus(context: Context, session: PlaybackSession): Boolean {
        // 音频焦点可以理解为“当前哪个应用拥有播放音频的优先权”。
        // 例如，音乐播放时来电话或其他应用开始播放语音，系统会通知本播放器失去焦点。
        // 如果播放器不申请焦点，多个应用可能同时播放，或者无法正确响应来电/导航播报。
        //
        // Android 8.0（API 26）开始推荐 AudioFocusRequest；API 25 及以下使用旧 API。
        // 本实现的策略是：暂时失去焦点时暂停，重新获得焦点后恢复；永久失去焦点时停止。
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 这是系统发生焦点变化时调用的监听器。回调参数 change 表示焦点变化的类型。
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // 永久失去焦点：例如用户开始播放另一段长期媒体。
                    // 当前会话必须停止，后续由 finally 释放 AudioTrack、Codec 和 Extractor。
                    session.stopRequested = true

                    // 播放线程可能正处于暂停等待状态；只设置 stopRequested 不会自动唤醒 wait()，
                    // 因此需要 notifyAll()，让播放线程及时检查停止标志并退出。
                    synchronized(pauseLock) { pauseLock.notifyAll() }
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // LOSS_TRANSIENT：短暂失去焦点，例如导航播报或短时间语音提示。
                    // LOSS_TRANSIENT_CAN_DUCK：系统允许当前音频继续，但通常应降低音量。
                    //
                    // 当前示例为了逻辑简单，将这两种情况都处理为暂停，而不是降低音量。
                    // 如果希望支持 duck，可以在这里调用 audioTrack?.setVolume(较小音量)，
                    // 在 AUDIOFOCUS_GAIN 中再恢复原音量。
                    session.pausedByFocus = true
                    session.paused = true
                    session.audioTrack?.pause()

                    // 如果播放线程正在 pauseLock.wait()，唤醒它重新检查状态。
                    synchronized(pauseLock) { pauseLock.notifyAll() }
                }

                AudioManager.AUDIOFOCUS_GAIN -> {
                    // 重新获得焦点。只有此前确实是因为音频焦点而暂停，才自动恢复。
                    // 这样可以避免用户手动暂停后，其他应用结束播放时又被意外恢复。
                    if (session.pausedByFocus && !session.stopRequested) {
                        session.pausedByFocus = false
                        session.paused = false
                        session.audioTrack?.play()

                        // 唤醒播放线程，让它继续向 MediaCodec 和 AudioTrack 提供数据。
                        synchronized(pauseLock) { pauseLock.notifyAll() }
                    }
                }
            }
        }

        // 保存 AudioManager 和监听器，释放会话时还要用同一个监听器/请求来放弃焦点。
        session.audioManager = manager
        session.focusListener = listener

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // API 26+：使用 AudioFocusRequest 描述本次焦点申请。
            // AUDIOFOCUS_GAIN 表示这是长期媒体播放，而不是一声短提示音。
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // USAGE_MEDIA 告诉系统这是音乐/媒体播放，系统会据此进行音频策略管理。
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        // CONTENT_TYPE_MUSIC 表示内容类型是音乐，而不是语音或提示音。
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                // 将上面的监听器交给系统，焦点变化时系统会调用它。
                .setOnAudioFocusChangeListener(listener)
                .build()
            session.audioFocusRequest = request

            // 返回 AUDIOFOCUS_REQUEST_GRANTED 才表示申请成功，可以继续播放。
            // 申请失败时抛出异常，外层 finally 仍会释放已经创建的资源。
            return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        // API 25 及以下的旧接口：需要传入监听器、音频流类型和焦点类型。
        // 这里使用 STREAM_MUSIC，与 AudioTrack 的媒体音乐用途保持一致。
        @Suppress("DEPRECATION")
        return manager.requestAudioFocus(
            listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseSession(
        session: PlaybackSession,
        decoder: MediaCodec?,
        extractor: MediaExtractor,
        focusAcquired: Boolean
    ) {
        // finally 必须具备幂等性：即使初始化只完成了一半，也要逐个尝试释放资源。
        // 每个资源单独 try/catch，避免一个 stop/release 异常阻止其他资源释放。
        session.stopRequested = true
        session.paused = false
        synchronized(pauseLock) { pauseLock.notifyAll() }

        try {
            session.audioTrack?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack.stop failed", e)
        }
        try {
            session.audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack.release failed", e)
        }
        session.audioTrack = null

        try {
            decoder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodec.stop failed", e)
        }
        try {
            decoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodec.release failed", e)
        }
        try {
            extractor.release()
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor.release failed", e)
        }

        if (focusAcquired) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                session.audioFocusRequest?.let { session.audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                session.focusListener?.let { session.audioManager?.abandonAudioFocus(it) }
            }
        }

        synchronized(stateLock) {
            if (currentSession === session) currentSession = null
        }
    }
}
