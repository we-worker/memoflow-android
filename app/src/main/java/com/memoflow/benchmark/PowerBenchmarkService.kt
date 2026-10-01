package com.memoflow.benchmark

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.memoflow.recording.AacMediaCodecEncoder
import com.memoflow.recording.AudioRecordSource
import com.memoflow.recording.M4aChunkWriter
import com.memoflow.recording.MediaRecorderChunkRecorder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation

class PowerBenchmarkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private var mode: String = ""
    private var startElapsedMs = 0L
    private var startCpuMs = 0L
    private var startChargeUah = Long.MIN_VALUE
    private var startBatteryPercent = -1

    @Volatile private var outputBytes = 0L
    @Volatile private var mmapUsed = false
    @Volatile private var nativeSampleRate = 0
    @Volatile private var framesRead = 0L
    @Volatile private var errorMessage = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_A -> startTest(MODE_A)
            ACTION_START_B -> startTest(MODE_B)
            ACTION_START_C -> startTest(MODE_C)
            ACTION_STOP -> stopTest()
        }
        return START_NOT_STICKY
    }

    private fun startTest(requestedMode: String) {
        if (job?.isActive == true) return

        mode = requestedMode
        outputBytes = 0L
        mmapUsed = false
        nativeSampleRate = 0
        framesRead = 0L
        errorMessage = ""

        startElapsedMs = android.os.SystemClock.elapsedRealtime()
        startCpuMs = Process.getElapsedCpuTime()
        startChargeUah = batteryChargeCounter()
        startBatteryPercent = batteryPercent()

        prefs().edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_MODE, mode)
            .putLong(KEY_START_ELAPSED_MS, startElapsedMs)
            .apply()

        startForeground(NOTIFICATION_ID, buildNotification())

        job =
            scope.launch {
                try {
                    when (mode) {
                        MODE_A -> runLegacyAudioRecord()
                        MODE_B -> runMediaRecorder()
                        MODE_C -> runAAudioProbe()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    errorMessage = error.message ?: error.javaClass.simpleName
                }
            }.also { running ->
                running.invokeOnCompletion {
                    saveResult()
                    prefs().edit().putBoolean(KEY_ACTIVE, false).apply()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    job = null
                }
            }
    }

    private suspend fun runLegacyAudioRecord() {
        val dir = File(cacheDir, "power_benchmark/a").apply {
            deleteRecursively()
            mkdirs()
        }
        val source = AudioRecordSource()
        val encoder = AacMediaCodecEncoder()
        val writer = M4aChunkWriter(dir)

        source.start()
        encoder.open(SAMPLE_RATE, CHANNELS, BITRATE)
        writer.start()

        try {
            while (currentCoroutineContext().isActive) {
                val frame = source.read() ?: break
                val encoded = encoder.encode(frame)
                encoder.outputFormat?.let(writer::onFormat)
                encoded.forEach(writer::write)
            }
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    val tail = encoder.flush()
                    encoder.outputFormat?.let(writer::onFormat)
                    tail.forEach(writer::write)
                }
                runCatching { encoder.close() }
                runCatching { source.stop() }
                val chunk = runCatching { writer.finish() }.getOrNull()
                outputBytes = chunk?.fileSize ?: 0L
            }
        }
    }

    private suspend fun runMediaRecorder() {
        val dir = File(cacheDir, "power_benchmark/b").apply {
            deleteRecursively()
            mkdirs()
        }
        val recorder =
            MediaRecorderChunkRecorder(
                dir = dir,
                sampleRate = SAMPLE_RATE,
                channels = CHANNELS,
                bitrate = BITRATE,
            )
        recorder.start()
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                val chunk = runCatching { recorder.finish() }.getOrNull()
                outputBytes = chunk?.fileSize ?: 0L
            }
        }
    }

    private suspend fun runAAudioProbe() {
        val probe = AAudioPowerProbe()
        val handle = probe.start()
        require(handle != 0L) { "AAudio input stream open failed" }

        mmapUsed = probe.isMMapUsed(handle)
        nativeSampleRate = probe.sampleRate(handle)

        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                framesRead = probe.framesRead(handle)
                probe.stop(handle)
            }
        }
    }

    private fun stopTest() {
        job?.cancel()
    }

    private fun saveResult() {
        val endElapsed = android.os.SystemClock.elapsedRealtime()
        val endCpu = Process.getElapsedCpuTime()
        val endCharge = batteryChargeCounter()
        val endBattery = batteryPercent()

        val chargeDelta =
            if (startChargeUah != Long.MIN_VALUE && endCharge != Long.MIN_VALUE) {
                endCharge - startChargeUah
            } else {
                Long.MIN_VALUE
            }

        prefs().edit()
            .putString(KEY_LAST_MODE, mode)
            .putLong(KEY_LAST_DURATION_MS, (endElapsed - startElapsedMs).coerceAtLeast(0L))
            .putLong(KEY_LAST_CPU_MS, (endCpu - startCpuMs).coerceAtLeast(0L))
            .putLong(KEY_LAST_OUTPUT_BYTES, outputBytes)
            .putLong(KEY_LAST_CHARGE_DELTA_UAH, chargeDelta)
            .putInt(KEY_LAST_BATTERY_START, startBatteryPercent)
            .putInt(KEY_LAST_BATTERY_END, endBattery)
            .putBoolean(KEY_LAST_MMAP_USED, mmapUsed)
            .putInt(KEY_LAST_NATIVE_SAMPLE_RATE, nativeSampleRate)
            .putLong(KEY_LAST_FRAMES_READ, framesRead)
            .putString(KEY_LAST_ERROR, errorMessage)
            .apply()
    }

    private fun batteryChargeCounter(): Long {
        val manager = getSystemService(BatteryManager::class.java) ?: return Long.MIN_VALUE
        val value = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return if (value == Long.MIN_VALUE) Long.MIN_VALUE else value
    }

    private fun batteryPercent(): Int {
        val battery =
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return -1
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (100 * level / scale) else -1
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MemoFlow A/B/C 功耗测试")
            .setContentText(modeDescription(mode))
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "停止测试",
                PendingIntent.getService(
                    this,
                    91,
                    Intent(this, PowerBenchmarkService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "功耗测试",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS = "power_benchmark"
        const val ACTION_START_A = "benchmark_start_a"
        const val ACTION_START_B = "benchmark_start_b"
        const val ACTION_START_C = "benchmark_start_c"
        const val ACTION_STOP = "benchmark_stop"

        const val MODE_A = "A"
        const val MODE_B = "B"
        const val MODE_C = "C"

        const val KEY_ACTIVE = "active"
        const val KEY_MODE = "mode"
        const val KEY_START_ELAPSED_MS = "start_elapsed_ms"
        const val KEY_LAST_MODE = "last_mode"
        const val KEY_LAST_DURATION_MS = "last_duration_ms"
        const val KEY_LAST_CPU_MS = "last_cpu_ms"
        const val KEY_LAST_OUTPUT_BYTES = "last_output_bytes"
        const val KEY_LAST_CHARGE_DELTA_UAH = "last_charge_delta_uah"
        const val KEY_LAST_BATTERY_START = "last_battery_start"
        const val KEY_LAST_BATTERY_END = "last_battery_end"
        const val KEY_LAST_MMAP_USED = "last_mmap_used"
        const val KEY_LAST_NATIVE_SAMPLE_RATE = "last_native_sample_rate"
        const val KEY_LAST_FRAMES_READ = "last_frames_read"
        const val KEY_LAST_ERROR = "last_error"

        private const val CHANNEL_ID = "power_benchmark"
        private const val NOTIFICATION_ID = 71
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITRATE = 24_000

        fun modeDescription(mode: String): String =
            when (mode) {
                MODE_A -> "A · AudioRecord + MediaCodec + M4A"
                MODE_B -> "B · MediaRecorder 直接 AAC/M4A"
                MODE_C -> "C · AAudio POWER_SAVING / MMAP 探针"
                else -> "未开始"
            }
    }
}
