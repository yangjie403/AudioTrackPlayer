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

        // 初始化 MediaCodec 解码器。
        // MediaCodec 采用“输入缓冲区 -> 解码 -> 输出缓冲区”的异步队列式思路：
        // 应用把 MP3 压缩数据放入输入缓冲区，解码器处理后，再从输出缓冲区取出 PCM。
        //
        // createDecoderByType() 根据 MIME 类型查找并创建“解码器”，例如：
        // - audio/mpeg       -> MP3 解码器；
        // - audio/mp4a-latm  -> AAC 解码器。
        // 这里只创建解码器，还没有开始处理数据。
        val decoder = MediaCodec.createDecoderByType(mime)

        // configure() 配置解码器，但此时解码器还没有进入运行状态。
        // 第一个参数 audioFormat 是 MediaExtractor 从媒体文件中读取到的格式，
        // 其中包含 MIME 类型、采样率、声道数等解码所需信息。
        //
        // 第二个参数是输出 Surface。音频解码不需要 Surface，所以传 null；
        // 视频解码到屏幕时才通常会传入 Surface。
        // 第三个参数是 MediaCrypto，用于受保护/加密媒体；普通 MP3 传 null。
        // 第四个参数是配置标志。0 表示按“解码模式”配置；编码器才会使用
        // MediaCodec.CONFIGURE_FLAG_ENCODE。
        decoder.configure(audioFormat, null, null, 0)

        // start() 让解码器从“已配置”状态进入“执行”状态。
        // 只有 start() 成功后，才能调用 dequeueInputBuffer()、getInputBuffer()、
        // queueInputBuffer() 和 dequeueOutputBuffer() 等缓冲区 API。
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
                // 向 MediaCodec 输入一块 MP3 压缩数据。
                //
                // isEOS（End Of Stream，流结束）只表示“已经没有新的压缩数据可以
                // 从 MediaExtractor 读取”，并不表示解码器已经把之前收到的数据全部
                // 解码完成。因此发送 EOS 后仍然要继续读取输出缓冲区，直到输出端也
                // 返回 BUFFER_FLAG_END_OF_STREAM。
                if (!isEOS) {
                    // dequeueInputBuffer() 从 MediaCodec 的输入队列中申请一个可写的
                    // 输入缓冲区，并返回它的索引。
                    //
                    // timeoutUs 的单位是微秒（这里是 10,000 微秒，即 10 毫秒）：
                    // - 返回值 >= 0：成功取得输入缓冲区，返回值就是缓冲区索引；
                    // - 返回 INFO_TRY_AGAIN_LATER（通常为 -1）：在超时时间内没有
                    //   可用缓冲区，本轮跳过输入，稍后继续尝试。
                    val inputBufferIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputBufferIndex >= 0) {
                        // getInputBuffer() 根据索引取得实际的 ByteBuffer，应用需要把
                        // 一块压缩数据写入这个缓冲区。取得后，该缓冲区暂时归应用使用；
                        // 调用 queueInputBuffer() 后，所有权会交还给 MediaCodec，不能再
                        // 继续修改或读取它，直到解码器再次把它提供出来。
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            // readSampleData() 把 MediaExtractor 当前指向的压缩音频样本
                            // 写入 inputBuffer，从第 0 个字节开始写。
                            // 返回值是实际读取的字节数；返回负数表示已经没有更多样本。
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                // 输入数据已经读取完毕，但 MediaCodec 可能还在处理之前
                                // 送入的 MP3 数据，所以要用一个“大小为 0 的特殊输入缓冲区”
                                // 通知它输入流结束。
                                //
                                // queueInputBuffer() 的参数依次是：
                                // 1. inputBufferIndex：要提交的输入缓冲区索引；
                                // 2. offset：有效数据起始偏移；EOS 没有数据，因此为 0；
                                // 3. size：有效数据大小；EOS 缓冲区大小为 0；
                                // 4. presentationTimeUs：时间戳，单位是微秒；EOS 时这里为 0；
                                // 5. flags：标记该缓冲区是 BUFFER_FLAG_END_OF_STREAM。
                                decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEOS = true
                            } else {
                                // 把当前 MP3 样本提交给解码器。
                                // sampleTime 是当前样本的播放时间戳，单位为微秒，传给
                                // MediaCodec 后会成为对应输出 PCM 的时间信息。
                                // flags=0 表示这是一块普通输入数据，没有特殊标记。
                                // 提交后 inputBuffer 的所有权归 MediaCodec。
                                decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)

                                // 只有当前样本已经成功提交给解码器后，才能让 Extractor
                                // 移动到下一个样本。否则可能重复读取同一块 MP3 数据。
                                extractor.advance()
                            }
                        }
                    }
                }

                // 从 MediaCodec 获取解码后的 PCM 数据。
                // 输入端 queueInputBuffer() 和输出端 dequeueOutputBuffer() 是相互
                // 配合的：输入压缩数据后，解码器需要一定时间处理，因此不一定每次
                // 循环都能立即得到输出。
                //
                // BufferInfo 会由 dequeueOutputBuffer() 填充，用于描述当前输出缓冲区
                // 中有效数据的位置、长度、时间戳以及是否带有 EOS 标记。
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputBufferIndex >= 0) {
                    // dequeueOutputBuffer() 返回输出缓冲区索引时，说明有一块解码结果
                    // 可以读取。返回值的常见含义是：
                    // - >= 0：输出缓冲区索引，可以调用 getOutputBuffer()；
                    // - INFO_TRY_AGAIN_LATER（通常为 -1）：暂时没有输出，稍后重试；
                    // - INFO_OUTPUT_FORMAT_CHANGED（通常为 -2）：输出格式发生变化，
                    //   应从 decoder.outputFormat 获取新的 MediaFormat；
                    // - INFO_OUTPUT_BUFFERS_CHANGED（旧 API，通常为 -3）：输出缓冲区
                    //   引用发生变化，旧版代码需要重新获取缓冲区列表；当前代码使用
                    //   getOutputBuffer()，一般不需要单独处理这个状态。
                    val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        // getOutputBuffer() 根据索引取得解码器输出的 ByteBuffer。
                        // 对 MP3 解码器来说，这里的有效内容通常是 PCM 音频数据，而不是
                        // 原始 MP3 数据。只有 bufferInfo.offset 到 offset + size 范围内
                        // 的数据有效。
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

                    // 只有当“输出端”也带有 EOS 标记时，才说明解码器已经把最后一块
                    // 输入数据处理完毕。此时才能退出循环；仅仅 isEOS=true 还不够，
                    // 因为解码器内部可能仍有排队中的数据。
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
                // stop() 让 MediaCodec 停止执行，并清空/结束当前的解码过程。
                // 调用 stop() 后不能继续向它提交或读取数据；如果要再次使用，通常需要
                // 重新 configure()，然后再次 start()。
                decoder.stop()
                // release() 释放 MediaCodec 占用的编解码器资源。释放后 decoder 不可再用。
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
