package com.memoflow.benchmark

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.memoflow.recording.MediaRecorderChunkRecorder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PowerBenchmarkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private var mode: String = ""
    private var startElapsedMs = 0L
    private var startCpuMs = 0L
    private var startChargeUah = Long.MIN_VALUE
    private var startBatteryPercent = -1

    @Volatile private var outputBytes = 0L
    @Volatile private var actualSampleRate = 0
    @Volatile private var actualChannels = 0
    @Volatile private var actualBitrate = 0
    @Volatile private var actualMime = ""
    @Volatile private var errorMessage = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_D -> startTest(MODE_D)
            ACTION_START_B16_MIC -> startTest(MODE_B16_MIC)
            ACTION_START_B48_MIC -> startTest(MODE_B48_MIC)
            ACTION_START_B16_VOICE -> startTest(MODE_B16_VOICE)
            ACTION_START_B48_VOICE -> startTest(MODE_B48_VOICE)
            ACTION_STOP -> stopTest()
        }
        return START_NOT_STICKY
    }

    private fun startTest(requestedMode: String) {
        if (job?.isActive == true) return

        mode = requestedMode
        outputBytes = 0L
        actualSampleRate = 0
        actualChannels = 0
        actualBitrate = 0
        actualMime = ""
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
                        MODE_D -> runIdleBaseline()
                        MODE_B16_MIC ->
                            runMediaRecorder(
                                sampleRate = 16_000,
                                audioSource = MediaRecorder.AudioSource.MIC,
                            )
                        MODE_B48_MIC ->
                            runMediaRecorder(
                                sampleRate = 48_000,
                                audioSource = MediaRecorder.AudioSource.MIC,
                            )
                        MODE_B16_VOICE ->
                            runMediaRecorder(
                                sampleRate = 16_000,
                                audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            )
                        MODE_B48_VOICE ->
                            runMediaRecorder(
                                sampleRate = 48_000,
                                audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            )
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

    private suspend fun runIdleBaseline() {
        awaitCancellation()
    }

    private suspend fun runMediaRecorder(
        sampleRate: Int,
        audioSource: Int,
    ) {
        val dir =
            File(cacheDir, "power_benchmark/" + mode.lowercase()).apply {
                deleteRecursively()
                mkdirs()
            }

        val recorder =
            MediaRecorderChunkRecorder(
                dir = dir,
                sampleRate = sampleRate,
                channels = CHANNELS,
                bitrate = BITRATE,
                audioSource = audioSource,
            )
        recorder.start()

        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                val chunk = runCatching { recorder.finish() }.getOrNull()
                outputBytes = chunk?.fileSize ?: 0L
                chunk?.audioPath?.let { inspectRecordedFile(File(it)) }
            }
        }
    }

    private fun inspectRecordedFile(file: File) {
        if (!file.exists()) return
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("audio/")) continue

                actualMime = mime
                actualSampleRate =
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else {
                        0
                    }
                actualChannels =
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else {
                        0
                    }
                actualBitrate =
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        format.getInteger(MediaFormat.KEY_BIT_RATE)
                    } else {
                        0
                    }
                break
            }
        } catch (_: Throwable) {
            // The power result is still useful even if metadata inspection fails.
        } finally {
            runCatching { extractor.release() }
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
            .putInt(KEY_LAST_ACTUAL_SAMPLE_RATE, actualSampleRate)
            .putInt(KEY_LAST_ACTUAL_CHANNELS, actualChannels)
            .putInt(KEY_LAST_ACTUAL_BITRATE, actualBitrate)
            .putString(KEY_LAST_ACTUAL_MIME, actualMime)
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
            .setContentTitle("MemoFlow 功耗参数测试")
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

        const val ACTION_START_D = "benchmark_start_d"
        const val ACTION_START_B16_MIC = "benchmark_start_b16_mic"
        const val ACTION_START_B48_MIC = "benchmark_start_b48_mic"
        const val ACTION_START_B16_VOICE = "benchmark_start_b16_voice"
        const val ACTION_START_B48_VOICE = "benchmark_start_b48_voice"
        const val ACTION_STOP = "benchmark_stop"

        const val MODE_D = "D"
        const val MODE_B16_MIC = "B16_MIC"
        const val MODE_B48_MIC = "B48_MIC"
        const val MODE_B16_VOICE = "B16_VOICE"
        const val MODE_B48_VOICE = "B48_VOICE"

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
        const val KEY_LAST_ACTUAL_SAMPLE_RATE = "last_actual_sample_rate"
        const val KEY_LAST_ACTUAL_CHANNELS = "last_actual_channels"
        const val KEY_LAST_ACTUAL_BITRATE = "last_actual_bitrate"
        const val KEY_LAST_ACTUAL_MIME = "last_actual_mime"
        const val KEY_LAST_ERROR = "last_error"

        private const val CHANNEL_ID = "power_benchmark"
        private const val NOTIFICATION_ID = 71
        private const val CHANNELS = 1
        private const val BITRATE = 24_000

        fun modeDescription(mode: String): String =
            when (mode) {
                MODE_D -> "D · 空闲基线（不打开麦克风）"
                MODE_B16_MIC -> "B1 · MediaRecorder 16 kHz / MIC"
                MODE_B48_MIC -> "B2 · MediaRecorder 48 kHz / MIC"
                MODE_B16_VOICE -> "B3 · MediaRecorder 16 kHz / VOICE_RECOGNITION"
                MODE_B48_VOICE -> "B4 · MediaRecorder 48 kHz / VOICE_RECOGNITION"
                // Keep labels for previously saved benchmark results.
                "A" -> "A · 旧 AudioRecord + MediaCodec 基线"
                "B" -> "B · 旧 MediaRecorder 16 kHz / MIC"
                "C" -> "C · 旧 AAudio/MMAP 探针"
                else -> "未开始"
            }
    }
}
