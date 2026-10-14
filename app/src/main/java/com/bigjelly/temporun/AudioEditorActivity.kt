package com.bigjelly.temporun

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bigjelly.temporun.player.AdvancedAudioPlayer
import com.bigjelly.temporun.player.FfmpegBridge
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 音频编辑入口 Activity。
 *
 * 本类负责把 Android 文件选择器得到的 Uri 转成 native 层可以读取的普通文件路径，
 * 再把耗时的 probe、波形提取、滤镜处理和导出任务放到单线程执行器中。真正的音频
 * 解码、滤镜和编码实现位于 FfmpegBridge 及其对应的 ffmpeg_jni.cpp。
 */
class AudioEditorActivity : ComponentActivity() {

    /** 标记下一次选择的音频应该替换主音轨还是第二段拼接音轨。 */
    private enum class ImportTarget { FIRST, SECOND }

    /** 标记待导出的 native 操作类型。 */
    private enum class ExportKind { SEGMENTS, CONCAT }

    /** 页面状态中保存的一段音频；file 是 native 层实际读取的缓存文件。 */
    data class Track(
        val file: File,
        val name: String,
        val durationMs: Long,
        val waveform: List<Float>,
    )

    /** 主音轨中一个保留的音频分段。 */
    data class AudioSegment(
        val startMs: Long,
        val endMs: Long,
    )

    /** SAF 返回保存位置之前暂存的导出请求。 */
    private data class ExportRequest(
        val kind: ExportKind,
        val first: File,
        val second: File? = null,
        val segments: List<AudioSegment> = emptyList(),
        val outputName: String,
    )

    // native 音频处理是同步 JNI 调用，必须放到后台线程，避免阻塞主线程。
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val player = AdvancedAudioPlayer()
    private var importTarget = ImportTarget.FIRST
    private var pendingExport: ExportRequest? = null
    private var previewTempFile: File? = null
    private var firstTempFile: File? = null
    private var secondTempFile: File? = null
    private val firstTrackState = mutableStateOf<Track?>(null)
    private val secondTrackState = mutableStateOf<Track?>(null)
    private val statusState = mutableStateOf("正在加载示例音频…")
    private val busyState = mutableStateOf(true)
    private val playbackPositionState = mutableLongStateOf(0L)
    private val waveformPlayingState = mutableStateOf(false)
    private var waveformPlaybackActive = false
    private var waveformPlaybackSegments: List<AudioSegment> = emptyList()
    private var waveformPlaybackSegmentIndex = -1

