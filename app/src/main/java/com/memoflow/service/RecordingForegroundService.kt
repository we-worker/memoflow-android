package com.memoflow.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.memoflow.MainActivity
import com.memoflow.data.ChunkSyncWorker
import com.memoflow.data.MemoDatabase
import com.memoflow.data.toEntity
import com.memoflow.processing.AudioPostProcessWorker
import com.memoflow.recording.MediaRecorderChunkRecorder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private lateinit var db: MemoDatabase

    override fun onCreate() {
        super.onCreate()
        db = MemoDatabase.get(this)
        createChannel()
        ChunkSyncWorker.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> startRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (job?.isActive == true) return

        getSharedPreferences(PREFS_RECORDING, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RECORDING_ACTIVE, true)
            .putLong(KEY_RECORDING_STARTED_AT, System.currentTimeMillis())
            .apply()

        startForeground(NOTIFICATION_ID, buildNotification())

        job =
            scope.launch { runRecordingLoop() }
                .also { runningJob ->
                    runningJob.invokeOnCompletion {
                        getSharedPreferences(PREFS_RECORDING, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_RECORDING_ACTIVE, false)
                            .apply()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("回声正在记录")
            .setContentText("低功耗录音中；VAD 在充电或结束记录后处理")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    10,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "结束",
                PendingIntent.getService(
                    this,
                    11,
                    Intent(this, RecordingForegroundService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private suspend fun runRecordingLoop() {
        while (currentCoroutineContext().isActive) {
            val recorder =
                MediaRecorderChunkRecorder(
                    dir = File(filesDir, "audio"),
                    sampleRate = SAMPLE_RATE,
                    channels = CHANNELS,
                    bitrate = BITRATE,
                )

            var cancelled = false
            try {
                recorder.start()
                delay(currentChunkDurationMs())
            } catch (_: CancellationException) {
                // User stopped recording. Finalize the partial chunk below, but do
                // not trigger VAD/waveform here; those run on charging or detail open.
                cancelled = true
            } catch (_: Throwable) {
                recorder.abort()
                break
            }

            val chunk =
                withContext(NonCancellable) {
                    runCatching { recorder.finish() }.getOrNull()
                }

            if (chunk != null) {
                withContext(NonCancellable) {
                    db.chunks().upsert(chunk.toEntity())
                    AudioPostProcessWorker.enqueueDeferred(
                        this@RecordingForegroundService,
                        chunk.id,
                    )
                }
            }

            if (cancelled || !currentCoroutineContext().isActive) break
        }
    }

    private fun currentChunkDurationMs(): Long {
        val minutes =
            getSharedPreferences(PREFS_RECORDING, Context.MODE_PRIVATE)
                .getInt(KEY_CHUNK_DURATION_MINUTES, DEFAULT_CHUNK_DURATION_MINUTES)
                .coerceIn(MIN_CHUNK_DURATION_MINUTES, MAX_CHUNK_DURATION_MINUTES)
        return minutes * 60_000L
    }

    private suspend fun finalizeEncoderIntoWriter(
        encoder: AacMediaCodecEncoder,
        writer: M4aChunkWriter,
    ) {
        val tail = encoder.flush()
        encoder.outputFormat?.let(writer::onFormat)
        tail.forEach(writer::write)
        encoder.close()
    }

    private suspend fun finishChunkAndDeferPostProcess(writer: M4aChunkWriter): String? {
        val chunk = writer.finish() ?: return null
        db.chunks().upsert(chunk.toEntity())
        AudioPostProcessWorker.enqueueDeferred(this, chunk.id)
        return chunk.id
    }

    private fun stopRecording() {
        job?.cancel()
    }

    override fun onDestroy() {
        stopRecording()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "持续录音",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
        }
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val CHANNEL_ID = "recording"

        const val PREFS_RECORDING = "recording"
        const val KEY_RECORDING_ACTIVE = "recording_active"
        const val KEY_RECORDING_STARTED_AT = "recording_started_at"
        const val KEY_VAD_ENGINE = "vad_engine"
        const val DEFAULT_VAD_ENGINE = "SILERO"
        const val KEY_SILERO_THRESHOLD = "silero_vad_threshold"
        const val DEFAULT_SILERO_THRESHOLD = 0.5f
        const val KEY_FIRERED_THRESHOLD = "firered_vad_threshold"
        const val DEFAULT_FIRERED_THRESHOLD = 0.4f
        const val KEY_VAD_SEGMENT_GAP_MINUTES = "vad_segment_gap_minutes"
        const val DEFAULT_VAD_SEGMENT_GAP_MINUTES = 5
        const val KEY_CHUNK_DURATION_MINUTES = "chunk_duration_minutes"
        const val DEFAULT_CHUNK_DURATION_MINUTES = 10
        const val MIN_CHUNK_DURATION_MINUTES = 5
        const val MAX_CHUNK_DURATION_MINUTES = 60

        private const val NOTIFICATION_ID = 7
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITRATE = 24_000
    }
}
