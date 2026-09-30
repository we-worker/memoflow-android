package com.memoflow.recording

import com.memoflow.domain.AudioFrame
import com.memoflow.domain.AudioRange
import com.memoflow.domain.VadEngine
import kotlin.math.sqrt

class EnergyVadEngine(
    private val threshold: Double = 0.012,
) : VadEngine {
    private var baseTimestampNs: Long? = null
    private var speech = false
    private var speechStartMs = 0L

    override suspend fun process(frame: AudioFrame): List<AudioRange> {
        val baseNs = baseTimestampNs ?: frame.timestampNs.also { baseTimestampNs = it }
        val nowMs = ((frame.timestampNs - baseNs) / 1_000_000L).coerceAtLeast(0L)

        var squaredSum = 0.0
        var sampleCount = 0
        var index = 0
        while (index + 1 < frame.pcm.size) {
            val raw =
                ((frame.pcm[index].toInt() and 0xff) or
                    (frame.pcm[index + 1].toInt() shl 8))
                    .toShort()
                    .toInt()
            val normalized = raw / 32768.0
            squaredSum += normalized * normalized
            sampleCount++
            index += 2
        }

        val rms = if (sampleCount == 0) 0.0 else sqrt(squaredSum / sampleCount)
        val output = mutableListOf<AudioRange>()

        if (rms >= threshold && !speech) {
            speech = true
            speechStartMs = nowMs
        } else if (rms < threshold && speech) {
            speech = false
            output +=
                AudioRange(
                    chunkId = "active",
                    startOffsetMs = speechStartMs,
                    endOffsetMs = nowMs,
                    confidence = 1.0f,
                    modelId = "energy-vad",
                    modelVersion = "1",
                )
        }

        return output
    }

    override suspend fun reset() {
        baseTimestampNs = null
        speech = false
        speechStartMs = 0L
    }
}