    // OpenDocument 返回的是 content:// Uri，回调中会复制到 cacheDir 后再交给 FFmpeg。
    private val openAudio = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) loadSelectedAudio(uri, importTarget)
    }

    // CreateDocument 只负责让用户选择最终保存位置，native 先写缓存文件，再复制到该 Uri。
    private val createOutput = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mpeg")
    ) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri != null && request != null) executeExport(uri, request)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player.onProgressListener = { currentMs, _ ->
            handleWaveformProgress(currentMs)
        }
        player.onStateChangedListener = { state ->
            when (state) {
                AdvancedAudioPlayer.State.PLAYING,
                AdvancedAudioPlayer.State.PREPARING -> {
                    if (waveformPlaybackActive) waveformPlayingState.value = true
                }

                AdvancedAudioPlayer.State.PAUSED,
                AdvancedAudioPlayer.State.STOPPED -> waveformPlayingState.value = false

                AdvancedAudioPlayer.State.COMPLETED,
                AdvancedAudioPlayer.State.ERROR -> {
                    waveformPlaybackActive = false
                    waveformPlayingState.value = false
                }

                AdvancedAudioPlayer.State.IDLE -> Unit
            }
        }
        player.onCompletionListener = {
            waveformPlaybackActive = false
            waveformPlayingState.value = false
            statusState.value = "播放完成"
        }
        setContent {
            MaterialTheme(colorScheme = editorColors()) {
                val firstTrack by firstTrackState
                val secondTrack by secondTrackState
                val status by statusState
                val busy by busyState
                val playbackPositionMs = playbackPositionState.longValue
                val waveformPlaying by waveformPlayingState
                AudioEditorScreen(
                    firstTrack = firstTrack,
                    secondTrack = secondTrack,
                    status = status,
                    busy = busy,
                    playbackPositionMs = playbackPositionMs,
                    waveformPlaying = waveformPlaying,
                    onChooseFirst = {
                        waveformPlaybackActive = false
                        waveformPlayingState.value = false
                        player.stop()
                        importTarget = ImportTarget.FIRST
                        openAudio.launch(arrayOf("audio/*"))
                    },
                    onChooseSecond = {
                        pauseWaveformPlayback()
                        importTarget = ImportTarget.SECOND
                        openAudio.launch(arrayOf("audio/*"))
                    },
                    onPreview = ::previewSelection,
                    onToggleWaveformPlayback = ::toggleWaveformPlayback,
                    onPausePlayback = ::pauseWaveformPlayback,
                    onSeekPlayback = ::seekWaveformPlayback,
                    onExportSegments = ::requestSegmentsExport,
                    onExportConcat = ::requestConcatExport,
                    onStop = {
                        waveformPlaybackActive = false
                        waveformPlayingState.value = false
                        player.stop()
                        statusState.value = "播放已停止"
                    },
                )
            }
        }
        loadAssetExample()
    }

    override fun onStop() {
        waveformPlaybackActive = false
        waveformPlayingState.value = false
        player.stop()
        previewTempFile?.delete()
        previewTempFile = null
        super.onStop()
    }

    override fun onDestroy() {
        waveformPlaybackActive = false
        waveformPlayingState.value = false
        player.onProgressListener = null
        player.onCompletionListener = null
        player.onStateChangedListener = null
        player.stop()
        executor.shutdownNow()
        firstTempFile?.delete()
        secondTempFile?.delete()
        previewTempFile?.delete()
        super.onDestroy()
    }

    private fun loadAssetExample() {
        executor.execute {
            try {
                // 示例资源也复制成普通临时文件，使示例和用户选择的音频走同一套 native 流程。
                val file = File.createTempFile("editor-example-", ".mp3", cacheDir)
                assets.open("music.mp3").use { input -> file.outputStream().use(input::copyTo) }
                val track = readTrack(file, "示例音频 · music.mp3")
                firstTempFile = file
                playbackPositionState.longValue = 0L
                firstTrackState.value = track
                busyState.value = false
                statusState.value = "左右滑动波形调整进度，在当前进度分割音频"
            } catch (error: Throwable) {
                busyState.value = false
                statusState.value = "示例音频加载失败：${error.message ?: "未知错误"}"
            }
        }
    }

    private fun loadSelectedAudio(uri: Uri, target: ImportTarget) {
        busyState.value = true
        statusState.value = "正在读取音频并生成波形…"
        executor.execute {
            var temp: File? = null
            try {
                // JNI 的 avformat_open_input 直接接收文件路径，不能直接打开 Android Uri。
                temp = copyUriToCache(uri)
                val name = displayName(uri) ?: "audio.mp3"
                val track = readTrack(temp, name)
                if (target == ImportTarget.FIRST) {
                    firstTempFile?.delete()
                    firstTempFile = temp
                    playbackPositionState.longValue = 0L
                    firstTrackState.value = track
                } else {
                    secondTempFile?.delete()
                    secondTempFile = temp
                    secondTrackState.value = track
                }
                temp = null
                busyState.value = false
                statusState.value = "已载入 ${track.name}"
            } catch (error: Throwable) {
                temp?.delete()
                busyState.value = false
                statusState.value = "读取失败：${error.message ?: error.javaClass.simpleName}"
                runOnUiThread { toast(statusState.value) }
            }
        }
    }

    private fun readTrack(file: File, name: String): Track {
        // probe 提供时长；waveform 解码整段音频并返回固定数量的归一化峰值。
        val probe = FfmpegBridge.probe(file.absolutePath)
        val waveform = FfmpegBridge.waveform(file.absolutePath, 2000).toList()
        return Track(
            file = file,
            name = name,
            durationMs = probe.durationMs.coerceAtLeast(1L),
            waveform = waveform,
        )
    }

    private fun previewSelection(startMs: Long, endMs: Long, speed: Double, pitch: Double) {
        val track = firstTrackState.value ?: return
        waveformPlaybackActive = false
        waveformPlayingState.value = false
        busyState.value = true
        statusState.value = "正在渲染变速变调预览…"
        player.stop()
        previewTempFile?.delete()
        previewTempFile = null
        executor.execute {
            try {
                // 预览也是一次完整的 native 转码，生成的临时 MP3 交给播放器播放。
                val output = File.createTempFile("editor-preview-", ".mp3", cacheDir)
                FfmpegBridge.tempoPitch(
                    track.file.absolutePath,
                    output.absolutePath,
                    startMs,
                    endMs,
                    speed,
                    pitch,
                )
                previewTempFile = output
                runOnUiThread {
                    busyState.value = false
                    statusState.value = "预览播放中：${formatTime(startMs)} – ${formatTime(endMs)}"
                    player.playFromFilePath(this, output.absolutePath)
                }
            } catch (error: Throwable) {
                busyState.value = false
                statusState.value = "预览失败：${error.message ?: error.javaClass.simpleName}"
                runOnUiThread { toast(statusState.value) }
            }
        }
    }

    private fun toggleWaveformPlayback(segments: List<AudioSegment>, positionMs: Long) {
        if (segments.isEmpty()) return
        if (waveformPlaybackActive && player.state in setOf(
                AdvancedAudioPlayer.State.PLAYING,
                AdvancedAudioPlayer.State.PREPARING,
            )
        ) {
            waveformPlaybackActive = false
            player.pause()
            waveformPlayingState.value = false
            statusState.value = "播放已暂停"
            return
        }

        val track = firstTrackState.value ?: return
        val startMs = findPlaybackStart(positionMs, segments)
        waveformPlaybackSegments = segments
        waveformPlaybackSegmentIndex = segments.indexOfFirst {
            startMs >= it.startMs && startMs < it.endMs
        }.coerceAtLeast(0)
        waveformPlaybackActive = false
        player.playFromFilePath(this, track.file.absolutePath)
        waveformPlaybackActive = true
        waveformPlayingState.value = true
        playbackPositionState.longValue = startMs
        player.seekTo(startMs)
        statusState.value = "正在播放预览"
    }

    private fun pauseWaveformPlayback() {
        if (waveformPlaybackActive && player.state in setOf(
                AdvancedAudioPlayer.State.PLAYING,
                AdvancedAudioPlayer.State.PREPARING,
            )
        ) {
            waveformPlaybackActive = false
            player.pause()
            waveformPlayingState.value = false
            statusState.value = "播放已暂停"
        }
    }

    private fun seekWaveformPlayback(positionMs: Long) {
        if (waveformPlaybackActive) player.seekTo(positionMs)
    }

    private fun findPlaybackStart(positionMs: Long, segments: List<AudioSegment>): Long {
        val first = segments.firstOrNull() ?: return 0L
        val current = segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }
        return current?.let { positionMs.coerceAtLeast(it.startMs) }
            ?: segments.firstOrNull { it.startMs >= positionMs }?.startMs
            ?: first.startMs
    }

    private fun handleWaveformProgress(currentMs: Long) {
        if (!waveformPlaybackActive) return
        playbackPositionState.longValue = currentMs
        val segments = waveformPlaybackSegments
        val currentSegment = segments.getOrNull(waveformPlaybackSegmentIndex) ?: return
        if (currentMs < currentSegment.startMs) {
            // MediaExtractor 只能跳到附近的同步帧，实际回调可能暂时早于目标分段。
            // 重新发起 seek，避免进度线落回已删除的区间。
            playbackPositionState.longValue = currentSegment.startMs
            player.seekTo(currentSegment.startMs)
            return
        }
        if (currentMs >= currentSegment.endMs) {
            val nextIndex = segments.indexOfFirst { it.startMs >= currentSegment.endMs }
            val next = segments.getOrNull(nextIndex)
            if (next != null) {
                waveformPlaybackSegmentIndex = nextIndex
                playbackPositionState.longValue = next.startMs
                player.seekTo(next.startMs)
            } else {
                waveformPlaybackActive = false
                player.pause()
                playbackPositionState.longValue = currentSegment.endMs
                waveformPlayingState.value = false
                statusState.value = "播放到最后一个保留分段"
            }
        }
    }

    private fun requestSegmentsExport(segments: List<AudioSegment>) {
        val track = firstTrackState.value ?: return
        if (segments.isEmpty()) return
        // 先记录参数并打开系统保存器；用户确认路径后才开始执行 native 导出。
        pendingExport = ExportRequest(
            kind = ExportKind.SEGMENTS,
            first = track.file,
            segments = segments,
            outputName = "${track.name.substringBeforeLast('.', "audio")}-edited.mp3",
        )
        createOutput.launch(pendingExport?.outputName ?: "edited.mp3")
    }

    private fun requestConcatExport() {
        val first = firstTrackState.value ?: return
        val second = secondTrackState.value ?: return
        pendingExport = ExportRequest(
            kind = ExportKind.CONCAT,
            first = first.file,
            second = second.file,
            outputName = "${first.name.substringBeforeLast('.', "audio")}-joined.mp3",
        )
        createOutput.launch(pendingExport?.outputName ?: "joined.mp3")
    }

    private fun executeExport(uri: Uri, request: ExportRequest) {
        busyState.value = true
        statusState.value = "正在导出 MP3…"
        executor.execute {
            val output = File.createTempFile("editor-export-", ".mp3", cacheDir)
            try {
                // native 只能稳定地写普通路径，因此先写 cacheDir，再通过 ContentResolver 写入 SAF Uri。
                when (request.kind) {
                    ExportKind.SEGMENTS -> exportSegments(request, output)

                    ExportKind.CONCAT -> FfmpegBridge.concat(
                        request.first.absolutePath,
                        requireNotNull(request.second).absolutePath,
                        output.absolutePath,
                    )
                }
                contentResolver.openOutputStream(uri, "w")?.use { destination ->
                    output.inputStream().use { input -> input.copyTo(destination) }
                } ?: error("无法打开目标文件")
                busyState.value = false
                statusState.value = "导出完成：${displayName(uri) ?: request.outputName}"
                runOnUiThread { toast("音频已保存") }
            } catch (error: Throwable) {
                busyState.value = false
                statusState.value = "导出失败：${error.message ?: error.javaClass.simpleName}"
                runOnUiThread { toast(statusState.value) }
            } finally {
                output.delete()
            }
        }
    }

    /** 一次解码并顺序拼接保留分段，删除的分段不会进入最终文件。 */
    private fun exportSegments(request: ExportRequest, output: File) {
        require(request.segments.isNotEmpty()) { "没有可导出的音频分段" }
        FfmpegBridge.keepSegments(
            request.first.absolutePath,
            output.absolutePath,
            request.segments.flatMap { listOf(it.startMs, it.endMs) }.toLongArray(),
        )
    }

    private fun copyUriToCache(uri: Uri): File {
        // 临时文件扩展名不参与 FFmpeg 解码格式判断，FFmpeg 会读取文件内容和容器信息。
        val file = File.createTempFile("editor-input-", ".audio", cacheDir)
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法打开输入文件")
        return file
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun toast(message: CharSequence) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

private fun editorColors() = darkColorScheme(
    primary = Color(0xFF8BD7FF),
    onPrimary = Color(0xFF003548),
    secondary = Color(0xFFB8C8FF),
    background = Color(0xFF0C1117),
    surface = Color(0xFF141C24),
    surfaceVariant = Color(0xFF202B35),
)

private const val MAX_UNDO_STEPS = 20

/** 只记录分段编辑上下文，播放进度和选中变化本身不生成历史记录。 */
private data class SegmentEditSnapshot(
    val segments: List<AudioEditorActivity.AudioSegment>,
    val selectedSegmentIndex: Int,
    val playHeadFraction: Float,
)

private data class SegmentEdit(
    val before: SegmentEditSnapshot,
    val after: SegmentEditSnapshot,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AudioEditorScreen(
    firstTrack: AudioEditorActivity.Track?,
    secondTrack: AudioEditorActivity.Track?,
    status: String,
    busy: Boolean,
    playbackPositionMs: Long,
    waveformPlaying: Boolean,
    onChooseFirst: () -> Unit,
    onChooseSecond: () -> Unit,
    onPreview: (startMs: Long, endMs: Long, speed: Double, pitch: Double) -> Unit,
    onToggleWaveformPlayback: (List<AudioEditorActivity.AudioSegment>, Long) -> Unit,
    onPausePlayback: () -> Unit,
    onSeekPlayback: (Long) -> Unit,
    onExportSegments: (List<AudioEditorActivity.AudioSegment>) -> Unit,
    onExportConcat: () -> Unit,
    onStop: () -> Unit,
) {
    var playHeadFraction by remember(firstTrack?.file) { mutableFloatStateOf(0f) }
    var segments by remember(firstTrack?.file) {
        mutableStateOf(
            firstTrack?.let { listOf(AudioEditorActivity.AudioSegment(0L, it.durationMs)) }
                .orEmpty(),
        )
    }
    var selectedSegmentIndex by remember(firstTrack?.file) { mutableIntStateOf(0) }
    var undoHistory by remember(firstTrack?.file) {
        mutableStateOf<List<SegmentEdit>>(emptyList())
    }
    var redoHistory by remember(firstTrack?.file) {
        mutableStateOf<List<SegmentEdit>>(emptyList())
    }
    var speed by remember { mutableFloatStateOf(1f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    val durationMs = firstTrack?.durationMs ?: 1L
    LaunchedEffect(playbackPositionMs, durationMs) {
        if (durationMs > 0L) {
            playHeadFraction = (playbackPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            segments.indexOfFirst {
                playbackPositionMs >= it.startMs && playbackPositionMs < it.endMs
            }.takeIf { it >= 0 }?.let { selectedSegmentIndex = it }
        }
    }
    val playHeadMs = (durationMs * playHeadFraction).roundToInt().toLong()
    val selectedSegment = segments.getOrNull(selectedSegmentIndex)
    val currentSegmentIndex =
        segments.indexOfFirst { playHeadMs >= it.startMs && playHeadMs < it.endMs }
    val currentSegment = segments.getOrNull(currentSegmentIndex)
    val scrollState = rememberScrollState()
    val canDeleteSegment by remember(firstTrack?.file) {
        derivedStateOf { segments.size > 1 }
    }
    val canPlay by remember(firstTrack?.file) {
        derivedStateOf {
            !busy && segments.isNotEmpty()
        }
    }
    val canSplitSegment by remember(firstTrack?.file) {
        derivedStateOf {
            val currentPlayHeadMs =
                (durationMs * playHeadFraction).roundToInt().toLong()
            val segment = segments.firstOrNull {
                currentPlayHeadMs >= it.startMs && currentPlayHeadMs < it.endMs
            }

            segment != null &&
                    currentPlayHeadMs > segment.startMs &&
                    currentPlayHeadMs < segment.endMs
        }
    }

    fun recordSegmentEdit(
        updatedSegments: List<AudioEditorActivity.AudioSegment>,
        updatedSelectedIndex: Int,
    ) {
        if (updatedSegments == segments) return
        val before = SegmentEditSnapshot(
            segments = segments.toList(),
            selectedSegmentIndex = selectedSegmentIndex,
            playHeadFraction = playHeadFraction,
        )
        val after = before.copy(
            segments = updatedSegments.toList(),
            selectedSegmentIndex = updatedSelectedIndex,
        )
        undoHistory = (undoHistory + SegmentEdit(before, after)).takeLast(MAX_UNDO_STEPS)
        redoHistory = emptyList()
        segments = after.segments
        selectedSegmentIndex = after.selectedSegmentIndex
    }

    fun restoreSegmentEdit(snapshot: SegmentEditSnapshot) {
        // 暂停旧分段的播放，下次播放会使用恢复后的分段列表和播放头。
        onPausePlayback()
        segments = snapshot.segments
        selectedSegmentIndex = snapshot.selectedSegmentIndex
        playHeadFraction = snapshot.playHeadFraction
        onSeekPlayback((durationMs * snapshot.playHeadFraction).roundToInt().toLong())
    }

    val splitSegment = {
        onPausePlayback()
        val splitAtMs = playHeadMs.coerceIn(0L, durationMs)
        val segmentIndex = currentSegmentIndex.takeIf {
            it >= 0 && currentSegment != null &&
                    splitAtMs > currentSegment.startMs && splitAtMs < currentSegment.endMs
        } ?: -1
        if (segmentIndex >= 0) {
            val source = segments[segmentIndex]
            val updatedSegments = segments.toMutableList().apply {
                removeAt(segmentIndex)
                add(
                    segmentIndex,
                    AudioEditorActivity.AudioSegment(
                        splitAtMs,
                        source.endMs
                    )
                )
                add(
                    segmentIndex,
                    AudioEditorActivity.AudioSegment(
                        source.startMs,
                        splitAtMs
                    )
                )
            }
            recordSegmentEdit(updatedSegments, segmentIndex + 1)
        }
        Log.d("AudioEditorScreen", "segments size: ${segments.size}")
    }
    val splitSegmentState = rememberUpdatedState(splitSegment)
    val onTogglePlayback = {
        onToggleWaveformPlayback(segments, playHeadMs)
    }
    val onToggleWaveformPlaybackState = rememberUpdatedState(onTogglePlayback)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Audio Studio", fontWeight = FontWeight.Bold)
                        Text(
                            "FFmpeg 音频剪辑工作台",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EditorIntro()

            TrackCard(
                title = "主音轨",
                track = firstTrack,
                onChoose = onChooseFirst,
            )

            if (firstTrack != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("音频波形", style = MaterialTheme.typography.titleMedium)
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text(
                                    "${formatTime(playHeadMs)} / ${formatTime(durationMs)}",
                                    modifier = Modifier.padding(
                                        horizontal = 10.dp,
                                        vertical = 5.dp
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        WaveformSelection(
                            samples = firstTrack.waveform,
                            playHeadFraction = playHeadFraction,
                            segments = segments,
                            selectedSegmentIndex = selectedSegmentIndex,
                            durationMs = durationMs,
                            onDragStart = {
                                onPausePlayback()
                            },
                            onProgressChanged = { fraction ->
                                playHeadFraction = fraction
                                val timeMs = (durationMs * fraction).roundToInt().toLong()
                                segments.indexOfFirst { timeMs >= it.startMs && timeMs < it.endMs }
                                    .takeIf { it >= 0 }
                                    ?.let { selectedSegmentIndex = it }
                            },
                            onDragEndOrCancel = { fraction ->
                                playHeadFraction = fraction
                                val timeMs = (durationMs * fraction).roundToInt().toLong()
                                onSeekPlayback(timeMs)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onToggleWaveformPlaybackState.value() },
                            enabled = canPlay,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (waveformPlaying) "Ⅱ 暂停播放" else "▶ 播放预览")
                        }
                        Text(
                            "左右滑动波形调整播放进度；中间竖线是当前进度",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    splitSegmentState.value.invoke()
                                },
                                enabled = canSplitSegment,
                                modifier = Modifier.weight(1f),
                            ) { Text("在当前进度分割") }
                            OutlinedButton(
                                onClick = {
                                    onPausePlayback()
                                    if (segments.size > 1 && selectedSegmentIndex in segments.indices) {
                                        val updatedSegments = segments.toMutableList()
                                            .apply { removeAt(selectedSegmentIndex) }
                                        recordSegmentEdit(
                                            updatedSegments,
                                            (selectedSegmentIndex - 1).coerceAtLeast(0),
                                        )
                                    }
                                },
                                enabled = selectedSegment != null && canDeleteSegment,
                                modifier = Modifier.weight(1f),
                            ) { Text("删除选中分段") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    undoHistory.lastOrNull()?.let { edit ->
                                        restoreSegmentEdit(edit.before)
                                        undoHistory = undoHistory.dropLast(1)
                                        redoHistory = redoHistory + edit
                                    }
                                },
                                enabled = !busy && undoHistory.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) { Text("撤销") }
                            OutlinedButton(
                                onClick = {
                                    redoHistory.lastOrNull()?.let { edit ->
                                        restoreSegmentEdit(edit.after)
                                        redoHistory = redoHistory.dropLast(1)
                                        undoHistory = (undoHistory + edit).takeLast(MAX_UNDO_STEPS)
                                    }
                                },
                                enabled = !busy && redoHistory.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) { Text("重做") }
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("保留分段", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${segments.size} 段",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (segments.isEmpty()) {
                            Text(
                                "所有分段都已删除，请重新选择音频。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            segments.forEachIndexed { index, segment ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (index == selectedSegmentIndex) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                            },
                                        )
                                        .clickable {
                                            onPausePlayback()
                                            selectedSegmentIndex = index
                                            playHeadFraction =
                                                segment.startMs.toFloat() / durationMs
                                            onSeekPlayback(segment.startMs)
                                        }
                                        .padding(
                                            start = 12.dp,
                                            top = 8.dp,
                                            end = 4.dp,
                                            bottom = 8.dp
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "分段 ${index + 1}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (index == selectedSegmentIndex) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            "${formatTime(segment.startMs)} – ${formatTime(segment.endMs)} · ${
                                                formatTime(
                                                    segment.endMs - segment.startMs
                                                )
                                            }",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    OutlinedButton(
                                        enabled = canDeleteSegment,
                                        onClick = {
                                            onPausePlayback()
                                            if (segments.size > 1 && index in segments.indices) {
                                                val updatedSegments = segments.toMutableList()
                                                    .apply { removeAt(index) }
                                                val updatedSelectedIndex = when {
                                                    index < selectedSegmentIndex -> selectedSegmentIndex - 1
                                                    index == selectedSegmentIndex -> index.coerceAtMost(
                                                        updatedSegments.lastIndex
                                                    )

                                                    else -> selectedSegmentIndex
                                                }
                                                recordSegmentEdit(
                                                    updatedSegments,
                                                    updatedSelectedIndex,
                                                )
                                            }
                                        },
                                    ) { Text("删除") }
                                }
                            }
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("变速变调预览", style = MaterialTheme.typography.titleMedium)
                        SettingSlider(
                            label = "速度",
                            valueText = "${speed.formatOneDecimal()}×",
                            value = speed,
                            range = 0.5f..2f,
                            onValueChange = { speed = it },
                        )
                        SettingSlider(
                            label = "音调",
                            valueText = "${
                                pitch.roundToInt().let { if (it >= 0) "+$it" else it }
                            } 半音",
                            value = pitch,
                            range = -12f..12f,
                            onValueChange = { pitch = it },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    selectedSegment?.let {
                                        onPreview(
                                            it.startMs,
                                            it.endMs,
                                            speed.toDouble(),
                                            pitch.toDouble()
                                        )
                                    }
                                },
                                enabled = !busy && selectedSegment != null,
                                modifier = Modifier.weight(1f),
                            ) { Text("▶ 预览分段") }
                            OutlinedButton(
                                onClick = onStop,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("■ 停止") }
                        }
                    }
                }
            }

            TrackCard(
                title = "拼接音轨 · 第二段",
                track = secondTrack,
                onChoose = onChooseSecond,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onExportSegments(segments) },
                    enabled = !busy && firstTrack != null && segments.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("导出保留分段") }
                Button(
                    onClick = onExportConcat,
                    enabled = !busy && firstTrack != null && secondTrack != null,
                    modifier = Modifier.weight(1f),
                ) { Text("导出拼接") }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(20.dp)
                            .height(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EditorIntro() {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            "剪辑、塑形、合成",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "分割并删除不需要的音频片段，再将保留分段导出为 MP3。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackCard(
    title: String,
    track: AudioEditorActivity.Track?,
    onChoose: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    track?.name ?: "还没有选择音频",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    track?.let { "${formatTime(it.durationMs)} · 已生成波形" }
                        ?: "支持 MP3、WAV、FLAC、OGG、AAC 等格式",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onChoose) { Text(if (track == null) "选择" else "替换") }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            valueText,
            modifier = Modifier.width(66.dp),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun WaveformSelection(
    modifier: Modifier = Modifier,
    samples: List<Float>,
    playHeadFraction: Float,
    segments: List<AudioEditorActivity.AudioSegment>,
    selectedSegmentIndex: Int,
    durationMs: Long,
    onDragStart: () -> Unit = {},
    onProgressChanged: (Float) -> Unit,
    onDragEndOrCancel: (Float) -> Unit = {},
) {
    val onDragStartState = rememberUpdatedState(onDragStart)
    val currentPlayHeadState = rememberUpdatedState(playHeadFraction)
    val onProgressChangedState = rememberUpdatedState(onProgressChanged)
    val onDragEndOrCancelState = rememberUpdatedState(onDragEndOrCancel)
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val background = MaterialTheme.colorScheme.surfaceVariant
    val centerLine = MaterialTheme.colorScheme.onSurface
    // 中央只展示一段时间窗口，滑动时波形会在固定进度线下方移动。
    val visibleFraction = 0.35f

    Canvas(
        modifier = modifier
            .height(116.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .pointerInput(samples) {
                var dragFraction = currentPlayHeadState.value
                detectDragGestures(
                    onDragStart = {
                        onDragStartState.value()
                        dragFraction = currentPlayHeadState.value
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val deltaFraction = -dragAmount.x / width * visibleFraction
                        dragFraction = (dragFraction + deltaFraction).coerceIn(0f, 1f)
                        onProgressChangedState.value(dragFraction)
                    },
                    onDragEnd = {
                        onDragEndOrCancelState.value(dragFraction)
                    },
                    onDragCancel = {
                        onDragEndOrCancelState.value(dragFraction)
                    }
                )
            },
    ) {
        val width = size.width
        val height = size.height
        val centerX = width / 2f
        val pixelsPerFraction = width / visibleFraction
        val windowStart = playHeadFraction - visibleFraction / 2f
        val windowEnd = playHeadFraction + visibleFraction / 2f
        fun xAt(fraction: Float) = centerX + (fraction - playHeadFraction) * pixelsPerFraction

        drawRoundRect(
            background,
            size = Size(width, height),
            cornerRadius = CornerRadius(18.dp.toPx())
        )
        val count = samples.size.coerceAtLeast(1)
        val step = pixelsPerFraction / count
        val windowStartIndex = ((windowStart.coerceAtLeast(0f)) * count).toInt().coerceAtLeast(0)
        val windowEndIndex = ((windowEnd.coerceAtMost(1f)) * count).toInt().coerceAtMost(count)
        for (index in windowStartIndex until windowEndIndex) {
            val fraction = (index + 0.5f) / count
            val x = xAt(fraction)
            val barHeight = (samples[index].coerceIn(0.035f, 1f) * height * 0.72f)
            val segmentIndex = segments.indexOfFirst {
                fraction >= it.startMs.toFloat() / durationMs &&
                        fraction < it.endMs.toFloat() / durationMs
            }
            drawLine(
                color = when {
                    segmentIndex < 0 -> muted.copy(alpha = 0.22f)
                    segmentIndex == selectedSegmentIndex -> primary
                    else -> primary.copy(alpha = 0.5f)
                },
                start = Offset(x, (height - barHeight) / 2f),
                end = Offset(x, (height + barHeight) / 2f),
                strokeWidth = (step * 0.48f).coerceAtLeast(1f),
                cap = StrokeCap.Round,
            )
        }
        segments.flatMap { segment ->
            listOf(segment.startMs, segment.endMs)
        }.distinct().filter { it in 1..<durationMs }.forEach { boundaryMs ->
            val split = boundaryMs.toFloat() / durationMs
            if (split in windowStart..windowEnd) {
                val x = xAt(split)
                drawLine(
                    color = muted.copy(alpha = 0.5f),
                    start = Offset(x, 12.dp.toPx()),
                    end = Offset(x, height - 12.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        // 固定在屏幕中间的进度线，手势只移动波形内容。
        drawLine(
            color = centerLine.copy(alpha = 0.96f),
            start = Offset(centerX, 8.dp.toPx()),
            end = Offset(centerX, height - 8.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
        drawCircle(primary, radius = 5.dp.toPx(), center = Offset(centerX, 8.dp.toPx()))
        drawCircle(primary, radius = 5.dp.toPx(), center = Offset(centerX, height - 8.dp.toPx()))
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000L
    return String.format(Locale.getDefault(), "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

private fun Float.formatOneDecimal(): String = String.format(Locale.getDefault(), "%.1f", this)
