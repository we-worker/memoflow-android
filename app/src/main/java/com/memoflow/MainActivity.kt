package com.memoflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.memoflow.service.RecordingForegroundService

class MainActivity : ComponentActivity() {
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) startCapture()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MemoApp(
                onStart = { ensurePermission() },
                onStop = { stopCapture() },
            )
        }
    }

    private fun ensurePermission() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            startCapture()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startCapture() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, RecordingForegroundService::class.java)
                .setAction(RecordingForegroundService.ACTION_START),
        )
    }

    private fun stopCapture() {
        startService(
            Intent(this, RecordingForegroundService::class.java)
                .setAction(RecordingForegroundService.ACTION_STOP),
        )
    }
}

@Composable
fun MemoApp(
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    var active by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("MemoFlow", style = MaterialTheme.typography.headlineLarge)
                Text("Capture first. Process later.")

                Card {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(if (active) "Recording is active" else "Recording is stopped")
                        Text("AudioRecord · AAC-LC · 10 minute M4A · VAD")
                        Button(
                            onClick = {
                                if (active) onStop() else onStart()
                                active = !active
                            },
                        ) {
                            Text(if (active) "Stop recording" else "Start recording")
                        }
                    }
                }

                Text(
                    "VAD labels speech ranges and never gates capture. ASR runs through the configured PC endpoint.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
