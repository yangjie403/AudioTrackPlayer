package com.bigjelly.temporun.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlin.math.abs

class AdvancedAudioPlayer {

    companion object {
        private const val TAG = "AdvancedAudioPlayer"
    }

    // 状态与控制标记
    @Volatile
    private var isRunning = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var isLooping = false

    @Volatile
    private var pendingSeekUs: Long = -1L

    private val pauseLock = Object()
    private var workerThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    // 进度回调
    private val mainHandler = Handler(Looper.getMainLooper())
    var onProgressListener: ((currentMs: Long, totalMs: Long) -> Unit)? = null
    var onCompletionListener: (() -> Unit)? = null

    var durationMs: Long = 0L
        private set

    /**
     * 播放 assets 目录音频
     */
    fun playFromAssets(context: Context, assetName: String, loop: Boolean = false) {
        val afd = context.assets.openFd(assetName)
        startInternal(loop) { extractor ->
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
    }

    /**
     * 播放本地文件路径
     */
    fun playFromFilePath(path: String, loop: Boolean = false) {
        startInternal(loop) { extractor ->
            extractor.setDataSource(path)
        }
    }

    /**
     * 跳转到指定进度
     */
    fun seekTo(positionMs: Long) {
        pendingSeekUs = positionMs * 1000L
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    fun setLooping(loop: Boolean) {
        this.isLooping = loop
    }

    fun pause() {
        if (isRunning && !isPaused) {
            isPaused = true
            audioTrack?.pause()
        }
    }

    fun resume() {
        if (isRunning && isPaused) {
            isPaused = false
            audioTrack?.play()
            synchronized(pauseLock) {
                pauseLock.notifyAll()
            }
        }
    }

    fun stop() {
        isRunning = false
        isPaused = false
        pendingSeekUs = -1L
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
        workerThread?.interrupt()
        workerThread = null
    }

    private fun startInternal(loop: Boolean, dataSourceSetter: (MediaExtractor) -> Unit) {
        stop()
        this.isLooping = loop
        this.isRunning = true
        this.isPaused = false
        this.pendingSeekUs = -1L

        workerThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null

            try {
                dataSourceSetter(extractor)
                // 获取音轨信息与总时长
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
                    return@Thread
                }
                extractor.selectTrack(audioTrackIndex)

                // 获取时长
                val durationUs =
                    if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                        audioFormat.getLong(MediaFormat.KEY_DURATION)
                    } else 0L
                durationMs = durationUs / 1000L

                val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val channelConfig =
                    if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO
                    else AudioFormat.CHANNEL_OUT_STEREO

                // 构建 AudioTrack
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT
                )
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

                // 构建 MediaCodec
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(audioFormat, null, null, 0)
                decoder.start()
                audioTrack?.play()

                val bufferInfo = MediaCodec.BufferInfo()
                val timeoutUs = 10000L
                var isEOS = false
                var lastProgressUpdateMs = 0L

                // 核心逻辑
                while (isRunning) {
                    // 检查是否触发了 Seek
                    if (pendingSeekUs >= 0) {
                        val seekTarget = pendingSeekUs
                        pendingSeekUs = -1L
                        extractor.seekTo(seekTarget, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        // 清理解码器中残留的待输出数据
                        decoder.flush()
                        audioTrack?.pause()
                        // 清空硬件缓冲区中残留的数据
                        audioTrack?.flush()
                        if (!isPaused) {
                            audioTrack?.play()
                        }
                        isEOS = false
                    }

                    // 暂停控制逻辑
                    synchronized(pauseLock) {
                        while (isPaused && isRunning && pendingSeekUs < 0) {
                            pauseLock.wait()
                        }
                    }
                    if (!isRunning) break

                    // 输入端：读取压缩音频送入 MediaCodec
                    if (!isEOS) {
                        val inputIndex = decoder.dequeueInputBuffer(timeoutUs)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    if (isLooping) {
                                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                        decoder.flush()
                                    } else {
                                        decoder.queueInputBuffer(
                                            inputIndex,
                                            0,
                                            0,
                                            0L,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                        isEOS = true
                                    }
                                } else {
                                    decoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        sampleSize,
                                        extractor.sampleTime,
                                        0
                                    )
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    // 输出端：拿到 PCM 数据并写入 AudioTrack
                    val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    if (outputIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            audioTrack?.write(
                                outputBuffer,
                                bufferInfo.size,
                                AudioTrack.WRITE_BLOCKING
                            )

                            // 节流分发播放进度给 UI，大约每 100ms 刷新一次
                            val currentPositionMs = bufferInfo.presentationTimeUs / 1000L
                            if (abs(currentPositionMs - lastProgressUpdateMs) >= 100) {
                                lastProgressUpdateMs = currentPositionMs
                                mainHandler.post {
                                    onProgressListener?.invoke(currentPositionMs, durationMs)
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            mainHandler.post { onCompletionListener?.invoke() }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback Exception", e)
            } finally {
                isRunning = false
                isPaused = false
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                    audioTrack = null
                    decoder?.stop()
                    decoder?.release()
                    extractor.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error while releasing resources", e)
                }
            }
        }.apply { start() }
    }
}