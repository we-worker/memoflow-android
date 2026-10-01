package com.memoflow.processing

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs

object WaveformStore {
    private const val MAGIC = 0x4D465756
    private const val VERSION = 1

    fun write(file: File, values: List<Float>) {
        file.parentFile?.mkdirs()
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(values.size)
            values.forEach { out.writeFloat(it.coerceIn(0f, 1f)) }
        }
    }

    fun read(file: File): List<Float> {
        if (!file.exists()) return emptyList()
        return runCatching {
            DataInputStream(FileInputStream(file)).use { input ->
                if (input.readInt() != MAGIC) return@use emptyList()
                if (input.readInt() != VERSION) return@use emptyList()
                val count = input.readInt().coerceIn(0, 100_000)
                List(count) { input.readFloat().coerceIn(0f, 1f) }
            }
        }.getOrDefault(emptyList())
    }

    fun sidecarForAudio(audioFile: File): File =
        File(audioFile.parentFile, audioFile.nameWithoutExtension + ".waveform")
}

class WaveformAccumulator(
    private val bucketSamples: Int = 800,
) {
    private val values = mutableListOf<Float>()
    private var peak = 0
    private var samples = 0
    private var pendingLowByte: Int? = null

    fun accept(pcm: ByteArray) {
        var index = 0

        pendingLowByte?.let { low ->
            if (pcm.isNotEmpty()) {
                acceptSample((low or (pcm[0].toInt() shl 8)).toShort().toInt())
                index = 1
            }
            pendingLowByte = null
        }

        while (index + 1 < pcm.size) {
            val sample =
                ((pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8))
                    .toShort()
                    .toInt()
            acceptSample(sample)
            index += 2
        }

        if (index < pcm.size) {
            pendingLowByte = pcm[index].toInt() and 0xff
        }
    }

    fun finish(): List<Float> {
        if (samples > 0) flushBucket()
        return values.toList()
    }

    private fun acceptSample(sample: Int) {
        peak = maxOf(peak, abs(sample))
        samples++
        if (samples >= bucketSamples) flushBucket()
    }

    private fun flushBucket() {
        values += (peak / 32768f).coerceIn(0f, 1f)
        peak = 0
        samples = 0
    }
}

/**
 * One-shot helper used only when an older speech-only file has no waveform sidecar.
 * New post-processing builds the sidecar without an extra decode.
 */
object AudioWaveformExtractor {
    fun extract(source: File): List<Float> {
        if (!source.exists()) return emptyList()

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(source.absolutePath)

            var audioTrack = -1
            var trackFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    audioTrack = index
                    trackFormat = format
                    break
                }
            }
            if (audioTrack < 0 || trackFormat == null) return emptyList()

            extractor.selectTrack(audioTrack)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            trackFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

            decoder =
                MediaCodec.createDecoderByType(mime).also {
                    it.configure(trackFormat, null, null, 0)
                    it.start()
                }

            val waveform = WaveformAccumulator()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER,
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && info.size > 0) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            outputBuffer.get(bytes)
                            waveform.accept(bytes)
                        }
                        outputDone =
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            waveform.finish()
        } catch (_: Throwable) {
            emptyList()
        } finally {
            decoder?.runCatching { stop() }
            decoder?.runCatching { release() }
            runCatching { extractor.release() }
        }
    }
}
