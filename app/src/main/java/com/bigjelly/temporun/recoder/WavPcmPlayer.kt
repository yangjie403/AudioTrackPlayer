package com.bigjelly.temporun.recoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong


/**
 * 读取 PCM WAV 文件，并使用 [AudioTrack] 将原始 PCM 数据送入扬声器。
 *
 * 播放流程与录音流程相反：先解析 WAV 文件头得到音频格式，再按照相同格式创建
 * AudioTrack，最后跳过文件头，分块读取 data chunk 并写入 AudioTrack。
 */
class WavPcmPlayer {

    /** 播放 WAV 所需的关键元数据；dataOffset 指向第一个 PCM 字节。 */
    private data class WavHeader(
        /** 采样率，例如 44,100 Hz。 */
        val sampleRate: Int,
        /** 声道数。 */
        val channelCount: Int,
        /** 每个样本的位数。 */
        val bitsPerSample: Int,
        /** data chunk 内容在文件中的起始位置。 */
        val dataOffset: Long,
        /** PCM 数据长度，单位为字节。 */
        val dataSize: Long
    )

    /** 每次播放递增；旧播放线程发现编号过期后就会停止继续写入。 */
    private val generation = AtomicLong(0L)

    /** 当前播放使用的 AudioTrack，供 stop() 停止。 */
    @Volatile
    private var audioTrack: AudioTrack? = null

    fun play(file: File, onFinished: () -> Unit = {}, onError: (Throwable) -> Unit = {}): Boolean {
        // 这里先做轻量检查；真正打开和解析文件在后台线程中进行。
        if (!file.isFile) return false

        // 开始新的播放前，先使旧会话失效。
        stop()
        val sessionId = generation.incrementAndGet()
        // 文件读取和 AudioTrack.write() 不能阻塞 UI 线程。
        Thread({
            playInternal(
                file = file,
                sessionId = sessionId,
                onFinished = onFinished,
                onError = onError
            )
        }, "wav-pcm-player").start()
        return true
    }

    fun stop() {
        // 递增 generation 会让播放循环在下一次检查时退出。
        generation.incrementAndGet()
        try {
            // stop() 可能在 AudioTrack 尚未创建或已经释放后调用，因此忽略这里的异常。
            audioTrack?.stop()
        } catch (_: Throwable) {
        }
    }

    private fun playInternal(
        file: File,
        sessionId: Long,
        onFinished: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        var track: AudioTrack? = null
        try {
            RandomAccessFile(file, "r").use { input ->
                // WAV 是容器格式，先读取头部才能知道后续 PCM 数据该如何播放。
                val header = readWavHeader(input)
                require(header.bitsPerSample == 16) { "Only PCM 16-bit WAV are supported" }
                require(header.channelCount == 1 || header.channelCount == 2) {
                    "Only mono and stereo WAV are supported"
                }
                val outputChannelMask = if (header.channelCount == 1) {
                    AudioFormat.CHANNEL_OUT_MONO
                } else {
                    AudioFormat.CHANNEL_OUT_STEREO
                }
                val minBufferSize = AudioTrack.getMinBufferSize(
                    header.sampleRate,
                    outputChannelMask,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                require(minBufferSize > 0) { "AudioTrack.getMinBufferSize failed" }
                // AudioTrack 的格式必须和录音时写入 WAV 的 PCM 格式一致：采样率、声道数、位深。
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(header.sampleRate)
                            .setChannelMask(outputChannelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBufferSize, 4096))
                    .build()
                check(track.state == AudioTrack.STATE_INITIALIZED) {
                    "AudioTrack initialization failed"
                }

                if (generation.get() != sessionId) return

                audioTrack = track
                // play() 只启动播放；真正的 PCM 数据通过后面的 write() 送入 AudioTrack。
                track.play()
                // 跳过 RIFF/WAVE/fmt 等头部，定位到 data chunk 的第一个 PCM 字节。
                input.seek(header.dataOffset)
                val buffer = ByteArray(maxOf(4096, minBufferSize))
                var remaining = header.dataSize
                while (remaining > 0L && generation.get() == sessionId) {
                    // 分块读取，避免一次性把整个音频文件加载到内存。
                    val requestSize = minOf(buffer.size.toLong(), remaining).toInt()
                    val readCount = input.read(buffer, 0, requestSize)
                    if (readCount <= 0) break
                    // write() 返回实际写入 AudioTrack 的字节数，remaining 必须按它扣减。
                    val written = track.write(buffer, 0, readCount)
                    check(written > 0) { "AudioTrack.write failed: $written" }
                    remaining -= written.toLong()
                }
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                if (remaining == 0L && generation.get() == sessionId) {
                    // 播放完成回调发生在后台线程；若要更新界面，应切回主线程。
                    onFinished()
                }
            }
        } catch (error: Throwable) {
            // 被 stop() 取消的旧会话不再向外报告错误。
            if (generation.get() == sessionId) onError(error)
        } finally {
            // AudioTrack 是系统资源，播放结束、取消或出错时都必须释放。
            try {
                track?.release()
            } catch (_: Throwable) {
            }
            if (audioTrack == track) audioTrack = null
        }
    }

