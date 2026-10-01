package com.memoflow.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.memoflow.domain.AudioChunk
import com.memoflow.domain.ChunkState
import com.memoflow.domain.EncodedAudioFrame
import java.io.File

class M4aChunkWriter(
    private val dir: File,
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
    private val bitrate: Int = 24000,
) {
    var currentId: String = ""
        private set

    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var file: File? = null
    private var startTimeMs = 0L

    fun start() {
        dir.mkdirs()
        file = File(dir, "chunk_" + System.currentTimeMillis() + ".part")
        currentId = file!!.nameWithoutExtension
        muxer = MediaMuxer(file!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        trackIndex = -1
        muxerStarted = false
        startTimeMs = System.currentTimeMillis()
    }

    fun onFormat(format: MediaFormat) {
        val muxer = muxer ?: return
        if (!muxerStarted) {
            trackIndex = muxer.addTrack(format)
            muxer.start()
            muxerStarted = true
        }
    }

    fun write(frame: EncodedAudioFrame) {
        val muxer = muxer ?: return
        if (!muxerStarted || trackIndex < 0 || frame.data.isEmpty()) return

        val info =
            MediaCodec.BufferInfo().apply {
                set(0, frame.data.size, frame.presentationTimeUs, frame.flags)
            }
        muxer.writeSampleData(trackIndex, java.nio.ByteBuffer.wrap(frame.data), info)
    }

    fun finish(): AudioChunk? {
        val muxer = muxer ?: return null
        val partFile = file ?: return null

        var valid = muxerStarted && trackIndex >= 0
        try {
            if (muxerStarted) muxer.stop()
        } catch (_: Exception) {
            valid = false
        } finally {
            runCatching { muxer.release() }
            this.muxer = null
        }

        if (!valid) {
            partFile.delete()
            return null
        }

        val outputFile = File(partFile.parentFile, partFile.name.removeSuffix(".part") + ".m4a")
        if (!partFile.renameTo(outputFile) || !outputFile.exists() || outputFile.length() <= 0L) {
            partFile.delete()
            outputFile.delete()
            return null
        }

        val endTimeMs = System.currentTimeMillis()
        return AudioChunk(
            id = outputFile.name.substringBefore('.'),
            deviceId = "android-device",
            startTimeUtcMs = startTimeMs,
            endTimeUtcMs = endTimeMs,
            durationMs = endTimeMs - startTimeMs,
            audioPath = outputFile.absolutePath,
            codec = "aac-lc",
            container = "m4a",
            sampleRate = sampleRate,
            channels = channels,
            bitrate = bitrate,
            fileSize = outputFile.length(),
            checksumSha256 = "",
            state = ChunkState.COMPLETE,
        )
    }
}
