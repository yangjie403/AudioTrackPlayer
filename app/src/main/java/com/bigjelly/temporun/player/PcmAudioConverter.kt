package com.bigjelly.temporun.player

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 一段 PCM 音频的描述信息。
 *
 * 这里的三个字段共同决定了 ByteBuffer 中字节的解释方式：
 *
 * - [sampleRate]：每秒包含多少个音频帧，例如 44_100 表示 44.1 kHz；
 * - [channelCount]：每个音频帧包含多少个声道采样值，例如立体声为 2；
 * - [encoding]：每个采样值使用什么 PCM 编码，以及占用多少字节。
 *
 * 注意“音频帧（frame）”和“采样值（sample）”不是一回事：
 * 立体声的一帧包含左、右两个采样值，所以 2 声道 PCM 中：
 * `sampleCount = frameCount * channelCount`。
 */
internal data class PcmFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int
)

/**
 * 将 MediaCodec 输出的 PCM 转换成 AudioTrack 固定使用的 PCM 格式。
 *
 * 整体数据流如下：
 *
 * `Codec ByteBuffer`
 * `    -> 按输入 encoding 读取字节`
 * `    -> Float 采样值（[-1, 1]）`
 * `    -> 声道数转换`
 * `    -> sourceFrames 缓冲`
 * `    -> 线性插值重采样`
 * `    -> PCM 16-bit ByteBuffer`
 * `    -> AudioTrack.write()`
 *
 * 内部统一使用 `Float` 表示采样值，是为了让不同位深的 PCM 可以经过同一套
 * 声道混合和重采样逻辑。[-1, 1] 只是内部的归一化范围，最终输出时仍会重新量化
 * 为目标 AudioTrack 使用的 PCM 16-bit。
 *
 * 转换顺序：
 * 1. 将输入 PCM 解码为 [-1, 1] 范围的 Float 采样值；
 * 2. 将输入声道转换成目标声道数；
 * 3. 使用线性插值进行采样率转换；
 * 4. 将 Float 采样值编码成目标 PCM 16-bit。
 *
 * [sourceFrames] 和 [sourcePosition] 会跨多个 MediaCodec 输出缓冲区保留，避免在
 * 缓冲区边界处重采样时丢失采样点，减少杂音和断裂。这个类本身不负责线程同步，
 * 预期由调用方在同一个播放/解码线程中顺序调用。
 */
