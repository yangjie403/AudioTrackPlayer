package com.bigjelly.temporun.player

import org.json.JSONObject

/**
 * FFmpeg native 层的 Kotlin 入口。
 *
 * 这里的参数全部是普通文件路径，而不是 Android 的 Uri。调用方如果拿到的是
 * content:// Uri，需要先通过 ContentResolver 复制到应用缓存目录，再把缓存文件
 * 的 absolutePath 传给这些方法。具体的解码、滤镜和编码工作都在 ffmpeg_jni.cpp 中完成。
 */
object FfmpegBridge {

    // ffmpeg_jni 是本项目封装 FFmpeg 静态库后的 JNI 动态库。
    init {
        System.loadLibrary("ffmpeg_jni")
    }

    // 下面的 external 方法名称必须与 C++ 中的 JNI 导出函数名称对应。
    private external fun nativeVersion(): String

    private external fun nativeConfiguration(): String

    private external fun nativeProbe(path: String): String

    private external fun nativeConvert(
        inputPath: String,
        outputPath: String,
        targetFormat: String,
    )

    private external fun nativeWaveform(path: String, pointCount: Int): FloatArray

    private external fun nativeTrim(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long,
    )

    private external fun nativeTempoPitch(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long,
        speed: Double,
        pitchSemitones: Double,
    )

    private external fun nativeConcat(
        firstInputPath: String,
        secondInputPath: String,
        outputPath: String,
    )

    /** 返回底层 FFmpeg 的版本字符串，主要用于诊断构建是否正确。 */
    fun version(): String = nativeVersion()

    /** 返回编译 FFmpeg 时使用的配置参数，主要用于诊断编码器和滤镜是否被启用。 */
    fun configuration(): String = nativeConfiguration()

    /**
     * 将输入音频转码为目标格式。
     *
     * [targetFormat] 当前由 native 层支持 mp3、wav、flac、ogg 和 opus。该方法是通用
     * 转码入口，编辑功能的裁剪、变速变调和拼接使用下面更专用的接口。
     */
    fun convert(inputPath: String, outputPath: String, targetFormat: String) {
        require(inputPath.isNotBlank()) { "inputPath must not be blank" }
        require(outputPath.isNotBlank()) { "outputPath must not be blank" }
        nativeConvert(inputPath, outputPath, targetFormat)
    }

    /**
     * 解码音频并返回归一化峰值数组。
     *
     * [pointCount] 是最终数组长度，不是解码帧数量。native 层会先按帧统计峰值，
     * 再把这些峰值压缩到指定数量，返回值通常位于 0～1 之间。
     */
    fun waveform(path: String, pointCount: Int = 160): FloatArray {
        require(path.isNotBlank()) { "path must not be blank" }
        require(pointCount > 0) { "pointCount must be greater than zero" }
        return nativeWaveform(path, pointCount)
    }

    /**
     * 裁剪 [startMs] 到 [endMs] 的音频并输出 MP3。
     *
     * 时间单位是毫秒，且 [endMs] 必须严格大于 [startMs]。当输入是完整 MP3、选区
     * 覆盖整个文件时，native 层会直接复制文件，避免不必要的二次编码。
     */
    fun trim(inputPath: String, outputPath: String, startMs: Long, endMs: Long) {
        require(endMs > startMs) { "endMs must be greater than startMs" }
        nativeTrim(inputPath, outputPath, startMs, endMs)
    }

    /**
     * 裁剪区间并独立调整速度和音调，输出 MP3。
     *
     * [speed] 是速度倍率，1.0 表示原速；[pitchSemitones] 是半音数，0 表示原调，
     * 正数升调，负数降调。native 层通过 asetrate 改变音调，再用 aresample 和
     * atempo 抵消音调变化带来的速度变化，从而保持两个参数相互独立。
     */
    fun tempoPitch(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long,
        speed: Double,
        pitchSemitones: Double,
    ) {
        require(endMs > startMs) { "endMs must be greater than startMs" }
        require(speed > 0.0) { "speed must be greater than zero" }
        nativeTempoPitch(inputPath, outputPath, startMs, endMs, speed, pitchSemitones)
    }

    /**
     * 按先后顺序拼接两段音频并输出 MP3。
     *
     * 两段输入会先分别归零时间戳，再通过 concat 滤镜连接；输出统一为 44100 Hz、
     * stereo、fltp，随后使用 libmp3lame 编码。
     */
    fun concat(firstInputPath: String, secondInputPath: String, outputPath: String) {
        nativeConcat(firstInputPath, secondInputPath, outputPath)
    }

    /**
     * 读取输入文件的容器、音频流和编码参数。
     *
     * 返回值来自 native 层生成的 JSON，包括时长、格式、码率、编码器、采样率、
     * 声道布局、采样格式和流标签等信息。
     */
    fun probe(path: String): FfmpegProbe {
        require(path.isNotBlank()) { "path must not be blank" }

        val json = JSONObject(nativeProbe(path))
        val tagsJson = json.optJSONObject("tags")
        val tags = buildMap {
            if (tagsJson != null) {
                val keys = tagsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, tagsJson.optString(key))
                }
            }
        }

        return FfmpegProbe(
            durationMs = json.optLong("durationMs"),
            format = json.optStringOrNull("format"),
            formatLong = json.optStringOrNull("formatLong"),
            bitRate = json.optLong("bitRate"),
            codec = json.optStringOrNull("codec"),
            codecLong = json.optStringOrNull("codecLong"),
            sampleRate = json.optInt("sampleRate"),
            channels = json.optInt("channels"),
            channelLayout = json.optStringOrNull("channelLayout"),
            sampleFmt = json.optStringOrNull("sampleFmt"),
            streams = json.optInt("streams"),
            tags = tags,
        )
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeUnless { it.isEmpty() }
    }
}

/** native probe 返回的音频元数据。数值为 0 或空字符串表示源文件未提供该字段。 */
data class FfmpegProbe(
    val durationMs: Long,
    val format: String?,
    val formatLong: String?,
    val bitRate: Long,
    val codec: String?,
    val codecLong: String?,
    val sampleRate: Int,
    val channels: Int,
    val channelLayout: String?,
    val sampleFmt: String?,
    val streams: Int,
    val tags: Map<String, String>,
)
