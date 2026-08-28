package com.bigjelly.temporun

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import com.bigjelly.temporun.player.AdvancedAudioPlayer
import com.bigjelly.temporun.player.Mp3AudioTrackPlayer
import java.util.Locale

class MainActivity : Activity() {

    private val player = Mp3AudioTrackPlayer()
    private val advancedPlayer = AdvancedAudioPlayer()
    private var isUserTrackingSeekBar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val start = findViewById<Button>(R.id.start)
        start.setOnClickListener {
            player.playFromAssets(this, "music.mp3")
        }

        val stop = findViewById<Button>(R.id.stop)
        stop.setOnClickListener {
            player.stop()
        }

        val seekBar = findViewById<SeekBar>(R.id.seek_bar)
        val tvCurrent = findViewById<TextView>(R.id.tv_current_time)
        val tvTotal = findViewById<TextView>(R.id.tv_total_time)
        val cbLoop = findViewById<CheckBox>(R.id.cb_loop)
        val btnPlay = findViewById<Button>(R.id.btn_play)
        val btnPause = findViewById<Button>(R.id.btn_pause)
        val btnResume = findViewById<Button>(R.id.btn_resume)
        val btnStop = findViewById<Button>(R.id.btn_stop)

        advancedPlayer.onProgressListener = { currentMs, totalMs ->
            if (!isUserTrackingSeekBar) {
                tvCurrent.text = formatTime(currentMs)
                tvTotal.text = formatTime(totalMs)
                if (totalMs > 0) {
                    seekBar.max = totalMs.toInt()
                    seekBar.progress = currentMs.toInt()
                }
            }
        }

        advancedPlayer.onCompletionListener = {
            tvCurrent.text = "00:00"
            seekBar.progress = 0
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                if (fromUser) {
                    tvCurrent.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isUserTrackingSeekBar = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isUserTrackingSeekBar = false
                advancedPlayer.seekTo(seekBar.progress.toLong())
            }
        })

        btnPlay.setOnClickListener {
            advancedPlayer.playFromAssets(this, "music.mp3", cbLoop.isChecked)
        }
        btnPause.setOnClickListener { advancedPlayer.pause() }
        btnResume.setOnClickListener { advancedPlayer.resume() }
        btnStop.setOnClickListener {
            advancedPlayer.stop()
            tvCurrent.text = "00:00"
            seekBar.progress = 0
        }
        cbLoop.setOnCheckedChangeListener { _, isChecked ->
            advancedPlayer.setLooping(isChecked)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.stop()
        advancedPlayer.stop()
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
