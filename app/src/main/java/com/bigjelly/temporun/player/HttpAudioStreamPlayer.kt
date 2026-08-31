package com.bigjelly.temporun.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer

/**
 * 一个基于 Android 原生 API 的网络音频播放器。
 *
 * 播放链路如下：
 *
 *     HTTP 音频地址
 *           ↓
 *     MediaExtractor：读取网络媒体、识别音频轨道、切分压缩 sample
 *           ↓
 *     MediaCodec：把 MP3/AAC 等压缩数据解码成 PCM 原始采样数据
 *           ↓
 *     AudioTrack：把 PCM 写入 Android 音频输出设备
 *           ↓
 *     扬声器/耳机
 *
 * 这里没有直接用 InputStream.read() 把 HTTP 原始字节交给 MediaCodec。
 * 原始网络响应可能包含 ID3 标签、流媒体元数据或不完整的帧，直接送入解码器
 * 容易导致 “Codec reported err” 或 “Pending dequeue output buffer request
 * cancelled”。MediaExtractor 会先解析媒体格式，再输出解码器可以识别的 sample。
 */
class HttpAudioStreamPlayer {

    companion object {
        /** 日志标签，便于在 Logcat 中筛选本播放器的日志。 */
        private const val TAG = "HttpAudioStreamPlayer"

        /**
         * MediaCodec 缓冲区 API 的等待时间，单位是微秒。
         * 10,000 微秒就是 10 毫秒；超时后返回 INFO_TRY_AGAIN_LATER，循环继续执行。
         */
        private const val TIMEOUT_US = 10_000L
    }

    /**
     * 保护 currentSession 的锁。
     *
     * start()/stop() 可能由 UI 线程调用，而真正的解码和播放在后台线程执行，
     * 因此替换当前会话时必须保证线程安全。
     */
    private val stateLock = Any()

    /** 当前正在播放的会话；同一时刻最多保留一个“当前”会话。 */
    private var currentSession: PlaybackSession? = null

    /**
     * 一次播放任务的状态对象。
     *
     * 每次 start() 都会创建新的会话，旧会话不能复用。这样快速连续点击播放时，
     * 新旧播放线程的停止标志和资源不会相互覆盖。
     */
    private class PlaybackSession {
        /**
         * 播放线程的运行标志。
         * @Volatile 保证 stop() 修改后，后台线程可以及时看到最新值。
         */
        @Volatile
        var running = true

        /** 该会话唯一拥有的播放线程。 */
        var thread: Thread? = null

        /**
         * 请求结束播放。
         *
         * running 负责让循环正常退出，interrupt() 用来唤醒可能正在等待的
         * MediaCodec/MediaExtractor 调用。真正的 MediaCodec、AudioTrack 和
         * MediaExtractor 释放仍然由播放线程在 finally 中完成。
         */
        fun requestStop() {
            running = false
            thread?.interrupt()
        }
    }

    /**
     * 开始播放网络音频。
     *
     * 这个方法只负责管理播放会话和启动线程，不在调用线程中执行网络访问、解码
     * 或 AudioTrack.write()，避免阻塞 UI 线程。
     *
     * @param audioUrl 实际的音频文件地址或设备支持的网络音频流地址。
     */
    fun start(audioUrl: String) {
        // 开始新地址前先停止旧会话，避免多个 AudioTrack 同时向扬声器写数据。
        stop()

        val session = PlaybackSession()
        session.thread = Thread(
            { playSession(session, audioUrl) },
            "HttpAudioStreamPlayer"
        )

        // 先把会话登记为 currentSession，再启动线程。
        // 这样如果启动后 UI 立即调用 stop()，stop() 也能找到这个会话。
        synchronized(stateLock) {
            currentSession = session
        }
        session.thread?.start()
    }

