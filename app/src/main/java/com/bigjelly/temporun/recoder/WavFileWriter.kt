package com.bigjelly.temporun.recoder

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 将 PCM 16-bit little-endian 数据写成标准 WAV 文件。
 *
 * WAV 文件可以理解为“文件头 + 原始 PCM 数据”。文件头中包含采样率、声道数、
 * 位深和 PCM 数据长度等信息。录音开始时还不知道最终会有多少音频数据，因此先
 * 写入 44 字节占位头；录音结束时由 [closeAndFinalize] 回到文件开头补写真实长度。
 *
 * 使用方式：
 * 1. 创建对象，构造函数会写入 44 字节占位头；
 * 2. 重复调用 writePcm16；
 * 3. 录音停止后调用 closeAndFinalize() 回填 WAV 头并关闭文件。
 */
class WavFileWriter(
    /** 输出 WAV 文件。 */
    private val file: File,
    /** 每秒的样本数，例如 44,100。 */
    private val sampleRate: Int,
    /** 声道数，例如单声道为 1，立体声为 2。 */
    private val channelCount: Int,
    /** 每个样本的位数；当前实现限定为 16。 */
    private val bitsPerSample: Int = 16
) : Closeable {

    companion object {
        private const val HEADER_SIZE = 44
    }

    private val randomAccessFile: RandomAccessFile
    /** 已经写入的 PCM 数据字节数，用于最终填写 WAV 头。 */
    private var dataSize: Long = 0L
    /** 防止 close 后继续写入或重复关闭。 */
    private var closed = false

    init {
        require(sampleRate > 0) { "sampleRate must be greater than zero" }
        require(channelCount > 0) { "channelCount must be greater than zero" }
        require(bitsPerSample == 16) { "This implementation only supports PCM 16-bit" }
        file.parentFile?.mkdirs()
        randomAccessFile = RandomAccessFile(file, "rw")
        randomAccessFile.setLength(0L)

        // 先占出标准 PCM WAV 头的空间；此时 dataSize 还是未知的。
        randomAccessFile.write(ByteArray(HEADER_SIZE))
    }

    @Synchronized
    fun writePcm16(samples: ShortArray, offset: Int = 0, length: Int = samples.size) {
        // 写入方法可能由录音线程调用；同步可以保护 RandomAccessFile 的当前位置和 dataSize。
        check(!closed) { "WAV writer has already been closed" }
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) {
            "Invalid samples range"
        }

        // Kotlin/Java 的 ShortArray 不是文件需要的字节数组，因此要逐个转换为
        // little-endian 的两个字节。WAV PCM 数据通常使用 little-endian 字节序。
        val bytes = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in offset until offset + length) {
            bytes.putShort(samples[index])
        }
        randomAccessFile.write(bytes.array())
        // 每个 16-bit 样本占 2 字节；length 表示样本数而不是字节数。
        dataSize += length * 2L
    }

    @Synchronized
    fun closeAndFinalize() {
        if (closed) return
        require(dataSize <= 0xffffffffL) { "WAV file is larger than 4 GiB" }

        // blockAlign：一个采样帧（所有声道各一个样本）占用的字节数。
        val bytesPerSample = bitsPerSample / 8
        val blockAlign = channelCount * bytesPerSample
        // byteRate：播放设备每秒需要读取的字节数。
        val byteRate = sampleRate * blockAlign

        // 回到文件开头，按 RIFF/WAVE PCM 格式写入最终文件头。
        randomAccessFile.seek(0L)
        writeAscii("RIFF")
        // RIFF 的 size 不包含前 8 个字节（"RIFF" 和该字段本身）。
        writeUInt32LittleEndian(36L + dataSize)
        writeAscii("WAVE")

        writeAscii("fmt ")
        writeUInt32LittleEndian(16L)
        writeUInt16LittleEndian(1)
        writeUInt16LittleEndian(channelCount)
        writeUInt32LittleEndian(sampleRate.toLong())
        writeUInt32LittleEndian(byteRate.toLong())
        writeUInt16LittleEndian(blockAlign)
        writeUInt16LittleEndian(bitsPerSample)
        writeAscii("data")
        // data chunk 的长度就是后面实际 PCM 数据的字节数。
        writeUInt32LittleEndian(dataSize)
        // 如果文件之前比当前内容更长，截断多余部分，确保文件长度准确。
        randomAccessFile.setLength(HEADER_SIZE + dataSize)
        randomAccessFile.close()
        closed = true
    }

    private fun writeAscii(value: String) {
        // RIFF 标识符使用 ASCII 字符串，例如 "RIFF"、"WAVE" 和 "fmt "。
        randomAccessFile.write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun writeUInt16LittleEndian(value: Int) {
        // WAV 的整数采用 little-endian：低位字节在前，高位字节在后。
        randomAccessFile.write(value and 0xff)
        randomAccessFile.write((value ushr 8) and 0xff)
    }

    private fun writeUInt32LittleEndian(value: Long) {
        // 以无符号 32 位形式写入 WAV 头字段；Long 用来避免 Kotlin 的 Int 溢出问题。
        randomAccessFile.write((value and 0xff).toInt())
        randomAccessFile.write(((value ushr 8) and 0xff).toInt())
        randomAccessFile.write(((value ushr 16) and 0xff).toInt())
        randomAccessFile.write(((value ushr 24) and 0xff).toInt())
    }

    override fun close() {
        closeAndFinalize()
    }
}
