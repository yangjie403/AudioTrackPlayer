package com.bigjelly.temporun.player

import org.json.JSONObject
/** C++ JNI bridge backed by the copied FFmpeg static archives. */
object FfmpegBridge {

    init {
        System.loadLibrary("ffmpeg_jni")
    }

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

    fun version(): String = nativeVersion()

    fun configuration(): String = nativeConfiguration()

    /** Converts an audio file at [inputPath] to [outputPath]. */
    fun convert(inputPath: String, outputPath: String, targetFormat: String) {
        require(inputPath.isNotBlank()) { "inputPath must not be blank" }
        require(outputPath.isNotBlank()) { "outputPath must not be blank" }
        nativeConvert(inputPath, outputPath, targetFormat)
    }

    /** Returns normalized peak values suitable for rendering a compact waveform. */
    fun waveform(path: String, pointCount: Int = 160): FloatArray {
        require(path.isNotBlank()) { "path must not be blank" }
        require(pointCount > 0) { "pointCount must be greater than zero" }
        return nativeWaveform(path, pointCount)
    }

    /** Renders the selected interval to an MP3 file. */
    fun trim(inputPath: String, outputPath: String, startMs: Long, endMs: Long) {
        require(endMs > startMs) { "endMs must be greater than startMs" }
        nativeTrim(inputPath, outputPath, startMs, endMs)
    }

    /** Renders a selected interval with independent speed and pitch controls. */
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

    /** Concatenates two audio files and writes a normalized MP3 output. */
    fun concat(firstInputPath: String, secondInputPath: String, outputPath: String) {
        nativeConcat(firstInputPath, secondInputPath, outputPath)
    }

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
