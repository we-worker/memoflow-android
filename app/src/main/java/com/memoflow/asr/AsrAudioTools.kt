package com.memoflow.asr

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.k2fsa.sherpa.onnx.OfflineStream
import java.io.File
import java.nio.ByteBuffer

object AsrAudioTools {
    fun feedM4aToStream(
        source: File,
        startMs: Long,
        endMs: Long,
        stream: OfflineStream,
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        val trackIndex = findAudioTrack(extractor)
        require(trackIndex >= 0) { "No audio track in " + source.name }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME")
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val startUs = startMs.coerceAtLeast(0L) * 1000L
        val endUs = endMs.coerceAtLeast(startMs + 1L) * 1000L
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0L || sampleTime >= endUs) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                endUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val input = decoder.getInputBuffer(inputIndex)!!
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    endUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    size,
                                    sampleTime,
                                    0,
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        val sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(sampleRate == 16_000 && channels == 1) {
                            "Expected 16 kHz mono PCM, got " +
                                sampleRate + " Hz / " + channels + " ch"
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0 && info.presentationTimeUs >= startUs && info.presentationTimeUs < endUs) {
                            val buffer = decoder.getOutputBuffer(outputIndex)
                            if (buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                stream.acceptWaveform(pcm16ToFloat(bytes), 16_000)
                            }
                        }

                        outputDone =
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { extractor.release() }
        }
    }

    fun clipM4a(
        source: File,
        destination: File,
        startMs: Long,
        endMs: Long,
    ): File {
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()

        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        val sourceTrack = findAudioTrack(extractor)
        require(sourceTrack >= 0) { "No audio track in " + source.name }
        extractor.selectTrack(sourceTrack)
        val format = extractor.getTrackFormat(sourceTrack)

        val muxer =
            MediaMuxer(
                destination.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
        val targetTrack = muxer.addTrack(format)
        muxer.start()

        val startUs = startMs.coerceAtLeast(0L) * 1000L
        val endUs = endMs.coerceAtLeast(startMs + 1L) * 1000L
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val maxInput =
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } else {
                256 * 1024
            }
        val buffer = ByteBuffer.allocate(maxInput)
        val info = MediaCodec.BufferInfo()
        var firstPts = -1L

        try {
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0L || sampleTime >= endUs) break
                if (sampleTime < startUs) {
                    extractor.advance()
                    continue
                }

                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (firstPts < 0L) firstPts = sampleTime

                info.offset = 0
                info.size = size
                info.presentationTimeUs = (sampleTime - firstPts).coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(targetTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }

        check(destination.exists() && destination.length() > 0L) {
            "Failed to create audio clip"
        }
        return destination
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime =
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return index
        }
        return -1
    }

    private fun pcm16ToFloat(bytes: ByteArray): FloatArray {
        val count = bytes.size / 2
        val out = FloatArray(count)
        var byteIndex = 0
        for (index in 0 until count) {
            val sample =
                ((bytes[byteIndex].toInt() and 0xff) or
                    (bytes[byteIndex + 1].toInt() shl 8))
                    .toShort()
                    .toInt()
            out[index] = sample / 32768.0f
            byteIndex += 2
        }
        return out
    }
}
