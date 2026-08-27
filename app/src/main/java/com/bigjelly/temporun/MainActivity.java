package com.bigjelly.temporun;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.widget.Button;

import java.io.InputStream;
import java.net.URL;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;


public class MainActivity extends Activity {

    private Mp3AudioTrackPlayer player = new Mp3AudioTrackPlayer();

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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        player.stop();
    }
}
