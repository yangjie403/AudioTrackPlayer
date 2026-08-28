package com.bigjelly.temporun.player

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** PCM 数据的采样率、声道数和采样编码。 */
internal data class PcmFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int
)

/**
 * 将 MediaCodec 输出的 PCM 转换成 AudioTrack 固定使用的 PCM 格式。
 *
 * 转换顺序：
 * 1. 将输入 PCM 解码为 [-1, 1] 范围的 Float 采样值；
 * 2. 将输入声道转换成目标声道数；
 * 3. 使用线性插值进行采样率转换；
 * 4. 将 Float 采样值编码成目标 PCM 16-bit。
 *
 * sourceFrames 和 sourcePosition 会跨多个 MediaCodec 输出缓冲区保留，避免在
 * 缓冲区边界处重采样时丢失采样点，减少杂音和断裂。
 */
internal class PcmAudioConverter(
    private val outputFormat: PcmFormat
) {
    private var inputFormat: PcmFormat? = null
    private var sourceFrames = FloatArray(0)
    private var sourceFrameCount = 0
    private var sourcePosition = 0.0

    /**
     * 更新 MediaCodec 当前输出的 PCM 格式。
     *
     * AudioTrack 的输出格式不会改变；发生输入格式变化时只改变转换比例和声道映射。
     * 为避免把不同采样率的数据混在同一个插值窗口中，格式真正变化时丢弃尚未输出的
     * 极少量尾部缓存。正常的格式变化通常发生在输出边界，这比直接终止播放更平滑。
     */
    fun updateInputFormat(format: PcmFormat) {
        require(format.sampleRate > 0) { "Invalid input sample rate: ${format.sampleRate}" }
        require(format.channelCount > 0) {
            "Invalid input channel count: ${format.channelCount}"
        }
        require(isSupportedEncoding(format.encoding)) {
            "Unsupported input PCM encoding: ${format.encoding}"
        }

        if (inputFormat != null && inputFormat != format) {
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
            val inputSamples = decodeSamples(inputBuffer, size, format)
            val inputFrameCount = inputSamples.size / format.channelCount
            if (inputFrameCount > 0) {
                val channelConverted = convertChannels(
                    inputSamples,
                    inputFrameCount,
                    format.channelCount,
                    outputFormat.channelCount
                )
                appendSourceFrames(channelConverted, inputFrameCount)
            }
        }

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
                val left = sourceFrames[leftOffset + channel]
                val right = sourceFrames[rightOffset + channel]
                outputSamples[outputOffset + channel] = left + (right - left) * fraction
            }
            outputFrameCount++
            sourcePosition += step
        }

        if (outputFrameCount == 0) return null

        // 删除已经被消费的源帧，但保留 sourcePosition 对应的插值起点。
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

        val outputBuffer = ByteBuffer
            .allocateDirect(outputFrameCount * outputFormat.channelCount * 2)
            .order(ByteOrder.nativeOrder())
        for (i in 0 until outputFrameCount * outputFormat.channelCount) {
            val sample = (outputSamples[i].coerceIn(-1f, 1f) * 32768f)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outputBuffer.putShort(sample.toShort())
        }
        outputBuffer.flip()

        if (endOfStream) {
            sourceFrames = FloatArray(0)
            sourceFrameCount = 0
            sourcePosition = 0.0
        }
        return outputBuffer
    }

    private fun appendSourceFrames(samples: FloatArray, frameCount: Int) {
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
        val bytesPerSample = bytesPerSample(format.encoding)
        val bytesPerFrame = bytesPerSample * format.channelCount
        val frameCount = size / bytesPerFrame
        val samples = FloatArray(frameCount * format.channelCount)
        val buffer = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val endPosition = (buffer.position() + frameCount * bytesPerFrame).coerceAtMost(buffer.limit())
        buffer.limit(endPosition)

        var index = 0
        repeat(frameCount * format.channelCount) {
            samples[index++] = when (format.encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> {
                    ((buffer.get().toInt() and 0xff) - 128) / 128f
                }
                AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> decode24Bit(buffer)
                AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2147483648f
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
                    for (channel in 0 until outputChannels) {
                        output[outputOffset + channel] = input[inputOffset]
                    }
                }
                outputChannels == 1 -> {
                    var sum = 0f
                    for (channel in 0 until inputChannels) sum += input[inputOffset + channel]
                    output[outputOffset] = sum / inputChannels
                }
                else -> {
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
        val value = (buffer.get().toInt() and 0xff) or
            ((buffer.get().toInt() and 0xff) shl 8) or
            ((buffer.get().toInt() and 0xff) shl 16)
        val signedValue = if (value and 0x800000 != 0) value or -0x1000000 else value
        return signedValue / 8388608f
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT -> 4
        else -> error("Unsupported input PCM encoding: $encoding")
    }

    private fun isSupportedEncoding(encoding: Int): Boolean = encoding == AudioFormat.ENCODING_PCM_8BIT ||
        encoding == AudioFormat.ENCODING_PCM_16BIT ||
        encoding == AudioFormat.ENCODING_PCM_FLOAT ||
        encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED ||
        encoding == AudioFormat.ENCODING_PCM_32BIT
}
