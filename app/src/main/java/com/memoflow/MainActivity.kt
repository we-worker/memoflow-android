package com.memoflow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.memoflow.service.BootReceiver
import com.memoflow.service.RecordingForegroundService
import com.memoflow.ui.EchoApp
import com.memoflow.ui.EchoViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: EchoViewModel by viewModels()

    private var recordingActive by mutableStateOf(false)
    private var recordingStartedAtMs by mutableLongStateOf(0L)

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) startCapture()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshRecordingState()

        setContent {
            EchoApp(
                viewModel = viewModel,
                recordingActive = recordingActive,
                recordingStartedAtMs = recordingStartedAtMs,
                onStartRecording = { ensurePermissions() },
                onStopRecording = { stopCapture() },
            )
        }

        val prefs = getSharedPreferences(BootReceiver.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(BootReceiver.KEY_RESUME_REQUESTED, false)) {
            prefs.edit().putBoolean(BootReceiver.KEY_RESUME_REQUESTED, false).apply()
            ensurePermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRecordingState()
    }

    private fun ensurePermissions() {
        val needed = buildList {
            if (
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)

            if (
                Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isEmpty()) startCapture() else permissions.launch(needed.toTypedArray())
    }

    private fun startCapture() {
        val now = System.currentTimeMillis()
        val result =
            runCatching {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, RecordingForegroundService::class.java)
                        .setAction(RecordingForegroundService.ACTION_START),
                )
            }
        if (result.isSuccess) {
            recordingActive = true
            recordingStartedAtMs = now
        }
    }

    private fun stopCapture() {
        startService(
            Intent(this, RecordingForegroundService::class.java)
                .setAction(RecordingForegroundService.ACTION_STOP),
        )
        recordingActive = false
    }

    private fun refreshRecordingState() {
        val prefs =
            getSharedPreferences(
                RecordingForegroundService.PREFS_RECORDING,
                Context.MODE_PRIVATE,
            )
        recordingActive =
            prefs.getBoolean(RecordingForegroundService.KEY_RECORDING_ACTIVE, false)
        recordingStartedAtMs =
            prefs.getLong(RecordingForegroundService.KEY_RECORDING_STARTED_AT, 0L)
    }
}
