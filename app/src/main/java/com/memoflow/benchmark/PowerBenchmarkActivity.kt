package com.memoflow.benchmark

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
        Text("结果对比", fontSize = 20.sp)
        Text(
            "每个模式会保留最近一次结果。跑完五组后直接比较 B1–B4 的平均电流，再减去 D 的平均电流。",
            style = MaterialTheme.typography.bodySmall,
        )

        listOf(
            PowerBenchmarkService.MODE_D,
            PowerBenchmarkService.MODE_B16_MIC,
            PowerBenchmarkService.MODE_B48_MIC,
            PowerBenchmarkService.MODE_B16_VOICE,
            PowerBenchmarkService.MODE_B48_VOICE,
        ).forEach { mode ->
            BenchmarkResultCard(mode = mode, prefs = prefs)
        }

        Text(
            "正式录音仍固定使用 MediaRecorder，不会跟随这里的测试参数自动切换。你把 D、B1–B4 的结果发给我后，再决定是否修改正式版采样率或 AudioSource。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun BenchmarkResultCard(
    mode: String,
    prefs: SharedPreferences,
) {
    val duration =
        prefs.getLong(
            PowerBenchmarkService.resultKey(
                mode,
                PowerBenchmarkService.FIELD_DURATION_MS,
            ),
            0L,
        )
    if (duration <= 0L) {
        Card {
            Column(Modifier.padding(14.dp)) {
                Text(PowerBenchmarkService.modeDescription(mode))
                Text("尚未测试", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    val cpu =
        prefs.getLong(
            PowerBenchmarkService.resultKey(mode, PowerBenchmarkService.FIELD_CPU_MS),
            0L,
        )
    val bytes =
        prefs.getLong(
            PowerBenchmarkService.resultKey(mode, PowerBenchmarkService.FIELD_OUTPUT_BYTES),
            0L,
        )
    val charge =
        prefs.getLong(
            PowerBenchmarkService.resultKey(
                mode,
                PowerBenchmarkService.FIELD_CHARGE_DELTA_UAH,
            ),
            Long.MIN_VALUE,
        )
    val batteryStart =
        prefs.getInt(
            PowerBenchmarkService.resultKey(
                mode,
                PowerBenchmarkService.FIELD_BATTERY_START,
            ),
            -1,
        )
    val batteryEnd =
        prefs.getInt(
            PowerBenchmarkService.resultKey(
                mode,
                PowerBenchmarkService.FIELD_BATTERY_END,
            ),
            -1,
        )
    val sampleRate =
        prefs.getInt(
            PowerBenchmarkService.resultKey(
                mode,
                PowerBenchmarkService.FIELD_ACTUAL_SAMPLE_RATE,
            ),
            0,
        )
    val channels =
        prefs.getInt(
            PowerBenchmarkService.resultKey(
                mode,
                PowerBenchmarkService.FIELD_ACTUAL_CHANNELS,
            ),
            0,
        )
    val bitrate =
        prefs.getInt(
            PowerBenchmarkService.resultKey(
                mode,
                PowerBenchmarkService.FIELD_ACTUAL_BITRATE,
            ),
            0,
        )
    val mime =
        prefs.getString(
            PowerBenchmarkService.resultKey(
                mode,
                PowerBenchmarkService.FIELD_ACTUAL_MIME,
            ),
            "",
        ).orEmpty()
    val error =
        prefs.getString(
            PowerBenchmarkService.resultKey(mode, PowerBenchmarkService.FIELD_ERROR),
            "",
        ).orEmpty()

    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(PowerBenchmarkService.modeDescription(mode), fontSize = 17.sp)
            Text(
                "时长 " + formatDuration(duration) +
                    " · CPU " + formatDuration(cpu) +
                    String.format(Locale.US, " (%.1f%%)", 100.0 * cpu / duration),
            )

            if (batteryStart >= 0 && batteryEnd >= 0) {
                Text("电量 " + batteryStart + "% → " + batteryEnd + "%")
            }

            if (charge != Long.MIN_VALUE) {
                val consumedUah = (-charge).coerceAtLeast(0L)
                val currentMa =
                    if (duration > 0L) {
                        consumedUah / 1000.0 / (duration / 3_600_000.0)
                    } else {
                        0.0
                    }
                val perMinute =
                    if (duration > 0L) consumedUah * 60_000.0 / duration else 0.0
                Text(
                    String.format(
                        Locale.US,
                        "%,d µAh · %.0f µAh/min · %.1f mA",
                        consumedUah,
                        perMinute,
                        currentMa,
                    ),
                )

                if (mode != PowerBenchmarkService.MODE_D) {
                    val baselineDuration =
                        prefs.getLong(
                            PowerBenchmarkService.resultKey(
                                PowerBenchmarkService.MODE_D,
                                PowerBenchmarkService.FIELD_DURATION_MS,
                            ),
                            0L,
                        )
                    val baselineCharge =
                        prefs.getLong(
                            PowerBenchmarkService.resultKey(
                                PowerBenchmarkService.MODE_D,
                                PowerBenchmarkService.FIELD_CHARGE_DELTA_UAH,
                            ),
                            Long.MIN_VALUE,
                        )
                    if (baselineDuration > 0L && baselineCharge != Long.MIN_VALUE) {
                        val baselineConsumed = (-baselineCharge).coerceAtLeast(0L)
                        val baselineCurrent =
                            baselineConsumed / 1000.0 /
                                (baselineDuration / 3_600_000.0)
                        Text(
                            String.format(
                                Locale.US,
                                "扣除 D 基线后的录音增量：约 %.1f mA",
                                currentMa - baselineCurrent,
                            ),
                        )
                    }
                }
            }

            if (bytes > 0L) {
                Text("输出 " + formatBytes(bytes))
            }

            if (sampleRate > 0 || mime.isNotBlank()) {
                Text(
                    "实际 " + mime.ifBlank { "audio" } + " · " +
                        sampleRate + " Hz · " + channels + " ch" +
                        if (bitrate > 0) " · " + bitrate / 1000 + " kbps" else "",
                )
            }

            if (error.isNotBlank()) {
                Text("错误：" + error, color = MaterialTheme.colorScheme.error)
            }
        }
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
