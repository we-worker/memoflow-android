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
            if (granted) {
                pendingAction?.let(::startBenchmarkService)
            }
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
    val lastCharge = prefs.getLong(
        PowerBenchmarkService.KEY_LAST_CHARGE_DELTA_UAH,
        Long.MIN_VALUE,
    )
    val lastBatteryStart = prefs.getInt(PowerBenchmarkService.KEY_LAST_BATTERY_START, -1)
    val lastBatteryEnd = prefs.getInt(PowerBenchmarkService.KEY_LAST_BATTERY_END, -1)
    val lastMmap = prefs.getBoolean(PowerBenchmarkService.KEY_LAST_MMAP_USED, false)
    val lastSampleRate = prefs.getInt(PowerBenchmarkService.KEY_LAST_NATIVE_SAMPLE_RATE, 0)
    val lastFrames = prefs.getLong(PowerBenchmarkService.KEY_LAST_FRAMES_READ, 0L)
    val lastError = prefs.getString(PowerBenchmarkService.KEY_LAST_ERROR, "").orEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("A/B/C 录音功耗测试", fontSize = 26.sp)
            OutlinedButton(onClick = onBack) { Text("返回") }
        }

        Text(
            "建议三种方案分别在相同电量、相同网络、屏幕关闭条件下录 30–60 分钟。测试服务会保持前台通知，停止后记录电量计、进程 CPU 时间和输出大小。",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (productionRecording) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    "正式录音正在运行，请先结束正式录音再做 A/B/C 测试，避免两个麦克风会话互相干扰。",
                    Modifier.padding(14.dp),
                )
            }
        }

        BenchmarkCard(
            title = "A · 旧链路",
            description = "AudioRecord → App PCM → MediaCodec AAC → MediaMuxer M4A。用于作为旧版本基线。",
            enabled = !active && !productionRecording,
            onClick = { onStart(PowerBenchmarkService.ACTION_START_A) },
        )
        BenchmarkCard(
            title = "B · MediaRecorder",
            description = "Android framework 直接录 AAC/M4A。正式版现在使用的低功耗路径。",
            enabled = !active && !productionRecording,
            onClick = { onStart(PowerBenchmarkService.ACTION_START_B) },
        )
        BenchmarkCard(
            title = "C · AAudio / MMAP 探针",
            description = "AAudio POWER_SAVING 大块阻塞读取，只测试输入数据路径，不做 AAC/文件 I/O；会记录设备是否真的进入 MMAP。",
            enabled = !active && !productionRecording,
            onClick = { onStart(PowerBenchmarkService.ACTION_START_C) },
        )

        if (active) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "正在测试 " + PowerBenchmarkService.modeDescription(activeMode),
                        fontSize = 18.sp,
                    )
                    val elapsed =
                        if (startElapsed > 0L) (nowElapsed - startElapsed).coerceAtLeast(0L) else 0L
                    Text("已运行 " + formatDuration(elapsed))
                    Button(onClick = onStop) { Text("停止并保存结果") }
                    Text("可以锁屏继续测试，之后从通知或这里停止。")
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
                    if (lastBatteryStart >= 0 && lastBatteryEnd >= 0) {
                        Text("系统电量：" + lastBatteryStart + "% → " + lastBatteryEnd + "%")
                    }
                    if (lastCharge != Long.MIN_VALUE) {
                        val consumed = -lastCharge
                        Text(
                            "Charge counter 变化：" +
                                String.format(Locale.US, "%,d µAh", lastCharge) +
                                if (consumed > 0) "（约消耗 " + consumed + " µAh）" else "",
                        )
                    } else {
                        Text("本机未提供可用的 charge counter。")
                    }
                    if (lastBytes > 0L) {
                        Text("输出文件：" + formatBytes(lastBytes))
                    }
                    if (lastMode == PowerBenchmarkService.MODE_C) {
                        Text("AAudio MMAP：" + if (lastMmap) "是" else "否 / 回落 legacy path")
                        Text("AAudio 实际采样率：" + lastSampleRate + " Hz")
                        Text("读取帧数：" + lastFrames)
                    }
                    if (lastError.isNotBlank()) {
                        Text("错误：" + lastError, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Text(
            "注意：C 只用于判断普通 APK 是否能拿到低功耗 AAudio/MMAP 输入路径，不和 A/B 的完整 AAC 写盘负载完全等价。正式版本固定使用 B，不给普通用户暴露录音模式切换。",
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
