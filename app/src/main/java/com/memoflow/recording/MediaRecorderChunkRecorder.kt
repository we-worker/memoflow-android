package com.memoflow.recording

import android.media.MediaRecorder
import com.memoflow.domain.AudioChunk
import com.memoflow.domain.ChunkState
import java.io.File

/**
 * Low-power production recorder. Android owns microphone capture and AAC encoding;
 * the app only starts/stops each chunk and persists the resulting M4A.
 */
class MediaRecorderChunkRecorder(
    private val dir: File,
    private val sampleRate: Int = 16_000,
    private val channels: Int = 1,
    private val bitrate: Int = 24_000,
) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startTimeMs: Long = 0L

    fun start() {
        check(recorder == null) { "Recorder already started" }
        dir.mkdirs()

        val output = File(dir, "chunk_" + System.currentTimeMillis() + ".m4a")
        val mediaRecorder =
            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(sampleRate)
                setAudioChannels(channels)
                setAudioEncodingBitRate(bitrate)
                setOutputFile(output.absolutePath)
                prepare()
                start()
            }

        file = output
        recorder = mediaRecorder
        startTimeMs = System.currentTimeMillis()
    }

    /**
     * Stops and finalizes the current M4A. Returns null when MediaRecorder could
     * not finalize a valid file (for example an extremely short recording).
     */
    fun finish(): AudioChunk? {
        val current = recorder ?: return null
        val output = file ?: return null
        recorder = null
        file = null

        var valid = true
        try {
            current.stop()
        } catch (_: RuntimeException) {
            valid = false
        } finally {
            runCatching { current.reset() }
            runCatching { current.release() }
        }

        if (!valid || !output.exists() || output.length() <= 0L) {
            output.delete()
            return null
        }

        val endTimeMs = System.currentTimeMillis()
        return AudioChunk(
            id = output.nameWithoutExtension,
            deviceId = "android-device",
            startTimeUtcMs = startTimeMs,
            endTimeUtcMs = endTimeMs,
            durationMs = (endTimeMs - startTimeMs).coerceAtLeast(0L),
            audioPath = output.absolutePath,
            codec = "aac-lc",
            container = "m4a",
            sampleRate = sampleRate,
            channels = channels,
            bitrate = bitrate,
            fileSize = output.length(),
            checksumSha256 = "",
            state = ChunkState.COMPLETE,
        )
    }

    fun abort() {
        val current = recorder
        val output = file
        recorder = null
        file = null

        current?.runCatching { stop() }
        current?.runCatching { reset() }
        current?.runCatching { release() }
        output?.delete()
    }
}