internal class PcmAudioConverter(
    private val outputFormat: PcmFormat
) {
    /**
     * MediaCodec 当前输出缓冲区所对应的格式。
     *
     * Codec 可能在输出格式变化时给出新的采样率、声道数或 PCM 编码；AudioTrack
     * 却需要在创建时确定格式，因此这里的输入格式可以变化，而 [outputFormat]
     * 在一个 AudioTrack 生命周期内保持不变。
     */
    private var inputFormat: PcmFormat? = null

    /**
     * 已经完成解码和声道转换、但还没有完成重采样的源帧，按帧交错存储。
     *
     * 例如目标为双声道时，数组排列为：
     * `[frame0-left, frame0-right, frame1-left, frame1-right, ...]`。
     * 数组的实际有效部分是前 `sourceFrameCount * outputFormat.channelCount` 个元素，
     * 后面的容量只是为了后续追加数据而预留。
     */
    private var sourceFrames = FloatArray(0)

    /** [sourceFrames] 前面实际包含的完整音频帧数量。 */
    private var sourceFrameCount = 0

    /**
     * 下一次输出采样在 [sourceFrames] 中对应的源帧位置，允许带小数。
     *
     * 例如 `sourcePosition = 10.25` 表示：下一输出采样位于第 10 帧和第 11 帧
     * 之间，距离第 10 帧的比例为 25%。整数部分用于找到左邻点，小数部分用于
     * 线性插值。每输出一个目标帧，就增加 `inputRate / outputRate`，因此它既
     * 表示当前位置，也编码了输入和输出采样率之间的速度比例。
     */
    private var sourcePosition = 0.0

    /**
     * 更新 MediaCodec 当前输出的 PCM 格式。
     *
     * AudioTrack 的输出格式不会改变；发生输入格式变化时只改变转换比例和声道映射。
     * 为避免把不同采样率的数据混在同一个插值窗口中，格式真正变化时丢弃尚未输出的
     * 极少量尾部缓存。正常的格式变化通常发生在输出边界，这比直接终止播放更平滑。
     */
    fun updateInputFormat(format: PcmFormat) {
        // 这些校验尽早暴露 Codec 元数据问题。否则后续除法、数组索引或 ByteBuffer
        // 读取可能在更远的位置失败，错误信息会更难理解。
        require(format.sampleRate > 0) { "Invalid input sample rate: ${format.sampleRate}" }
        require(format.channelCount > 0) {
            "Invalid input channel count: ${format.channelCount}"
        }
        require(isSupportedEncoding(format.encoding)) {
            "Unsupported input PCM encoding: ${format.encoding}"
        }

        if (inputFormat != null && inputFormat != format) {
            // 重采样状态依赖旧的采样率和声道布局。若把旧格式下缓存的帧继续交给
            // 新格式使用，sourcePosition 的单位就会改变，轻则产生速度/音高错误，
            // 重则出现错误的数组解释。因此格式真正变化时从干净状态重新开始。
            sourceFrames = FloatArray(0)
            sourceFrameCount = 0
            sourcePosition = 0.0
        }
        inputFormat = format
    }

    /**
     * 转换一个 Codec 输出缓冲区。
     *
     * endOfStream=true 时，即使 inputBuffer 为空，也会把转换器内部剩余的采样点输出。
     * 返回的 ByteBuffer 已经 flip，可以直接交给 AudioTrack.write()。
     */
    fun convert(inputBuffer: ByteBuffer?, size: Int, endOfStream: Boolean): ByteBuffer? {
        val format = requireNotNull(inputFormat) { "Input PCM format is not initialized" }
        if (inputBuffer != null && size > 0) {
            // decodeSamples 只读取 inputBuffer 的副本，不改变 Codec 管理的原始 buffer
            // 的 position。size 是本次有效 PCM 字节数，而不是整个 buffer 的容量。
            val inputSamples = decodeSamples(inputBuffer, size, format)
            val inputFrameCount = inputSamples.size / format.channelCount
            if (inputFrameCount > 0) {
                // 后面的 sourceFrames 始终使用“目标声道布局”，这样重采样时只需处理
                // 一个固定的声道数，不需要同时考虑输入和输出两套索引规则。
                val channelConverted = convertChannels(
                    inputSamples,
                    inputFrameCount,
                    format.channelCount,
                    outputFormat.channelCount
                )
                appendSourceFrames(channelConverted, inputFrameCount)
            }
        }

        // 每个目标帧大约需要 inputRate / outputRate 个源帧的位置跨度；这里按比例
        // 估算本次最多会产生多少目标帧，并额外留出两个位置应对 ceil/浮点误差。
        // EOS 和非 EOS 使用同一个容量估算，但两者的 while 条件不同：EOS 可以用
        // 最后一个源帧补齐尾部，非 EOS 必须保留右邻帧供下一次插值使用。
        val outputCapacityFrames = if (endOfStream) {
            ceil((sourceFrameCount - sourcePosition).coerceAtLeast(0.0) *
                outputFormat.sampleRate / format.sampleRate).toInt() + 2
        } else {
            ceil((sourceFrameCount - sourcePosition).coerceAtLeast(0.0) *
                outputFormat.sampleRate / format.sampleRate).toInt() + 2
        }
        if (outputCapacityFrames <= 0) return null

        val outputSamples = FloatArray(outputCapacityFrames * outputFormat.channelCount)
        var outputFrameCount = 0

        // sourcePosition 每前进 1.0 就跨过一个输入帧。若输入是 48 kHz、输出是
        // 44.1 kHz，step = 48_000 / 44_100 ≈ 1.0884，意味着每个输出帧在源数据
        // 上向前移动约 1.0884 帧；反过来升采样时 step < 1，会在相邻源帧之间
        // 生成多个输出位置。
        val step = format.sampleRate.toDouble() / outputFormat.sampleRate

        // 非 EOS 时必须保留 sourcePosition 后面的至少一个采样点，作为下一次插值的
        // 右邻点。EOS 时没有下一个采样点，因此使用最后一个采样点补齐尾部。
        while (if (endOfStream) {
            sourcePosition < sourceFrameCount
        } else {
            sourcePosition + 1.0 < sourceFrameCount
        }) {
            if (outputFrameCount >= outputCapacityFrames) break

            val leftIndex = floor(sourcePosition).toInt().coerceIn(0, sourceFrameCount - 1)
            val rightIndex = (leftIndex + 1).coerceAtMost(sourceFrameCount - 1)
            val fraction = (sourcePosition - leftIndex).toFloat()
            val outputOffset = outputFrameCount * outputFormat.channelCount
            val leftOffset = leftIndex * outputFormat.channelCount
            val rightOffset = rightIndex * outputFormat.channelCount

            for (channel in 0 until outputFormat.channelCount) {
                // 线性插值公式：y = left + (right - left) * fraction。
                // fraction=0 时得到左帧，fraction=1 时接近右帧；这样采样率转换
                // 后的波形会连续变化，而不是简单丢帧或重复整帧。
                val left = sourceFrames[leftOffset + channel]
                val right = sourceFrames[rightOffset + channel]
                outputSamples[outputOffset + channel] = left + (right - left) * fraction
            }
            outputFrameCount++
            sourcePosition += step
        }

        if (outputFrameCount == 0) return null

        // 删除已经被消费的源帧，但保留 sourcePosition 对应的插值起点。
        //
        // 例如消费后 sourcePosition=3.4：前 3 帧已经不可能再被访问，可以移除；
        // 原来的第 3 帧仍是下一次插值的左邻点，所以移位后要把 position 改成 0.4。
        // 这一步既控制缓存增长，也保证跨 Codec 输出缓冲区时波形连续。
        val consumedFrames = floor(sourcePosition).toInt().coerceAtMost(sourceFrameCount)
        if (consumedFrames > 0) {
            val remainingFrames = sourceFrameCount - consumedFrames
            if (remainingFrames > 0) {
                sourceFrames.copyInto(
                    destination = sourceFrames,
                    destinationOffset = 0,
                    startIndex = consumedFrames * outputFormat.channelCount,
                    endIndex = sourceFrameCount * outputFormat.channelCount
                )
            }
            sourceFrameCount = remainingFrames
            sourcePosition -= consumedFrames
        }

        // AudioTrack 固定接收 PCM 16-bit：每个采样值 2 字节，按目标声道交错排列。
        // nativeOrder 与 decodeSamples 保持一致；Android 常见设备使用 little-endian。
        val outputBuffer = ByteBuffer
            .allocateDirect(outputFrameCount * outputFormat.channelCount * 2)
            .order(ByteOrder.nativeOrder())
        for (i in 0 until outputFrameCount * outputFormat.channelCount) {
            // 量化前再次限制范围，避免浮点插值或输入异常导致 Short 溢出。
            // 32768 将 [-1, 1) 映射到大约 [-32768, 32767]，随后再做整数四舍五入。
            val sample = (outputSamples[i].coerceIn(-1f, 1f) * 32768f)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outputBuffer.putShort(sample.toShort())
        }
        outputBuffer.flip()

        if (endOfStream) {
            // EOS 的尾部已经用最后一个源帧完成补齐，当前转换器不再属于这段音频；
            // 清空状态也能避免下一段播放意外复用上一段的残留数据。
            sourceFrames = FloatArray(0)
            sourceFrameCount = 0
            sourcePosition = 0.0
        }
        return outputBuffer
    }

    private fun appendSourceFrames(samples: FloatArray, frameCount: Int) {
        // samples 已经是目标声道布局。按“帧数 × 目标声道数”计算所需元素数量，
        // 而不是按字节数计算，因为此处仍处于 Float 中间表示阶段。
        val requiredSamples = (sourceFrameCount + frameCount) * outputFormat.channelCount
        if (sourceFrames.size < requiredSamples) {
            sourceFrames = sourceFrames.copyOf(requiredSamples)
        }
        samples.copyInto(
            destination = sourceFrames,
            destinationOffset = sourceFrameCount * outputFormat.channelCount,
            endIndex = frameCount * outputFormat.channelCount
        )
        sourceFrameCount += frameCount
    }

    private fun decodeSamples(
        inputBuffer: ByteBuffer,
        size: Int,
        format: PcmFormat
    ): FloatArray {
        // PCM 是“按帧交错”的字节流：一帧包含所有声道的一个采样值。
        // 只有完整帧才可以参与转换；size 末尾若是不完整帧，会被下面的整除操作
        // 安全忽略，避免读取越界或把下一帧的字节错位解释。
        val bytesPerSample = bytesPerSample(format.encoding)
        val bytesPerFrame = bytesPerSample * format.channelCount
        val frameCount = size / bytesPerFrame
        val samples = FloatArray(frameCount * format.channelCount)
        // duplicate() 共享底层数据但拥有独立的 position/limit，因此不会改变调用方
        // 对 Codec buffer 的游标状态。PCM 16/24/32 和 float 的字节顺序按 nativeOrder
        // 读取；24-bit packed 会在 decode24Bit 中显式拼接三个字节。
        val buffer = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val endPosition = (buffer.position() + frameCount * bytesPerFrame).coerceAtMost(buffer.limit())
        buffer.limit(endPosition)

        var index = 0
        repeat(frameCount * format.channelCount) {
            samples[index++] = when (format.encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> {
                    // Android 的 PCM 8-bit 是无符号格式：0 表示最低电平，128 是零点。
                    ((buffer.get().toInt() and 0xff) - 128) / 128f
                }
                AudioFormat.ENCODING_PCM_16BIT -> {
                    // Short 是有符号 16-bit；-32768 映射到 -1，+32767 接近 +1。
                    buffer.short / 32768f
                }
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    // 理论上 PCM float 已经在 [-1, 1]，但对异常或非标准输入做保护。
                    buffer.float.coerceIn(-1f, 1f)
                }
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    decode24Bit(buffer)
                }
                AudioFormat.ENCODING_PCM_32BIT -> {
                    // Int 的幅度范围是 [-2^31, 2^31-1]，用 2^31 归一化。
                    buffer.int / 2147483648f
                }
                else -> error("Unsupported input PCM encoding: ${format.encoding}")
            }
        }
        return samples
    }

    private fun convertChannels(
        input: FloatArray,
        frameCount: Int,
        inputChannels: Int,
        outputChannels: Int
    ): FloatArray {
        if (inputChannels == outputChannels) return input
        val output = FloatArray(frameCount * outputChannels)
        for (frame in 0 until frameCount) {
            val inputOffset = frame * inputChannels
            val outputOffset = frame * outputChannels
            when {
                inputChannels == 1 -> {
                    // 单声道扩展：把唯一的采样值复制到每个目标声道。
                    for (channel in 0 until outputChannels) {
                        output[outputOffset + channel] = input[inputOffset]
                    }
                }
                outputChannels == 1 -> {
                    // 多声道收缩：对同一帧的所有声道求平均，避免直接相加造成削波。
                    // 这是通用的简单 down-mix，不包含 Dolby/环绕声等专用声道权重。
                    var sum = 0f
                    for (channel in 0 until inputChannels) sum += input[inputOffset + channel]
                    output[outputOffset] = sum / inputChannels
                }
                else -> {
                    // 其他声道数之间没有在此处维护具体的声道语义：按索引复制，
                    // 超出输入范围的目标声道重复输入的最后一个声道。例如 2 -> 4
                    // 会得到 L、R、R、R。常见的单声道/立体声场景不会走这里。
                    for (channel in 0 until outputChannels) {
                        output[outputOffset + channel] =
                            input[inputOffset + channel.coerceAtMost(inputChannels - 1)]
                    }
                }
            }
        }
        return output
    }

    private fun decode24Bit(buffer: ByteBuffer): Float {
        // PCM_24BIT_PACKED 每个采样值正好占 3 个字节，按低字节在前拼成 24-bit
        // 整数。Java/Kotlin 没有原生 24-bit 类型，因此先放入 Int，再手动做符号
        // 扩展：若 bit 23 为 1，就把高 8 位补成 1，得到正确的负数。
        val value = (buffer.get().toInt() and 0xff) or
            ((buffer.get().toInt() and 0xff) shl 8) or
            ((buffer.get().toInt() and 0xff) shl 16)
        val signedValue = if (value and 0x800000 != 0) value or -0x1000000 else value
        // 2^23 是 24-bit 有符号 PCM 的负方向缩放因子。
        return signedValue / 8388608f
    }

    /** 返回一个采样值占用的字节数，不包含声道数。 */
    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT -> 4
        else -> error("Unsupported input PCM encoding: $encoding")
    }

    /**
     * 当前解码器支持的输入编码集合。
     *
     * 输出端不论输入位深如何都会统一写成 PCM 16-bit；这里的支持列表只约束
     * “如何读取输入 ByteBuffer”。
     */
    private fun isSupportedEncoding(encoding: Int): Boolean = encoding == AudioFormat.ENCODING_PCM_8BIT ||
        encoding == AudioFormat.ENCODING_PCM_16BIT ||
        encoding == AudioFormat.ENCODING_PCM_FLOAT ||
        encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED ||
        encoding == AudioFormat.ENCODING_PCM_32BIT
}
