package com.bigjelly.temporun

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bigjelly.temporun.player.AdvancedAudioPlayer
import com.bigjelly.temporun.player.FfmpegBridge
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
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
    private enum class ExportKind { TRIM, CONCAT }

    /** 页面状态中保存的一段音频；file 是 native 层实际读取的缓存文件。 */
    data class Track(
        val file: File,
        val name: String,
        val durationMs: Long,
        val waveform: List<Float>,
    )

    /** SAF 返回保存位置之前暂存的导出请求。 */
    private data class ExportRequest(
        val kind: ExportKind,
        val first: File,
        val second: File? = null,
        val startMs: Long = 0L,
        val endMs: Long = 0L,
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
        setContent {
            val firstTrack by firstTrackState
            val secondTrack by secondTrackState
            val status by statusState
            val busy by busyState
            MaterialTheme(colorScheme = editorColors()) {
                AudioEditorScreen(
                    firstTrack = firstTrack,
                    secondTrack = secondTrack,
                    status = status,
                    busy = busy,
                    onChooseFirst = {
                        importTarget = ImportTarget.FIRST
                        openAudio.launch(arrayOf("audio/*"))
                    },
                    onChooseSecond = {
                        importTarget = ImportTarget.SECOND
                        openAudio.launch(arrayOf("audio/*"))
                    },
                    onPreview = ::previewSelection,
                    onExportTrim = ::requestTrimExport,
                    onExportConcat = ::requestConcatExport,
                    onStop = {
                        player.stop()
                        statusState.value = "播放已停止"
                    },
                )
            }
        }
        loadAssetExample()
    }

    override fun onStop() {
        player.stop()
        previewTempFile?.delete()
        previewTempFile = null
        super.onStop()
    }

    override fun onDestroy() {
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
                firstTrackState.value = track
                busyState.value = false
                statusState.value = "拖动波形两侧把手选择区间"
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
        val waveform = FfmpegBridge.waveform(file.absolutePath).toList()
        return Track(
            file = file,
            name = name,
            durationMs = probe.durationMs.coerceAtLeast(1L),
            waveform = waveform,
        )
    }

    private fun previewSelection(startMs: Long, endMs: Long, speed: Double, pitch: Double) {
        val track = firstTrackState.value ?: return
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

    private fun requestTrimExport(startMs: Long, endMs: Long) {
        val track = firstTrackState.value ?: return
        // 先记录参数并打开系统保存器；用户确认路径后才开始执行 native 导出。
        pendingExport = ExportRequest(
            kind = ExportKind.TRIM,
            first = track.file,
            startMs = startMs,
            endMs = endMs,
            outputName = "${track.name.substringBeforeLast('.', "clip")}-clip.mp3",
        )
        createOutput.launch(pendingExport?.outputName ?: "clip.mp3")
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
                    ExportKind.TRIM -> FfmpegBridge.trim(
                        request.first.absolutePath,
                        output.absolutePath,
                        request.startMs,
                        request.endMs,
                    )

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

@Composable
private fun editorColors() = darkColorScheme(
    primary = Color(0xFF8BD7FF),
    onPrimary = Color(0xFF003548),
    secondary = Color(0xFFB8C8FF),
    background = Color(0xFF0C1117),
    surface = Color(0xFF141C24),
    surfaceVariant = Color(0xFF202B35),
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AudioEditorScreen(
    firstTrack: AudioEditorActivity.Track?,
    secondTrack: AudioEditorActivity.Track?,
    status: String,
    busy: Boolean,
    onChooseFirst: () -> Unit,
    onChooseSecond: () -> Unit,
    onPreview: (startMs: Long, endMs: Long, speed: Double, pitch: Double) -> Unit,
    onExportTrim: (startMs: Long, endMs: Long) -> Unit,
    onExportConcat: () -> Unit,
    onStop: () -> Unit,
) {
    var startFraction by remember(firstTrack?.file) { mutableFloatStateOf(0f) }
    var endFraction by remember(firstTrack?.file) { mutableFloatStateOf(1f) }
    var speed by remember { mutableFloatStateOf(1f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    val durationMs = firstTrack?.durationMs ?: 1L
    val startMs = (durationMs * startFraction).roundToInt().toLong()
    val endMs = (durationMs * endFraction).roundToInt().toLong().coerceAtLeast(startMs + 1L)
    val scrollState = rememberScrollState()

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
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("波形选区", style = MaterialTheme.typography.titleMedium)
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text(
                                    "${formatTime(startMs)} – ${formatTime(endMs)}",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        WaveformSelection(
                            samples = firstTrack.waveform,
                            startFraction = startFraction,
                            endFraction = endFraction,
                            onRangeChanged = { start, end ->
                                startFraction = start
                                endFraction = end
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "拖动两侧把手调整起止点，也可以拖动中间选区整体移动",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            valueText = "${pitch.roundToInt().let { if (it >= 0) "+$it" else it }} 半音",
                            value = pitch,
                            range = -12f..12f,
                            onValueChange = { pitch = it },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onPreview(startMs, endMs, speed.toDouble(), pitch.toDouble()) },
                                enabled = !busy && endMs > startMs,
                                modifier = Modifier.weight(1f),
                            ) { Text("▶ 预览") }
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
                    onClick = { onExportTrim(startMs, endMs) },
                    enabled = !busy && firstTrack != null && endMs > startMs,
                    modifier = Modifier.weight(1f),
                ) { Text("导出裁剪") }
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
                        modifier = Modifier.width(20.dp).height(20.dp),
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
        Text("剪辑、塑形、合成", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "选择一段音频，拖动波形设定范围；渲染后的结果可以保存为 MP3。",
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
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    track?.name ?: "还没有选择音频",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    track?.let { "${formatTime(it.durationMs)} · 已生成波形" } ?: "支持 MP3、WAV、FLAC、OGG、AAC 等格式",
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
    samples: List<Float>,
    startFraction: Float,
    endFraction: Float,
    onRangeChanged: (start: Float, end: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentStart by rememberUpdatedState(startFraction)
    val currentEnd by rememberUpdatedState(endFraction)
    var activeHandle by remember { mutableIntStateOf(0) }
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val background = MaterialTheme.colorScheme.surfaceVariant
    val density = LocalDensity.current
    // 热区半径，不再使用固定像素，避免高密度设备上难以命中。
    val handleHitRadius = with(density) { 20.dp.toPx() }
    val handleWidth = with(density) { 8.dp.toPx() }

    Canvas(
        modifier = modifier
            .height(132.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .pointerInput(samples, onRangeChanged, handleHitRadius) {
                detectDragGestures(
                    onDragStart = { position ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val startX = currentStart * width
                        val endX = currentEnd * width
                        val startDistance = abs(position.x - startX)
                        val endDistance = abs(position.x - endX)
                        activeHandle = when {
                            minOf(startDistance, endDistance) <= handleHitRadius -> {
                                // 两个热区重叠时选择距离更近的端点，窄选区也能稳定拖动。
                                if (startDistance <= endDistance) 1 else 2
                            }
                            position.x in startX..endX -> 3
                            position.x < startX -> 1
                            else -> 2
                        }
                    },
                    onDrag = { change, dragAmount ->
                        val delta = dragAmount.x / size.width.toFloat().coerceAtLeast(1f)
                        val currentStartValue = currentStart
                        val currentEndValue = currentEnd
                        when (activeHandle) {
                            1 -> onRangeChanged(
                                (currentStartValue + delta).coerceIn(0f, currentEndValue - 0.01f),
                                currentEndValue,
                            )
                            2 -> onRangeChanged(
                                currentStartValue,
                                (currentEndValue + delta).coerceIn(currentStartValue + 0.01f, 1f),
                            )
                            3 -> {
                                val span = currentEndValue - currentStartValue
                                val movedStart = (currentStartValue + delta).coerceIn(0f, 1f - span)
                                onRangeChanged(movedStart, movedStart + span)
                            }
                        }
                    },
                )
            },
    ) {
        val width = size.width
        val height = size.height
        val selectedStart = startFraction * width
        val selectedEnd = endFraction * width
        drawRoundRect(background, size = Size(width, height), cornerRadius = CornerRadius(16.dp.toPx()))
        val count = samples.size.coerceAtLeast(1)
        val step = width / count
        samples.forEachIndexed { index, amplitude ->
            val x = index * step + step / 2f
            val barHeight = (amplitude.coerceIn(0.04f, 1f) * height * 0.76f)
            val selected = x in selectedStart..selectedEnd
            drawLine(
                color = if (selected) primary else muted.copy(alpha = 0.45f),
                start = Offset(x, (height - barHeight) / 2f),
                end = Offset(x, (height + barHeight) / 2f),
                strokeWidth = (step * 0.55f).coerceAtLeast(1.5f),
                cap = StrokeCap.Round,
            )
        }
        drawRect(Color.Black.copy(alpha = 0.28f), size = Size(selectedStart, height))
        drawRect(
            Color.Black.copy(alpha = 0.28f),
            topLeft = Offset(selectedEnd, 0f),
            size = Size((width - selectedEnd).coerceAtLeast(0f), height),
        )
        drawRoundRect(
            primary.copy(alpha = 0.22f),
            topLeft = Offset(selectedStart, 0f),
            size = Size((selectedEnd - selectedStart).coerceAtLeast(0f), height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
            style = Stroke(width = 1.dp.toPx()),
        )
        drawLine(primary, Offset(selectedStart, 0f), Offset(selectedStart, height), strokeWidth = 3.dp.toPx())
        drawLine(primary, Offset(selectedEnd, 0f), Offset(selectedEnd, height), strokeWidth = 3.dp.toPx())
        // 把手覆盖在波形内部，左右端点也保留完整可见面积。
        drawRoundRect(
            color = primary,
            topLeft = Offset((selectedStart - handleWidth / 2f).coerceIn(0f, width - handleWidth), 0f),
            size = Size(handleWidth, height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(handleWidth / 2f),
        )
        drawRoundRect(
            color = primary,
            topLeft = Offset((selectedEnd - handleWidth / 2f).coerceIn(0f, width - handleWidth), 0f),
            size = Size(handleWidth, height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(handleWidth / 2f),
        )
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000L
    return String.format(Locale.getDefault(), "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

private fun Float.formatOneDecimal(): String = String.format(Locale.getDefault(), "%.1f", this)
