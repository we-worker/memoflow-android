package com.memoflow.vad

import android.content.Context
import com.memoflow.domain.AudioFrame
import com.memoflow.domain.AudioRange
import com.memoflow.domain.VadEngine
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

class FireRedVadEngine(
    context: Context,
    private val backend: VadBackend,
    private val threshold: Float,
) : VadEngine {
    private val appContext = context.applicationContext
    private val pcm = ByteArrayOutputStream()
    private val modelDir = FireRedVadAssets.ensureInstalled(appContext, backend)

    init {
        require(backend != VadBackend.SILERO)
        require(threshold in 0.01f..0.99f)
    }

    override suspend fun process(frame: AudioFrame): List<AudioRange> {
        require(frame.sampleRate == SAMPLE_RATE) {
            "FireRedVAD expects 16 kHz PCM"
        }
        require(frame.channels == 1) {
            "FireRedVAD expects mono PCM"
        }
        pcm.write(frame.pcm)
        return emptyList()
    }

    override suspend fun flush(): List<AudioRange> {
        val bytes = pcm.toByteArray()
        pcm.reset()
        if (bytes.size < 800) return emptyList()

        val mode =
            when (backend) {
                VadBackend.FIRERED_NON_STREAM -> 0
                VadBackend.FIRERED_STREAM -> 1
                VadBackend.SILERO -> error("Silero does not use FireRed native runtime")
            }

        val probabilities =
            FireRedVadNative.detect(
                mode = mode,
                modelDir = modelDir.absolutePath,
                pcm16le = bytes,
            )

        return probabilitiesToRanges(probabilities)
    }

    override suspend fun reset() {
        pcm.reset()
    }

    override suspend fun close() {
        pcm.reset()
    }

    private fun probabilitiesToRanges(probabilities: FloatArray): List<AudioRange> {
        if (probabilities.isEmpty()) return emptyList()

        val smoothed = movingAverage(probabilities, SMOOTH_WINDOW)
        val minSpeechFrames =
            if (backend == VadBackend.FIRERED_STREAM) 8 else 20
        val minSilenceFrames =
            if (backend == VadBackend.FIRERED_STREAM) 20 else 10
        val padStartFrames =
            if (backend == VadBackend.FIRERED_STREAM) 5 else 0

        val output = mutableListOf<AudioRange>()
        var inSpeech = false
        var speechRun = 0
        var silenceRun = 0
        var startFrame = 0

        fun emit(endExclusive: Int) {
            val safeStart = max(0, startFrame - padStartFrames)
            val safeEnd = endExclusive.coerceAtLeast(safeStart + 1)
            var confidenceSum = 0f
            var confidenceCount = 0
            for (index in safeStart until minOf(safeEnd, smoothed.size)) {
                confidenceSum += smoothed[index]
                confidenceCount++
            }
            output +=
                AudioRange(
                    chunkId = "active",
                    startOffsetMs = safeStart * FRAME_MS,
                    endOffsetMs = safeEnd * FRAME_MS,
                    type = "speech",
                    confidence =
                        if (confidenceCount > 0) {
                            confidenceSum / confidenceCount
                        } else {
                            null
                        },
                    modelId =
                        if (backend == VadBackend.FIRERED_STREAM) {
                            "firered-stream-vad"
                        } else {
                            "firered-vad"
                        },
                    modelVersion = MODEL_VERSION,
                )
        }

        for (index in smoothed.indices) {
            val speech = smoothed[index] >= threshold

            if (!inSpeech) {
                if (speech) {
                    speechRun++
                    if (speechRun >= minSpeechFrames) {
                        startFrame = index - speechRun + 1
                        inSpeech = true
                        silenceRun = 0
                    }
                } else {
                    speechRun = 0
                }
            } else {
                if (speech) {
                    silenceRun = 0
                } else {
                    silenceRun++
                    if (silenceRun >= minSilenceFrames) {
                        emit(index - silenceRun + 1)
                        inSpeech = false
                        speechRun = 0
                        silenceRun = 0
                    }
                }
            }
        }

        if (inSpeech) {
            emit(smoothed.size)
        }

        return output
    }

    private fun movingAverage(
        input: FloatArray,
        window: Int,
    ): FloatArray {
        if (window <= 1 || input.size <= 1) return input.copyOf()

        val out = FloatArray(input.size)
        var sum = 0f
        for (index in input.indices) {
            sum += input[index]
            if (index >= window) sum -= input[index - window]
            val count = minOf(index + 1, window)
            out[index] = sum / count
        }
        return out
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 10L
        private const val SMOOTH_WINDOW = 5
        private const val MODEL_VERSION = "FireRedVAD-2026.03-NCNN"
    }
}

internal object FireRedVadNative {
    init {
        System.loadLibrary("memoflow_firered")
    }

    external fun detect(
        mode: Int,
        modelDir: String,
        pcm16le: ByteArray,
    ): FloatArray
}

private object FireRedVadAssets {
    private val nonStreamFiles =
        listOf(
            "firered_vad_non_stream.ncnn.param",
            "firered_vad_non_stream.ncnn.bin",
            "cmvn_means.bin",
            "cmvn_istd.bin",
        )

    private val streamFiles =
        listOf(
            "firered_vad_packed_cache_stream.ncnn.param",
            "firered_vad_packed_cache_stream.ncnn.bin",
            "cmvn_means_stream.bin",
            "cmvn_istd_stream.bin",
        )

    fun ensureInstalled(
        context: Context,
        backend: VadBackend,
    ): File {
        val folder =
            when (backend) {
                VadBackend.FIRERED_NON_STREAM -> "non_stream"
                VadBackend.FIRERED_STREAM -> "stream"
                VadBackend.SILERO -> error("Silero does not use FireRed assets")
            }
        val files =
            if (backend == VadBackend.FIRERED_STREAM) streamFiles else nonStreamFiles
        val target = File(context.filesDir, "firered_vad/" + MODEL_ASSET_VERSION + "/" + folder)
        target.mkdirs()

        for (name in files) {
            val output = File(target, name)
            if (output.exists() && output.length() > 0L) continue

            val tmp = File(target, name + ".tmp")
            context.assets.open("firered/" + folder + "/" + name).use { input ->
                tmp.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
            if (output.exists()) output.delete()
            check(tmp.renameTo(output)) {
                "Unable to install FireRedVAD asset " + name
            }
        }

        return target
    }

    private const val MODEL_ASSET_VERSION = "ncnn-2026-03"
}
