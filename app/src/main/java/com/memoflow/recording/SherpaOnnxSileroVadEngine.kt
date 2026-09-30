package com.memoflow.recording

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.memoflow.domain.AudioFrame
import com.memoflow.domain.AudioRange
import com.memoflow.domain.VadEngine

class SherpaOnnxSileroVadEngine(
    context: Context,
    threshold: Float = 0.5f,
    minSilenceDurationSeconds: Float = 0.5f,
    minSpeechDurationSeconds: Float = 0.25f,
    maxSpeechDurationSeconds: Float = 30.0f,
    private val sampleRate: Int = 16_000,
) : VadEngine {
    private val windowSize = 512
    private val window = FloatArray(windowSize)
    private var windowFill = 0

    private val vad =
        Vad(
            assetManager = context.assets,
            config =
                VadModelConfig(
                    sileroVadModelConfig =
                        SileroVadModelConfig(
                            model = MODEL_ASSET,
                            threshold = threshold.coerceIn(0.05f, 0.95f),
                            minSilenceDuration = minSilenceDurationSeconds,
                            minSpeechDuration = minSpeechDurationSeconds,
                            windowSize = windowSize,
                            maxSpeechDuration = maxSpeechDurationSeconds,
                        ),
                    sampleRate = sampleRate,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false,
                ),
        )

    override suspend fun process(frame: AudioFrame): List<AudioRange> {
        require(frame.sampleRate == sampleRate) {
            "Silero VAD expects " + sampleRate + " Hz, got " + frame.sampleRate
        }
        require(frame.channels == 1) {
            "Silero VAD expects mono PCM, got " + frame.channels + " channels"
        }

        val output = mutableListOf<AudioRange>()
        var byteIndex = 0
        while (byteIndex + 1 < frame.pcm.size) {
            val sample =
                ((frame.pcm[byteIndex].toInt() and 0xff) or
                    (frame.pcm[byteIndex + 1].toInt() shl 8))
                    .toShort()
                    .toInt()

            window[windowFill++] = sample / 32768.0f
            byteIndex += 2

            if (windowFill == windowSize) {
                vad.acceptWaveform(window)
                windowFill = 0
                drain(output)
            }
        }
        return output
    }

    override suspend fun flush(): List<AudioRange> {
        val output = mutableListOf<AudioRange>()
        if (windowFill > 0) {
            vad.acceptWaveform(window.copyOf(windowFill))
            windowFill = 0
        }
        vad.flush()
        drain(output)
        return output
    }

    override suspend fun reset() {
        windowFill = 0
        vad.reset()
    }

    override suspend fun close() {
        vad.release()
    }

    private fun drain(output: MutableList<AudioRange>) {
        while (!vad.empty()) {
            val segment = vad.front()
            val startMs = samplesToMs(segment.start.toLong())
            val endMs = samplesToMs(segment.start.toLong() + segment.samples.size)
            if (endMs > startMs) {
                output +=
                    AudioRange(
                        chunkId = "active",
                        startOffsetMs = startMs,
                        endOffsetMs = endMs,
                        type = "speech",
                        confidence = null,
                        modelId = MODEL_ID,
                        modelVersion = MODEL_VERSION,
                    )
            }
            vad.pop()
        }
    }

    private fun samplesToMs(samples: Long): Long =
        (samples * 1000L) / sampleRate

    companion object {
        const val MODEL_ASSET = "silero_vad.onnx"
        const val MODEL_ID = "sherpa-onnx-silero-vad"
        const val MODEL_VERSION = "1.13.8+silero-9e2449e1"
    }
}
