package com.bigjelly.temporun

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.annotation.RequiresPermission
import com.bigjelly.temporun.recoder.PcmRecoder
import com.bigjelly.temporun.recoder.WavPcmPlayer
import com.bigjelly.temporun.recoder.WaveformView
import java.io.File

class RecorderActivity : Activity() {

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001
    }

    private val recorder = PcmRecoder()
    private val player = WavPcmPlayer()

    private lateinit var statusText: TextView
    private lateinit var waveformView: WaveformView
    private var lastRecordedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recorder)

        statusText = findViewById(R.id.tv_record_status)
        waveformView = findViewById(R.id.waveform_view)

        val startButton = findViewById<Button>(R.id.btn_start_record)
        val stopButton = findViewById<Button>(R.id.btn_stop_record)
        val playButton = findViewById<Button>(R.id.btn_play_record)

        recorder.onAmplitude = { amplitude ->
            waveformView.addAmplitude(amplitude)
        }

        recorder.onStateChanged = { state ->
            runOnUiThread {
                statusText.text = when (state) {
                    PcmRecoder.State.IDLE -> "空闲"
                    PcmRecoder.State.RECORDING -> "正在录音..."
                    PcmRecoder.State.STOPPING -> "正在保存WAV..."
                }
            }
        }

        recorder.onStopped = { file ->
            runOnUiThread {
                lastRecordedFile = file
                statusText.text = "录音已保存：${file.name}"
            }
        }

        recorder.onError = { error ->
            runOnUiThread {
                statusText.text = "错误：${error.message ?: error.javaClass.simpleName}"
            }
        }

        startButton.setOnClickListener {
            startOrRequestPermission()
        }

        stopButton.setOnClickListener {
            recorder.stop()
        }

        playButton.setOnClickListener {
            val file = lastRecordedFile
            if (file == null || !file.isFile) {
                statusText.text = "还没有可播放的录音"
                return@setOnClickListener
            }

            statusText.text = "正在播放：${file.name}"
            val accepted = player.play(
                file = file,
                onFinished = {
                    runOnUiThread { statusText.text = "播放完成" }
                },
                onError = { error ->
                    runOnUiThread {
                        statusText.text = "播放失败：${error.message ?: error.javaClass.simpleName}"
                    }
                }
            )
            if (!accepted) statusText.text = "录音文件不存在"
        }
    }

    private fun startOrRequestPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        startRecording()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        val outputDirectory = File(filesDir, "recordings")
        val outputFile = File(outputDirectory, "record_${System.currentTimeMillis()}.wav")
        waveformView.clearWaveform()
        lastRecordedFile = null
        statusText.text = "正在初始化录音"
        if (!recorder.start(outputFile)) {
            statusText.text = "录音启动失败"
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                statusText.text = "没有录音权限，无法使用麦克风"
            }
        }
    }
}