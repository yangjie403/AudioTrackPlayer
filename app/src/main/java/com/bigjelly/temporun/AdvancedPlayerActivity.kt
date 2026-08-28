package com.bigjelly.temporun

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bigjelly.temporun.player.AdvancedAudioPlayer
import java.util.Locale

class AdvancedPlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { AdvancedPlayerScreen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedPlayerScreen(modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    val player = remember { AdvancedAudioPlayer() }
    var currentMs by remember { mutableLongStateOf(0L) }
    var totalMs by remember { mutableLongStateOf(0L) }
    var sliderMs by remember { mutableLongStateOf(0L) }
    var isLooping by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    val isDraggingState by rememberUpdatedState(isDragging)

    DisposableEffect(player) {
        player.onProgressListener = { current, total ->
            currentMs = current
            totalMs = total
            if (!isDraggingState) sliderMs = current
        }
        player.onCompletionListener = {
            currentMs = 0L
            sliderMs = 0L
        }
        onDispose {
            player.onProgressListener = null
            player.onCompletionListener = null
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("AdvancedAudioPlayer") }) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("AudioTrack + MediaCodec 播放示例", style = MaterialTheme.typography.headlineSmall)
            Text(
                "展示 AdvancedAudioPlayer 的进度回调、跳转、暂停和循环播放。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("music.mp3", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Slider(
                        value = sliderMs.toFloat(),
                        onValueChange = {
                            isDragging = true
                            sliderMs = it.toLong()
                            currentMs = sliderMs
                        },
                        onValueChangeFinished = {
                            player.seekTo(sliderMs)
                            // 先提交 seek，再结束拖动状态，避免旧进度回调在此期间覆盖目标位置。
                            isDragging = false
                        },
                        valueRange = 0f..totalMs.coerceAtLeast(1L).toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text(formatTime(currentMs))
                        Text(formatTime(totalMs))
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        player.playFromAssets(activity as Context, "music.mp3", isLooping)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("播放") }
                OutlinedButton(onClick = player::pause, Modifier.weight(1f)) { Text("暂停") }
                OutlinedButton(onClick = player::resume, Modifier.weight(1f)) { Text("继续") }
            }

            Button(
                onClick = {
                    player.stop()
                    currentMs = 0L
                    sliderMs = 0L
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("停止") }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = isLooping,
                    onCheckedChange = {
                        isLooping = it
                        player.setLooping(it)
                    }
                )
                Text("循环播放", Modifier.padding(start = 12.dp))
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    return String.format(Locale.getDefault(), "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
