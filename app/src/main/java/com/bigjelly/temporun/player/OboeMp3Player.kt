package com.bigjelly.temporun.player

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * 使用 dr_mp3 解码、Oboe 输出的 MP3 播放器。
 *
 * 对外 API 与 [Mp3AudioTrackPlayer] 对齐：
 * - [playFromAssets]
 * - [playFromFilePath]
 * - [stop]
 *
 * MP3 解码完全发生在 native 层：
 *
 *   MP3 文件 -> dr_mp3 -> PCM 16-bit -> Oboe AudioStream
 */
class OboeMp3Player {

    companion object {
        private const val TAG = "OboeMp3Player"
        private const val NATIVE_LIBRARY = "oboe_mp3_player"

        init {
            System.loadLibrary(NATIVE_LIBRARY)
        }
    }

    private class PlaybackSession(val id: Long) {
        @Volatile
        var stopRequested = false

        @Volatile
        var nativeHandle = 0L

        // stop() 和 nativeDestroy() 不能同时操作同一个 native 指针。
        val nativeLock = Any()

        var thread: Thread? = null
    }

    private val sessionLock = Any()
    private val nextSessionId = AtomicLong(0L)

    @Volatile
    private var currentSession: PlaybackSession? = null

    private external fun nativeCreateFromFile(path: String): Long

    /**
     * 从 direct ByteBuffer 创建 decoder。
     * native 层会复制 MP3 数据，因此 ByteBuffer 不需要跨 JNI 调用长期持有。
     */
    private external fun nativeCreateFromMemory(buffer: ByteBuffer, size: Int): Long

    private external fun nativeStart(handle: Long): Int

    /** 在当前线程中执行 dr_mp3 解码并阻塞写入 Oboe，直到结束或停止。 */
    private external fun nativeDecodeAndPlay(handle: Long): Int

    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)

    /** 播放 assets 中的 MP3 文件。 */
    fun playFromAssets(context: Context, assetName: String) {
        val appContext = context.applicationContext
        startPlayback { session ->
            val bytes = appContext.assets.open(assetName).use { it.readBytes() }
            check(!session.stopRequested) { "Playback stopped while reading asset" }

            // JNI 的 GetDirectBufferAddress() 只能访问 direct buffer。
            val directBuffer = ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
            directBuffer.put(bytes).flip()
            nativeCreateFromMemory(directBuffer, bytes.size)
        }
    }

    /** 播放本地绝对路径中的 MP3 文件。文件内容由 dr_mp3 在 native 层读取。 */
    fun playFromFilePath(path: String) {
        startPlayback { session ->
            check(!session.stopRequested) { "Playback stopped before opening file" }
            nativeCreateFromFile(path)
        }
    }

    /** 停止当前播放，并等待 native 解码线程释放 decoder 和 Oboe stream。 */
    fun stop() {
        val session = synchronized(sessionLock) {
            currentSession?.also {
                it.stopRequested = true
                currentSession = null
            }
        } ?: return

        // nativeStop() 会让 Oboe 的 blocking write() 返回，随后 nativeDecodeAndPlay()
        // 能够退出；不能只依赖 Kotlin 的线程 interrupt，因为 native 调用不会响应它。
        requestNativeStop(session)
        session.thread?.interrupt()

        if (Thread.currentThread() !== session.thread) {
            try {
                session.thread?.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Interrupted while waiting for playback thread", e)
            }
        }
    }

    private fun startPlayback(createNativeDecoder: (PlaybackSession) -> Long) {
        stop()

        val session = PlaybackSession(nextSessionId.incrementAndGet())
        val thread = Thread(
            {
                runPlayback(session, createNativeDecoder)
            },
            "OboeMp3Player-${session.id}"
        )
        session.thread = thread

        synchronized(sessionLock) {
            currentSession = session
        }
        thread.start()
    }

    private fun runPlayback(
        session: PlaybackSession,
        createNativeDecoder: (PlaybackSession) -> Long
    ) {
        var nativeHandle = 0L

        try {
            nativeHandle = createNativeDecoder(session)
            check(nativeHandle != 0L) { "Unable to initialize dr_mp3 decoder" }
            session.nativeHandle = nativeHandle
            check(!session.stopRequested) { "Playback stopped during decoder initialization" }

            check(nativeStart(nativeHandle) == 0) {
                "Unable to start Oboe output stream"
            }

            val result = nativeDecodeAndPlay(nativeHandle)
            check(result == 0 || session.stopRequested) {
                "Native dr_mp3 playback failed: $result"
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.d(TAG, "Playback interrupted")
        } catch (e: Exception) {
            if (!session.stopRequested) {
                Log.e(TAG, "Error while playing MP3", e)
            }
        } finally {
            session.stopRequested = true
            if (nativeHandle != 0L) {
                synchronized(session.nativeLock) {
                    val handle = session.nativeHandle
                    if (handle != 0L) {
                        requestNativeStop(session)
                        nativeDestroy(handle)
                        session.nativeHandle = 0L
                    }
                }
            }

            synchronized(sessionLock) {
                if (currentSession === session) {
                    currentSession = null
                }
            }
        }
    }

    private fun requestNativeStop(session: PlaybackSession) {
        synchronized(session.nativeLock) {
            val handle = session.nativeHandle
            if (handle != 0L) {
                try {
                    nativeStop(handle)
                } catch (e: Exception) {
                    Log.w(TAG, "Oboe stop failed", e)
                }
            }
        }
    }
}
