package com.bigjelly.temporun

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log

class Mp3AudioTrackPlayer {

    companion object {
        private const val TAG = "Mp3AudioTrackPlayer"
    }

    // AudioTrack 本身只负责播放“已经解码好的 PCM 数据”，并不会直接播放 MP3。
    // 因此本类的完整流程是：MediaExtractor 读取 MP3 -> MediaCodec 解码 ->
    // AudioTrack 接收 PCM 并播放。
    private var isPlaying = false

    /**
     * 播放 assets 中的音频文件
     */
    fun playFromAssets(context: Context, assetName: String) {
        val afd = context.assets.openFd(assetName)
        startDecodeAndPlay(afd)
    }

    /**
     * 播放本地绝对路径中的 MP3 文件
     */
    fun playFromFilePath(path: String) {
        Thread {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(path)
                decodeAndPlay(extractor)
            } catch (e: Exception) {
                Log.e(TAG, "Error while playing MP3")
            } finally {
                extractor.release()
            }
        }.start()
    }

    private fun startDecodeAndPlay(afd: AssetFileDescriptor) {
        Thread {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                decodeAndPlay(extractor)
            } catch (e: Exception) {
                Log.e(TAG, "Error while playing MP3")
            } finally {
                extractor.release()
                afd.close()
            }
        }.start()
    }

    private fun decodeAndPlay(extractor: MediaExtractor) {
        // 寻找音频轨道并配置 Extractor
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
            return
        }

        extractor.selectTrack(audioTrackIndex)

        // 获取音频基本信息。
        // 这些参数描述了解码器输出的 PCM 数据，创建 AudioTrack 时必须使用与
        // PCM 数据一致的采样率、声道数和编码格式，否则可能出现播放速度错误、
        // 声音失真，甚至完全没有声音。
        val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        // AudioTrack 使用“声道掩码（channel mask）”来表示声道布局，而不是直接
        // 使用声道数量：
        // - CHANNEL_OUT_MONO：单声道，PCM 中每个采样点只有一个声道数据；
        // - CHANNEL_OUT_STEREO：立体声，PCM 中每个采样点通常按 L、R 顺序排列。
        // 这里的示例只处理 1 声道和 2 声道；更多声道应根据实际布局选择对应掩码。
        val channelConfig = if (channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        // AudioTrack.getMinBufferSize(...) 用于查询系统在给定 PCM 参数下建议的
        // 最小缓冲区大小，单位是“字节”。参数必须与后面 AudioFormat 中的参数匹配：
        //
        // - sampleRate：采样率，例如 44_100 或 48_000 Hz；
        // - channelConfig：输出声道掩码；
        // - ENCODING_PCM_16BIT：每个采样值使用 16 位（2 字节）表示。
        //
        // 这个值不是“音频文件大小”，而是 AudioTrack 内部用于缓存待播放 PCM 的
        // 缓冲区容量参考值。缓冲区太小可能导致 write() 来不及供给数据而卡顿，
        // 缓冲区太大则会增加内存占用和播放延迟。
        //
        // 返回值可能是 ERROR_BAD_VALUE 或 ERROR，表示参数不合法或系统无法支持；
        // 生产代码中应检查返回值后再创建 AudioTrack。
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

        // AudioTrack.Builder 用于以 Builder 方式创建 AudioTrack（API 23+）。
        // 这里创建的是“流式（stream）”播放器：PCM 数据解码出来一段，就写入一段，
        // 适合 MP3、AAC 等持续解码的音频；它不是一次性把完整 PCM 音频放入内存。
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // setUsage() 描述音频的使用场景，系统可据此决定音频路由、音量策略、
                    // 音频焦点和与其他音频的交互方式。USAGE_MEDIA 适合音乐、播客等媒体播放。
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    // setContentType() 描述音频内容类型，主要用于系统的音频策略和效果处理。
                    // CONTENT_TYPE_MUSIC 表示音乐类内容；语音、电影等内容应选择相应类型。
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    // build() 将 Builder 中设置的属性对象构建出来，交给 AudioTrack 使用。
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    // setEncoding() 指定每个 PCM 采样值的编码方式。
                    // ENCODING_PCM_16BIT 表示有符号 16 位 PCM，单个采样值占 2 字节。
                    // 它必须与 MediaCodec 实际输出的 PCM 格式一致。
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    // setSampleRate() 指定每秒的采样次数，单位为 Hz。
                    // 必须与解码后 PCM 的采样率一致；设置错误会导致播放速度或音调异常。
                    .setSampleRate(sampleRate)
                    // setChannelMask() 指定 PCM 的输出声道布局，例如单声道或立体声。
                    // 必须与声道数量及 PCM 中各声道数据的排列方式相匹配。
                    .setChannelMask(channelConfig)
                    // build() 构建 AudioFormat。AudioFormat 只描述 PCM 数据格式，
                    // 本身不负责播放，也不保存音频数据。
                    .build()
            )
            // setBufferSizeInBytes() 设置 AudioTrack 内部缓冲区大小，单位为字节。
            // 这里使用系统最小值的 2 倍，为解码线程和播放线程之间提供更多余量。
            // 实际可用大小仍受设备、采样率、声道数和音频输出实现限制。
            .setBufferSizeInBytes(minBufferSize * 2)
            // setTransferMode() 指定应用如何向 AudioTrack 提供数据：
            // - MODE_STREAM：通过 write() 持续写入 PCM，适合本例；
            // - MODE_STATIC：一次性提供一小段完整 PCM，适合短音效，之后可重复播放。
            .setTransferMode(AudioTrack.MODE_STREAM)
            // build() 创建并初始化 AudioTrack 实例。
            // 如果参数不被设备支持，可能抛出 IllegalArgumentException，或者创建出
            // 状态异常的对象；生产代码应检查异常和 audioTrack.state。
            .build()

        // 初始化 MediaCodec 解码器
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(audioFormat, null, null, 0)
        decoder.start()

        // play() 将 AudioTrack 从初始化状态切换到播放状态。
        // 对 MODE_STREAM 来说，调用 play() 后并不会自动产生声音，必须继续调用
        // write() 提供 PCM 数据；write() 写入速度和系统播放速度共同决定实际输出。
        audioTrack.play()
        isPlaying = true
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 10000L // 10ms超时
        var isEOS = false
        try {
            // 循环：提取数据 -> 送入解码器 -> 解码为 PCM -> 喂给 AudioTrack
            while (isPlaying) {
                // 向 MediaCodec 输入压缩数据
                if (!isEOS) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                // 数据读取完毕，解码器已到达末尾 EOS
                                decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEOS = true
                            } else {
                                decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // 从 MediaCodec 获取解码后的 PCM 数据
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        // 将 MediaCodec 输出的 PCM 写入 AudioTrack。
                        //
                        // write(ByteBuffer, sizeInBytes, writeMode) 的含义：
                        // - 第 1 个参数：包含 PCM 数据的 ByteBuffer；这里是解码器输出缓冲区；
                        // - 第 2 个参数：本次最多写入的字节数，这里使用 bufferInfo.size；
                        // - 第 3 个参数：写入模式。
                        //
                        // WRITE_BLOCKING 表示缓冲区空间不足时等待，直到数据被复制到
                        // AudioTrack 内部缓冲区或发生错误。它适合当前这种持续供给数据的
                        // 后台解码线程；WRITE_NON_BLOCKING 则会立即返回，应用需要自行处理
                        // “只写入了部分数据”的情况。
                        //
                        // bufferInfo.offset 指明有效数据在 ByteBuffer 中的起始位置，
                        // bufferInfo.size 指明有效 PCM 数据的长度，因此前面必须先设置
                        // position/limit，避免把无效数据写入 AudioTrack。
                        // write() 的返回值是实际写入的字节数；负数表示错误。
                        // 本示例忽略该返回值，实际项目中应检查它，以便处理写入失败或
                        // 只写入部分数据的情况。
                        audioTrack.write(outputBuffer, bufferInfo.size, AudioTrack.WRITE_BLOCKING)
                    }

                    // releaseOutputBuffer() 告诉 MediaCodec：这个输出缓冲区已经使用完毕，
                    // 可以重新交给解码器循环使用。第二个参数 render=false 表示不让
                    // MediaCodec 直接渲染到 Surface；因为这里的目标是 ByteBuffer 中的
                    // PCM 数据，必须由应用手动 write() 到 AudioTrack。
                    decoder.releaseOutputBuffer(outputBufferIndex, false)

                    // 如果全部解码并播放完毕，退出循环
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d(TAG, "Playback completed")
                        break
                    }
                }
            }
        } finally {
            isPlaying = false
            try {
                // stop() 停止 AudioTrack 的播放。
                // 它会停止向音频设备输出数据，但不会释放 AudioTrack 对象占用的资源；
                // 停止后如果还要复用同一个实例，通常需要重新调用 play()，并根据状态
                // 重新处理待写入的数据。
                audioTrack.stop()
                // release() 释放 AudioTrack 持有的系统音频资源。释放后该实例不能再使用，
                // 必须重新通过 Builder 创建新的 AudioTrack。
                audioTrack.release()
                decoder.stop()
                decoder.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error while releasing resources", e)
            }
        }
    }

    /**
     * 请求停止播放。
     *
     * 这里没有直接调用 AudioTrack.stop()，因为 AudioTrack 是在后台解码线程中创建的，
     * 并且只在那里可安全地完成 stop()/release() 清理。修改标志位后，播放循环会退出，
     * 最终由 finally 统一释放 AudioTrack 和 MediaCodec。
     */
    fun stop() {
        isPlaying = false
    }
}
