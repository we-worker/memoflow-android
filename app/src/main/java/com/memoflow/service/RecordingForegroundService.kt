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
import com.memoflow.domain.AudioRange
import com.memoflow.recording.AacMediaCodecEncoder
import com.memoflow.recording.AudioRecordSource
import com.memoflow.recording.M4aChunkWriter
import com.memoflow.recording.SherpaOnnxSileroVadEngine
import java.io.File
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
            .setContentText("Silero VAD 正在本机标记语音，原始音频持续保存")
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
        val source = AudioRecordSource()
        var encoder = AacMediaCodecEncoder()
        var writer = M4aChunkWriter(File(filesDir, "audio"))
        val prefs = getSharedPreferences(PREFS_RECORDING, Context.MODE_PRIVATE)
        val vad =
            SherpaOnnxSileroVadEngine(
                context = this,
                threshold = prefs.getFloat(KEY_SILERO_THRESHOLD, DEFAULT_SILERO_THRESHOLD),
            )
        var chunkStartMs = System.currentTimeMillis()
        val ranges = mutableListOf<AudioRange>()

        try {
            source.start()
            encoder.open(SAMPLE_RATE, CHANNELS, BITRATE)
            writer.start()

            while (currentCoroutineContext().isActive) {
                val frame = source.read() ?: break

                ranges +=
                    vad.process(frame).map {
                        it.copy(chunkId = writer.currentId)
                    }

                val encoded = encoder.encode(frame)
                encoder.outputFormat?.let(writer::onFormat)
                encoded.forEach(writer::write)

                if (System.currentTimeMillis() - chunkStartMs >= CHUNK_DURATION_MS) {
                    ranges +=
                        vad.flush().map {
                            it.copy(chunkId = writer.currentId)
                        }

                    finalizeEncoderIntoWriter(encoder, writer)
                    finishChunk(writer, ranges)

                    ranges.clear()
                    vad.reset()

                    encoder = AacMediaCodecEncoder()
                    encoder.open(SAMPLE_RATE, CHANNELS, BITRATE)
                    writer = M4aChunkWriter(File(filesDir, "audio")).also { it.start() }
                    chunkStartMs = System.currentTimeMillis()
                }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    ranges +=
                        vad.flush().map {
                            it.copy(chunkId = writer.currentId)
                        }
                }
                runCatching { finalizeEncoderIntoWriter(encoder, writer) }
                runCatching { finishChunk(writer, ranges) }
                runCatching { vad.close() }
                runCatching { encoder.close() }
                runCatching { source.stop() }
            }
        }
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

    private suspend fun finishChunk(
        writer: M4aChunkWriter,
        ranges: List<AudioRange>,
    ) {
        writer.finish()?.let { chunk ->
            db.chunks().upsert(chunk.toEntity())
            if (ranges.isNotEmpty()) {
                db.chunks().insertRanges(ranges.map { it.toEntity(chunk.id) })
            }
        }
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
        const val KEY_SILERO_THRESHOLD = "silero_vad_threshold"
        const val DEFAULT_SILERO_THRESHOLD = 0.5f

        private const val NOTIFICATION_ID = 7
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITRATE = 24000
        private const val CHUNK_DURATION_MS = 10 * 60 * 1000L
    }
}
