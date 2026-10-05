package com.memoflow.benchmark

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.memoflow.service.RecordingForegroundService
import java.util.Locale
import kotlinx.coroutines.delay

class PowerBenchmarkActivity : ComponentActivity() {
    private var pendingAction: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingAction?.let(::startBenchmarkService)
            pendingAction = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                PowerBenchmarkScreen(
                    onStart = { action -> requestAndStart(action) },
                    onStop = {
                        startService(
                            Intent(this, PowerBenchmarkService::class.java)
                                .setAction(PowerBenchmarkService.ACTION_STOP),
                        )
                    },
                    onBack = { finish() },
                )
            }
        }
    }

    private fun requestAndStart(action: String) {
        val recordingPrefs =
            getSharedPreferences(RecordingForegroundService.PREFS_RECORDING, Context.MODE_PRIVATE)
        if (recordingPrefs.getBoolean(RecordingForegroundService.KEY_RECORDING_ACTIVE, false)) {
            return
        }

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            startBenchmarkService(action)
        } else {
            pendingAction = action
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startBenchmarkService(action: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, PowerBenchmarkService::class.java).setAction(action),
        )
    }
}

@Composable
private fun PowerBenchmarkScreen(
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs =
        remember {
            context.getSharedPreferences(
                PowerBenchmarkService.PREFS,
                Context.MODE_PRIVATE,
            )
        }
    val recordingPrefs =
        remember {
            context.getSharedPreferences(
                RecordingForegroundService.PREFS_RECORDING,
                Context.MODE_PRIVATE,
            )
        }

    var active by remember { mutableStateOf(false) }
    var activeMode by remember { mutableStateOf("") }
    var startElapsed by remember { mutableLongStateOf(0L) }
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var productionRecording by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            active = prefs.getBoolean(PowerBenchmarkService.KEY_ACTIVE, false)
            activeMode = prefs.getString(PowerBenchmarkService.KEY_MODE, "").orEmpty()
            startElapsed = prefs.getLong(PowerBenchmarkService.KEY_START_ELAPSED_MS, 0L)
            productionRecording =
                recordingPrefs.getBoolean(
                    RecordingForegroundService.KEY_RECORDING_ACTIVE,
                    false,
                )
            nowElapsed = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }

    val lastMode = prefs.getString(PowerBenchmarkService.KEY_LAST_MODE, "").orEmpty()
    val lastDuration = prefs.getLong(PowerBenchmarkService.KEY_LAST_DURATION_MS, 0L)
    val lastCpu = prefs.getLong(PowerBenchmarkService.KEY_LAST_CPU_MS, 0L)
    val lastBytes = prefs.getLong(PowerBenchmarkService.KEY_LAST_OUTPUT_BYTES, 0L)
    val lastCharge =
        prefs.getLong(
            PowerBenchmarkService.KEY_LAST_CHARGE_DELTA_UAH,
            Long.MIN_VALUE,
        )
    val lastBatteryStart = prefs.getInt(PowerBenchmarkService.KEY_LAST_BATTERY_START, -1)
    val lastBatteryEnd = prefs.getInt(PowerBenchmarkService.KEY_LAST_BATTERY_END, -1)
    val actualSampleRate = prefs.getInt(PowerBenchmarkService.KEY_LAST_ACTUAL_SAMPLE_RATE, 0)
    val actualChannels = prefs.getInt(PowerBenchmarkService.KEY_LAST_ACTUAL_CHANNELS, 0)
    val actualBitrate = prefs.getInt(PowerBenchmarkService.KEY_LAST_ACTUAL_BITRATE, 0)
    val actualMime = prefs.getString(PowerBenchmarkService.KEY_LAST_ACTUAL_MIME, "").orEmpty()
    val lastError = prefs.getString(PowerBenchmarkService.KEY_LAST_ERROR, "").orEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("MediaRecorder 功耗参数测试", fontSize = 25.sp)
            OutlinedButton(onClick = onBack) { Text("返回") }
        }

        Text(
            "先跑 D 空闲基线，再分别跑 B1–B4。建议每组 30–60 分钟，保持相近电量、网络和屏幕关闭状态。最终比较“录音平均电流 − D 基线平均电流”。",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (productionRecording) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
            ) {
                Text(
                    "正式录音正在运行，请先结束正式录音再测试。",
                    Modifier.padding(14.dp),
                )
            }
        }

        BenchmarkCard(
            title = "D · 空闲基线",
            description = "不打开麦克风、不编码、不写音频，只保留测试前台服务，用于估计同条件下整机基础放电。",
            enabled = !active && !productionRecording,
            onClick = { onStart(PowerBenchmarkService.ACTION_START_D) },
        )

        HorizontalDivider()
        Text("MediaRecorder 参数矩阵", fontSize = 20.sp)

        BenchmarkCard(
            title = "B1 · 16 kHz / MIC",
            description = "当前正式版参数：16 kHz、单声道、24 kbps AAC、MIC。",
            enabled = !active && !productionRecording,
            onClick = { onStart(PowerBenchmarkService.ACTION_START_B16_MIC) },
        )
        BenchmarkCard(
            title = "B2 · 48 kHz / MIC",
            description = "验证设备底层原生 48 kHz 路径是否能避免重采样并进一步省电。",
            enabled = !active && !productionRecording,
            onClick = { onStart(PowerBenchmarkService.ACTION_START_B48_MIC) },
        )
        BenchmarkCard(
            title = "B3 · 16 kHz / VOICE_RECOGNITION",
            description = "比较 VOICE_RECOGNITION 的厂商 DSP/前处理路径与普通 MIC 的功耗差异。",
            enabled = !active && !productionRecording,
            onClick = { onStart(PowerBenchmarkService.ACTION_START_B16_VOICE) },
        )
        BenchmarkCard(
            title = "B4 · 48 kHz / VOICE_RECOGNITION",
            description = "48 kHz + VOICE_RECOGNITION 组合。",
            enabled = !active && !productionRecording,
            onClick = { onStart(PowerBenchmarkService.ACTION_START_B48_VOICE) },
        )

        if (active) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "正在测试 " + PowerBenchmarkService.modeDescription(activeMode),
                        fontSize = 18.sp,
                    )
                    val elapsed =
                        if (startElapsed > 0L) {
                            (nowElapsed - startElapsed).coerceAtLeast(0L)
                        } else {
                            0L
                        }
                    Text("已运行 " + formatDuration(elapsed))
                    Button(onClick = onStop) { Text("停止并保存结果") }
                    Text("建议锁屏继续测试，以减少屏幕造成的误差。")
                }
            }
        }

        HorizontalDivider()
        Text("最近一次结果", fontSize = 20.sp)

        if (lastMode.isBlank()) {
            Text("还没有测试结果。")
        } else {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(PowerBenchmarkService.modeDescription(lastMode))
                    Text("测试时长：" + formatDuration(lastDuration))
                    Text("MemoFlow 进程 CPU 时间：" + formatDuration(lastCpu))

                    if (lastDuration > 0L) {
                        val cpuPercent = 100.0 * lastCpu / lastDuration
                        Text(String.format(Locale.US, "进程 CPU / 墙钟：%.1f%%", cpuPercent))
                    }

                    if (lastBatteryStart >= 0 && lastBatteryEnd >= 0) {
                        Text("系统电量：" + lastBatteryStart + "% → " + lastBatteryEnd + "%")
                    }

                    if (lastCharge != Long.MIN_VALUE) {
                        val consumedUah = (-lastCharge).coerceAtLeast(0L)
                        val durationHours = lastDuration / 3_600_000.0
                        val currentMa =
                            if (durationHours > 0.0) consumedUah / 1000.0 / durationHours else 0.0
                        val perMinute =
                            if (lastDuration > 0L) {
                                consumedUah * 60_000.0 / lastDuration
                            } else {
                                0.0
                            }

                        Text(
                            "Charge counter 变化：" +
                                String.format(Locale.US, "%,d µAh", lastCharge),
                        )
                        Text(
                            String.format(
                                Locale.US,
                                "约 %.0f µAh/分钟 · 等效整机平均电流 %.1f mA",
                                perMinute,
                                currentMa,
                            ),
                        )
                    } else {
                        Text("本机未提供可用的 charge counter。")
                    }

                    if (lastBytes > 0L) {
                        Text("输出文件：" + formatBytes(lastBytes))
                    }

                    if (actualSampleRate > 0 || actualMime.isNotBlank()) {
                        Text(
                            "实际文件参数：" +
                                (actualMime.ifBlank { "audio" }) + " · " +
                                actualSampleRate + " Hz · " +
                                actualChannels + " ch" +
                                if (actualBitrate > 0) " · " + actualBitrate / 1000 + " kbps" else "",
                        )
                    }

                    if (lastError.isNotBlank()) {
                        Text("错误：" + lastError, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Text(
            "正式录音仍固定使用 MediaRecorder，不会跟随这里的测试参数自动切换。你把 D、B1–B4 的结果发给我后，再决定是否修改正式版采样率或 AudioSource。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun BenchmarkCard(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 18.sp)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick, enabled = enabled) { Text("开始测试") }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L ->
            String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L ->
            String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> bytes.toString() + " B"
    }
