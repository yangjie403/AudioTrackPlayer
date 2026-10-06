package com.bigjelly.temporun

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bigjelly.temporun.player.FfmpegBridge
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Audio converter entry screen. All user files are accessed through SAF Uris. */
class AudioConvertActivity : Activity() {

    companion object {
        private const val REQUEST_INPUT = 4101
        private const val REQUEST_OUTPUT = 4102
        private val FORMATS = listOf("MP3", "WAV", "FLAC", "OGG", "OPUS")
        private val FORMAT_KEYS = FORMATS.map(String::lowercase)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var inputUri: Uri? = null
    private var inputName: String = ""
    private var inputTempFile: File? = null

    private lateinit var metadataText: TextView
    private lateinit var versionText: TextView
    private lateinit var configText: TextView
    private lateinit var formatSpinner: Spinner
    private lateinit var convertButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
    }

    override fun onDestroy() {
        executor.shutdownNow()
        inputTempFile?.delete()
        super.onDestroy()
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            addView(scrollContent)
        }

        val title = TextView(this).apply {
            text = "音频格式转换"
            textSize = 24f
            setPadding(0, 0, 0, dp(16))
        }
        scrollContent.addView(title)

        val chooseButton = Button(this).apply {
            text = "选择音频文件"
            setOnClickListener { openInputDocument() }
        }
        scrollContent.addView(chooseButton, matchWidth())

        metadataText = TextView(this).apply {
            text = "尚未选择文件"
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(16))
        }
        scrollContent.addView(metadataText, matchWidth())

        val formatLabel = TextView(this).apply {
            text = "目标格式"
            textSize = 16f
        }
        scrollContent.addView(formatLabel, matchWidth())

        formatSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AudioConvertActivity,
                android.R.layout.simple_spinner_dropdown_item,
                FORMATS
            )
        }
        scrollContent.addView(formatSpinner, matchWidth())

        convertButton = Button(this).apply {
            text = "保存转换后的文件"
            isEnabled = false
            setOnClickListener { openOutputDocument() }
        }
        val convertParams = matchWidth().apply { topMargin = dp(16) }
        scrollContent.addView(convertButton, convertParams)

        progress = ProgressBar(this).apply {
            visibility = View.GONE
        }
        val progressParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        }
        scrollContent.addView(progress, progressParams)

        statusText = TextView(this).apply {
            text = "通过系统文件选择器读取，不需要存储权限"
            setPadding(0, dp(12), 0, 0)
        }
        scrollContent.addView(statusText, matchWidth())

        versionText = TextView(this)
        versionText.text = FfmpegBridge.version()
        versionText.gravity = Gravity.CENTER_HORIZONTAL
        scrollContent.addView(versionText, matchWidth())
        (versionText.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(50)
        configText = TextView(this)
        configText.text = FfmpegBridge.configuration()
        scrollContent.addView(configText, matchWidth())

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = insets.top, bottom = insets.bottom)
            windowInsets
        }
        return root
    }

    private fun openInputDocument() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            },
            REQUEST_INPUT
        )
    }

    private fun openOutputDocument() {
        val format = FORMAT_KEYS[formatSpinner.selectedItemPosition]
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType(format)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                putExtra(Intent.EXTRA_TITLE, suggestedOutputName(format))
            },
            REQUEST_OUTPUT
        )
    }

    @Deprecated("Uses the platform activity-result contract available on the app minSdk")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQUEST_INPUT -> selectInput(uri)
            REQUEST_OUTPUT -> convertTo(uri)
        }
    }

    private fun selectInput(uri: Uri) {
        inputUri = uri
        inputName = displayName(uri) ?: "input.audio"
        convertButton.isEnabled = false
        progress.visibility = View.VISIBLE
        statusText.text = "正在读取文件信息..."

        executor.execute {
            try {
                val temp = copyInputToCache(uri)
                val probe = FfmpegBridge.probe(temp.absolutePath)
                inputTempFile?.delete()
                inputTempFile = temp
                runOnUiThread {
                    metadataText.text = formatMetadata(inputName, probe)
                    statusText.text = "文件已选择，请选择目标格式并保存"
                    convertButton.isEnabled = true
                    progress.visibility = View.GONE
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    statusText.text = "读取失败：${error.message ?: error.javaClass.simpleName}"
                    toast(statusText.text)
                }
            }
        }
    }

    private fun convertTo(outputUri: Uri) {
        val source = inputTempFile
        if (source == null || !source.isFile) {
            statusText.text = "请先选择输入文件"
            return
        }

        val format = FORMAT_KEYS[formatSpinner.selectedItemPosition]
        convertButton.isEnabled = false
        progress.visibility = View.VISIBLE
        statusText.text = "正在转换..."

        executor.execute {
            val outputTemp = File.createTempFile("converted-", ".${extension(format)}", cacheDir)
            try {
                FfmpegBridge.convert(source.absolutePath, outputTemp.absolutePath, format)
                contentResolver.openOutputStream(outputUri, "w")?.use { output ->
                    outputTemp.inputStream().use { input -> input.copyTo(output) }
                } ?: error("无法打开目标文件")

                runOnUiThread {
                    progress.visibility = View.GONE
                    convertButton.isEnabled = true
                    statusText.text =
                        "转换完成：${displayName(outputUri) ?: suggestedOutputName(format)}"
                    toast("文件已保存")
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    convertButton.isEnabled = true
                    statusText.text = "转换失败：${error.message ?: error.javaClass.simpleName}"
                    toast(statusText.text)
                }
            } finally {
                outputTemp.delete()
            }
        }
    }

    private fun copyInputToCache(uri: Uri): File {
        val file = File.createTempFile("input-", ".audio", cacheDir)
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法打开输入文件")
        return file
    }

    private fun formatMetadata(
        name: String,
        probe: com.bigjelly.temporun.player.FfmpegProbe
    ): String {
        val duration = if (probe.durationMs > 0) {
            val seconds = probe.durationMs / 1000
            "%02d:%02d".format(seconds / 60, seconds % 60)
        } else {
            "未知"
        }
        return buildString {
            appendLine("文件：$name")
            appendLine("格式：${probe.format ?: "未知"}${probe.formatLong?.let { "（$it）" } ?: ""}")
            appendLine("编码：${probe.codec ?: "未知"}")
            appendLine("时长：$duration")
            appendLine("采样率：${probe.sampleRate} Hz")
            appendLine("声道：${probe.channels}（${probe.channelLayout ?: "未知"}）")
            appendLine("采样格式：${probe.sampleFmt ?: "未知"}")
            if (probe.bitRate > 0) appendLine("码率：${probe.bitRate / 1000} kbps")
            if (probe.tags.isNotEmpty()) appendLine("标签：${probe.tags.entries.joinToString { "${it.key}=${it.value}" }}")
        }
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun suggestedOutputName(format: String): String {
        val base = inputName.substringBeforeLast('.', inputName).ifBlank { "converted" }
        return "$base.${extension(format)}"
    }

    private fun mimeType(format: String): String = when (format) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    private fun extension(format: String): String = if (format == "opus") "opus" else format

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: CharSequence) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
