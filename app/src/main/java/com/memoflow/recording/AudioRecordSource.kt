package com.memoflow.recording

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.memoflow.domain.AudioFrame
import com.memoflow.domain.AudioSource

class AudioRecordSource(
    private val sampleRate: Int = 16_000,
    private val frameSamples: Int = 3_200,
) : AudioSource {
    private var recorder: AudioRecord? = null
    private val reusableBuffer = ByteArray(frameSamples * BYTES_PER_SAMPLE)

    override suspend fun start() {
        val minBuffer =
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        recorder =
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, reusableBuffer.size * 4),
            ).also { it.startRecording() }
    }

    override suspend fun read(): AudioFrame? {
        val audioRecord = recorder ?: return null

        // The recording service already owns a long-lived Dispatchers.IO coroutine.
        // Fill one reusable 200 ms buffer here instead of allocating a ByteArray and
        // switching dispatchers every 100 ms.
        var offset = 0
        while (offset < reusableBuffer.size) {
            val read =
                audioRecord.read(
                    reusableBuffer,
                    offset,
                    reusableBuffer.size - offset,
                    AudioRecord.READ_BLOCKING,
                )
            if (read <= 0) return null
            offset += read
        }

        return AudioFrame(
            pcm = reusableBuffer,
            timestampNs = System.nanoTime(),
            sampleRate = sampleRate,
            channels = 1,
        )
    }

    override suspend fun stop() {
        val current = recorder
        recorder = null
        current?.runCatching { stop() }
        current?.runCatching { release() }
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2
    }
}
