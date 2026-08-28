package com.bigjelly.temporun;

import android.app.Activity;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;

import com.bigjelly.temporun.player.AdvancedAudioPlayer;
import com.bigjelly.temporun.player.Mp3AudioTrackPlayer;

import java.util.Locale;

import kotlin.Unit;


public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private Mp3AudioTrackPlayer player = new Mp3AudioTrackPlayer();

    private AdvancedAudioPlayer advancedPlayer = new AdvancedAudioPlayer();
    private boolean isUserTrackingSeekBar = false; // 标记用户是否正在拖拽进度条

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button start = findViewById(R.id.start);
        start.setOnClickListener(v -> {
            player.playFromAssets(this, "music.mp3");
        });
        Button stop = findViewById(R.id.stop);
        stop.setOnClickListener(v -> {
            player.stop();
        });

        SeekBar seekBar = findViewById(R.id.seek_bar);
        TextView tvCurrent = findViewById(R.id.tv_current_time);
        TextView tvTotal = findViewById(R.id.tv_total_time);
        CheckBox cbLoop = findViewById(R.id.cb_loop);
        Button btnPlay = findViewById(R.id.btn_play);
        Button btnPause = findViewById(R.id.btn_pause);
        Button btnResume = findViewById(R.id.btn_resume);
        Button btnStop = findViewById(R.id.btn_stop);

        advancedPlayer.setOnProgressListener((currentMs, totalMs) -> {
            if (!isUserTrackingSeekBar) {
                Log.d(TAG, "onProgressListener: currentMs: " + currentMs + ", totalMs: " + totalMs);
                seekBar.setMax(totalMs.intValue());
                seekBar.setProgress(currentMs.intValue());
                tvCurrent.setText(formatTime(currentMs));
                tvTotal.setText(formatTime(totalMs));
            }
            return Unit.INSTANCE;
        });
        advancedPlayer.setOnCompletionListener(() -> {
            tvCurrent.setText("00:00");
            seekBar.setProgress(0);
            return Unit.INSTANCE;
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    tvCurrent.setText(formatTime((long) progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserTrackingSeekBar = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isUserTrackingSeekBar = false;
                advancedPlayer.seekTo(seekBar.getProgress());
            }
        });

        btnPlay.setOnClickListener(view -> advancedPlayer.playFromAssets(MainActivity.this, "music.mp3", cbLoop.isChecked()));
        btnPause.setOnClickListener(view -> advancedPlayer.pause());
        btnStop.setOnClickListener(view -> {
            advancedPlayer.stop();
            tvCurrent.setText("00:00");
            seekBar.setProgress(0);
        });
        btnResume.setOnClickListener(view -> advancedPlayer.resume());
        cbLoop.setOnCheckedChangeListener((buttonView, isChecked) -> advancedPlayer.setLooping(isChecked));

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        player.stop();
        advancedPlayer.stop();
    }

    private String formatTime(Long millis) {
        long totalSeconds = millis / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }
}
