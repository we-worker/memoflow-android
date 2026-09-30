package com.memoflow.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.memoflow.data.ChunkSyncWorker
import com.memoflow.data.MemoDatabase
import com.memoflow.data.toEntity
import com.memoflow.domain.AudioRange
import com.memoflow.recording.AacMediaCodecEncoder
import com.memoflow.recording.AudioRecordSource
import com.memoflow.recording.EnergyVadEngine
import com.memoflow.recording.M4aChunkWriter
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

        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MemoFlow recording")
                .setContentText("Microphone capture is active")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build(),
        )

        job =
            scope.launch {
                runRecordingLoop()
            }.also { runningJob ->
                runningJob.invokeOnCompletion {
                    job = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
    }

    private suspend fun runRecordingLoop() {
        val source = AudioRecordSource()
        var encoder = AacMediaCodecEncoder()
        var writer = M4aChunkWriter(File(filesDir, "audio"))
        var vad = EnergyVadEngine()
        var chunkStartMs = System.currentTimeMillis()
        val ranges = mutableListOf<AudioRange>()

        try {
            source.start()
            encoder.open(SAMPLE_RATE, CHANNELS, BITRATE)
            writer.start()

            while (currentCoroutineContext().isActive) {
                val frame = source.read() ?: break

                ranges += vad.process(frame).map { it.copy(chunkId = writer.currentId) }

                val encoded = encoder.encode(frame)
                encoder.outputFormat?.let(writer::onFormat)
                encoded.forEach(writer::write)

                if (System.currentTimeMillis() - chunkStartMs >= CHUNK_DURATION_MS) {
                    finalizeEncoderIntoWriter(encoder, writer)
                    finishChunk(writer, ranges)

                    ranges.clear()
                    encoder = AacMediaCodecEncoder()
                    encoder.open(SAMPLE_RATE, CHANNELS, BITRATE)
                    writer = M4aChunkWriter(File(filesDir, "audio")).also { it.start() }
                    vad = EnergyVadEngine()
                    chunkStartMs = System.currentTimeMillis()
                }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    finalizeEncoderIntoWriter(encoder, writer)
                }
                runCatching {
                    finishChunk(writer, ranges)
                }
                runCatching {
                    encoder.close()
                }
                runCatching {
                    source.stop()
                }
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
                        "Recording",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
        }
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val CHANNEL_ID = "recording"

        private const val NOTIFICATION_ID = 7
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITRATE = 24000
        private const val CHUNK_DURATION_MS = 10 * 60 * 1000L
    }
}