    /**
     * 停止当前播放。
     *
     * stop() 不直接跨线程操作 MediaCodec 或 AudioTrack，因为这些对象由播放线程
     * 创建并使用。这里仅设置停止标志、发送中断，然后等待播放线程进行清理。
     */
    fun stop() {
        // 先从 currentSession 移除旧会话，防止它的 finally 误认为自己仍是当前会话。
        val session = synchronized(stateLock) {
            currentSession.also { currentSession = null }
        } ?: return

        session.requestStop()
        if (Thread.currentThread() !== session.thread) {
            try {
                // 等待一小段时间，让播放线程退出并释放底层资源。
                // 不能在播放线程自身调用 join()，否则会造成线程等待自己而死锁。
                session.thread?.join(1_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun playSession(session: PlaybackSession, audioUrl: String) {
        // 以下对象全部在播放线程中创建、使用和释放，避免跨线程访问底层媒体对象。
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var decoderStarted = false
        var decoderFailed = false
        var audioTrack: AudioTrack? = null

        try {
            // 不直接读取 HttpURLConnection 的原始字节。
            //
            // MediaExtractor 会负责：
            // 1. 访问 HTTP/HTTPS 数据源；
            // 2. 识别文件或流中的真实音频轨道；
            // 3. 处理常见的媒体头和 ID3 信息；
            // 4. 按解码器可识别的完整压缩 sample 提供数据。
            // 使用 URI 重载，明确告诉 MediaExtractor 这是网络数据源。
            // 该重载也允许后续添加 User-Agent、鉴权等请求头。
            extractor.setDataSource(audioUrl, emptyMap())

            // 一个媒体文件可能包含视频、音频、字幕等多个轨道。
            // 本播放器只选择第一个 audio/* 轨道。
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            var mime = ""
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val candidateMime = candidate.getString(MediaFormat.KEY_MIME) ?: ""
                if (candidateMime.startsWith("audio/")) {
                    audioTrackIndex = index
                    inputFormat = candidate
                    mime = candidateMime
                    break
                }
            }
            check(audioTrackIndex >= 0 && inputFormat != null) {
                "No audio track found in network source"
            }

            // selectTrack() 之后，readSampleData()/sampleTime()/advance() 才会围绕
            // 这个音频轨道工作。
            extractor.selectTrack(audioTrackIndex)
            val format = requireNotNull(inputFormat)

            // format 描述的是“压缩音频输入”，例如 audio/mpeg，而不是最终写入
            // AudioTrack 的 PCM 格式。MediaCodec 会根据这个格式选择合适的解码器。
            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            decoderStarted = true
            val codec = requireNotNull(decoder)
            val bufferInfo = MediaCodec.BufferInfo()

            // 输入 EOS 和输出 EOS 是两个不同阶段：
            // - inputEosSent=true：Extractor 已经没有更多压缩 sample；
            // - BUFFER_FLAG_END_OF_STREAM：Codec 已经把之前收到的 sample 全部解码完。
            // 收到输入 EOS 后仍必须继续 dequeueOutputBuffer，不能立即结束播放。
            var inputEosSent = false

            // 主循环不断执行：
            //   取一个 Codec 输入缓冲区 → 放入压缩 sample → 取解码后的 PCM → 写入 AudioTrack
            // 输入端和输出端是异步流水线，所以某一轮没有输出并不代表播放结束。
            while (session.running && !Thread.currentThread().isInterrupted) {
                if (!inputEosSent) {
                    // dequeueInputBuffer() 返回可写入压缩数据的缓冲区索引。
                    // 返回负数表示本轮暂时没有空闲输入缓冲区，稍后继续尝试。
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (!session.running) break

                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            // 极少数设备可能暂时取不到 ByteBuffer；必须归还这个索引，
                            // 否则 Codec 会一直等待一个永远不会被提交的输入缓冲区。
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                        } else {
                            // readSampleData() 把当前压缩 sample 写入 Codec 输入缓冲区，
                            // 返回写入的字节数；它不是直接读取任意网络字节，而是由
                            // MediaExtractor 按媒体格式切分好的数据。
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                // 没有更多压缩数据：向 Codec 提交一个大小为 0 的 EOS
                                // 缓冲区。Codec 还需要时间处理已经排队的最后几帧。
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEosSent = true
                            } else {
                                // sampleTime 是压缩 sample 的媒体时间戳，单位为微秒。
                                // 将它传给 Codec 有助于保持正确的音频时间顺序。
                                val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    sampleTimeUs,
                                    0
                                )

                                // 当前 sample 已经交给 Codec，Extractor 移动到下一个 sample。
                                extractor.advance()
                            }
                        }
                    }
                }

                if (!session.running) break

                // dequeueOutputBuffer() 返回值含义：
                // - INFO_OUTPUT_FORMAT_CHANGED：Codec 确定/改变了实际 PCM 格式；
                // - >= 0：取得一个包含 PCM 的输出缓冲区；
                // - INFO_TRY_AGAIN_LATER（通常为 -1）：本轮还没有输出。
                val outputIndex = try {
                    codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                } catch (e: IllegalStateException) {
                    decoderFailed = true
                    throw e
                }

                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 不能继续使用写死的 44100Hz/双声道。
                        // 这里读取解码器最终确认的采样率、声道数和 PCM 编码，
                        // AudioTrack 必须使用与实际 PCM 一致的参数。
                        val outputFormat = codec.outputFormat
                        val sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }

                        check(encoding == AudioFormat.ENCODING_PCM_16BIT) {
                            "Unsupported decoder PCM encoding: $encoding"
                        }
                        val channelConfig = when (channelCount) {
                            1 -> AudioFormat.CHANNEL_OUT_MONO
                            2 -> AudioFormat.CHANNEL_OUT_STEREO
                            else -> error("Unsupported output channel count: $channelCount")
                        }

                        // AudioTrack 的缓冲区大小单位是字节，使用系统建议的最小值的
                        // 两倍，给网络抖动和解码速度波动留出一定缓冲空间。
                        val minBufferSize = AudioTrack.getMinBufferSize(
                            sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        check(minBufferSize > 0) {
                            "Invalid AudioTrack buffer size: $minBufferSize"
                        }

                        // 如果 Codec 在播放过程中再次改变输出格式，释放旧 AudioTrack，
                        // 按新 PCM 格式重建。AudioTrack 创建后不能随意改变采样率/声道布局。
                        audioTrack?.release()
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
                        check(audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                            "AudioTrack initialization failed"
                        }

                        // play() 只是让 AudioTrack 进入接收/播放状态；MODE_STREAM 下不会
                        // 自动产生声音，后面仍必须不断调用 write() 写入 PCM 数据。
                        audioTrack.play()

                        Log.d(
                            TAG,
                            "Format parsed -> SampleRate: $sampleRate, " +
                                "Channels: $channelCount, Encoding: $encoding"
                        )
                    }

                    outputIndex >= 0 -> {
                        val outputBuffer: ByteBuffer? = codec.getOutputBuffer(outputIndex)
                        val outputFlags = bufferInfo.flags
                        try {
                            if (
                                session.running &&
                                outputBuffer != null &&
                                bufferInfo.size > 0
                            ) {
                                // BufferInfo.offset/size 指出本次输出在 ByteBuffer 中的有效区间。
                                // 只能把这个区间写入 AudioTrack，不能把整个 ByteBuffer 当成 PCM。
                                val start = bufferInfo.offset
                                val end = start + bufferInfo.size
                                check(start >= 0 && end <= outputBuffer.capacity()) {
                                    "Invalid decoder output range: $start..$end"
                                }
                                outputBuffer.position(start)
                                outputBuffer.limit(end)

                                // MediaCodec 输出的是 PCM 原始采样数据，AudioTrack 不负责
                                // MP3 解码；因此这里的 write() 是真正把声音送入系统音频链路。
                                val written = audioTrack?.write(
                                    outputBuffer,
                                    bufferInfo.size,
                                    AudioTrack.WRITE_BLOCKING
                                ) ?: -1
                                if (written < 0) {
                                    throw IOException("AudioTrack.write failed: $written")
                                }
                            }
                        } finally {
                            // 无论 write() 成功还是抛异常，都必须归还 Codec 输出缓冲区，
                            // 否则后续解码可能因输出缓冲区耗尽而停住。
                            codec.releaseOutputBuffer(outputIndex, false)
                        }

                        // 只有输出端收到 EOS，才表示最后一段 PCM 已经从 Codec 排出。
                        if (outputFlags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "Decode & playback complete")
                            break
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (session.running) Log.e(TAG, "Streaming playback interrupted", e)
        } catch (e: Exception) {
            if (session.running) {
                Log.e(TAG, "Streaming playback error", e)
            } else {
                Log.d(TAG, "Streaming playback stopped")
            }
        } finally {
            // 资源释放顺序：AudioTrack → MediaCodec → MediaExtractor。
            // 每个对象都单独保护，避免某一个释放异常阻止后续资源清理。
            try {
                audioTrack?.let { track ->
                    try {
                        track.stop()
                    } catch (_: IllegalStateException) {
                        // AudioTrack 可能尚未进入播放状态。
                    }
                    track.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioTrack", e)
            }

            try {
                decoder?.let { codec ->
                    if (decoderStarted && !decoderFailed) {
                        try {
                            // 正常情况下先 stop 再 release；如果 Codec 已因底层错误自动
                            // 进入非运行状态，则 stop 可能抛 IllegalStateException，此时
                            // 直接 release 即可。
                            codec.stop()
                        } catch (_: IllegalStateException) {
                            // Codec 已因底层错误自动停止时，直接 release 即可。
                        }
                    }
                    codec.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaCodec", e)
            }

            try {
                // Extractor 可能持有网络连接和内部缓冲区，必须在会话结束时释放。
                extractor.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaExtractor", e)
            }

            // 标记会话彻底结束，并且只清除仍然指向自己的 currentSession。
            // 旧会话退出时不能清除已经由 start() 设置的新会话。
            session.running = false
            synchronized(stateLock) {
                if (currentSession === session) {
                    currentSession = null
                }
            }
        }
    }
}