    private fun readWavHeader(input: RandomAccessFile): WavHeader {
        // 标准 PCM WAV 通常以 RIFF + WAVE 开头；后续内容由多个 chunk 组成。
        require(readFourCc(input) == "RIFF") { "Not a RIFF file" }
        readUInt32LittleEndian(input)
        require(readFourCc(input) == "WAVE") { "Not a WAVE file" }

        var audioFormat = -1
        var channelCount = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var dataOffset = -1L
        var dataSize = -1L
        while (input.filePointer + 8L <= input.length()) {
            // 每个 chunk 都由 4 字节 ID、4 字节长度和对应的数据组成。
            val chunkId = readFourCc(input)
            val chunkSize = readUInt32LittleEndian(input)
            val chunkDataStart = input.filePointer
            require(chunkDataStart + chunkSize <= input.length()) {
                "WAV chunk exceeds file length"
            }
            when (chunkId) {
                "fmt " -> {
                    // fmt chunk 描述编码格式；PCM 的 audioFormat 为 1。
                    require(chunkSize >= 16L) { "Invalid fmt chunk" }
                    audioFormat = readUInt16LittleEndian(input)
                    channelCount = readUInt16LittleEndian(input)
                    sampleRate = readUInt32LittleEndian(input).toInt()
                    input.skipBytes(6)
                    bitsPerSample = readUInt16LittleEndian(input)
                }

                "data" -> {
                    // data chunk 才是实际要交给 AudioTrack 的 PCM 字节流。
                    dataOffset = chunkDataStart
                    dataSize = chunkSize
                    break
                }
            }
            // chunk 数据若为奇数字节，RIFF 要求补一个对齐字节，因此跳过 padding。
            input.seek(chunkDataStart + chunkSize + (chunkSize and 1L))
        }

        require(audioFormat == 1) { "Only uncompressed PCM WAV is supported" }
        require(channelCount > 0 && sampleRate > 0 && bitsPerSample > 0) {
            "WAV fmt chunk is missing or invalid"
        }
        require(dataOffset >= 0L && dataSize >= 0L) { "WAV data chunk is missing" }

        return WavHeader(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            dataOffset = dataOffset,
            dataSize = dataSize
        )
    }

    private fun readFourCc(input: RandomAccessFile): String {
        // FourCC 是 WAV 用来标识 chunk 类型的 4 字节 ASCII 字符串。
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readUInt16LittleEndian(input: RandomAccessFile): Int {
        // 读取 WAV 头中的 16 位 little-endian 无符号整数。
        val low = input.readUnsignedByte()
        val high = input.readUnsignedByte()
        return (high shl 8) or low
    }

    private fun readUInt32LittleEndian(input: RandomAccessFile): Long {
        // 读取 WAV 头中的 32 位 little-endian 无符号整数；用 Long 保存完整范围。
        val b0 = input.readUnsignedByte().toLong()
        val b1 = input.readUnsignedByte().toLong()
        val b2 = input.readUnsignedByte().toLong()
        val b3 = input.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
