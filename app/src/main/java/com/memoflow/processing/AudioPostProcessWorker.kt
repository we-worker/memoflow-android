package com.memoflow.processing

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.memoflow.data.MemoDatabase
import com.memoflow.data.toEntity
import com.memoflow.domain.AudioFrame
import com.memoflow.domain.AudioRange
import com.memoflow.recording.AacMediaCodecEncoder
import com.memoflow.recording.M4aChunkWriter
import com.memoflow.recording.SherpaOnnxSileroVadEngine
import com.memoflow.service.RecordingForegroundService
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioPostProcessWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val chunkId = inputData.getString(KEY_CHUNK_ID) ?: return@withContext Result.failure()
        val dao = MemoDatabase.get(applicationContext).chunks()
        val chunk = dao.getChunk(chunkId) ?: return@withContext Result.failure()
        val original = File(chunk.audioPath)

        if (!original.exists()) {
            dao.updatePostProcessState(chunkId, "FAILED")
            return@withContext Result.failure()
        }

        dao.updatePostProcessState(chunkId, "PROCESSING")

        val tempDir = File(applicationContext.cacheDir, "postprocess").apply { mkdirs() }
        val pcmFile = File(tempDir, chunkId + ".pcm")
        val waveformFile =
            File(applicationContext.filesDir, "waveforms/" + chunkId + ".waveform")

        try {
            val threshold =
                applicationContext
                    .getSharedPreferences(
                        RecordingForegroundService.PREFS_RECORDING,
                        Context.MODE_PRIVATE,
                    )
                    .getFloat(
                        RecordingForegroundService.KEY_SILERO_THRESHOLD,
                        RecordingForegroundService.DEFAULT_SILERO_THRESHOLD,
                    )

            val analysis =
                decodeAnalyze(
                    source = original,
                    pcmFile = pcmFile,
                    waveformFile = waveformFile,
                    threshold = threshold,
                )

            dao.replaceRanges(chunkId, analysis.ranges.map { it.toEntity(chunkId) })

            val speech =
                buildSpeechOnlyAudio(
                    chunkId = chunkId,
                    pcmFile = pcmFile,
                    ranges = analysis.ranges,
                    originalDurationMs = chunk.durationMs,
                )

            dao.updatePostProcessResult(
                id = chunkId,
                speechAudioPath = speech?.file?.absolutePath,
                speechDurationMs = speech?.durationMs ?: 0L,
                waveformPath = waveformFile.absolutePath,
                state = "DONE",
            )

            Result.success(
                workDataOf(
                    KEY_RANGE_COUNT to analysis.ranges.size,
                    KEY_SPEECH_DURATION_MS to (speech?.durationMs ?: 0L),
                ),
            )
        } catch (_: Throwable) {
            dao.updatePostProcessState(chunkId, "FAILED")
            Result.failure()
        } finally {
            pcmFile.delete()
        }
    }

    private suspend fun decodeAnalyze(
        source: File,
        pcmFile: File,
        waveformFile: File,
        threshold: Float,
    ): AnalysisResult {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)

        var audioTrack = -1
        var trackFormat: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                audioTrack = index
                trackFormat = format
                break
            }
        }
        require(audioTrack >= 0 && trackFormat != null) {
            "No audio track in " + source.name
        }

        extractor.selectTrack(audioTrack)
        val mime = trackFormat!!.getString(MediaFormat.KEY_MIME)!!
        trackFormat.setInteger(
            MediaFormat.KEY_PCM_ENCODING,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(trackFormat, null, null, 0)
        decoder.start()

        val vad =
            SherpaOnnxSileroVadEngine(
                context = applicationContext,
                threshold = threshold,
            )
        val ranges = mutableListOf<AudioRange>()
        val waveform = WaveformAccumulator()
        val output = FileOutputStream(pcmFile)

        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)!!
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
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(sampleRate == SAMPLE_RATE && channels == 1) {
                            "Unexpected decoded PCM format: " +
                                sampleRate + " Hz, " + channels + " channels"
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)

                            output.write(bytes)
                            waveform.accept(bytes)
                            ranges +=
                                vad.process(
                                    AudioFrame(
                                        pcm = bytes,
                                        timestampNs = 0L,
                                        sampleRate = SAMPLE_RATE,
                                        channels = 1,
                                    ),
                                )
                        }

                        outputDone =
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            ranges += vad.flush()
            WaveformStore.write(waveformFile, waveform.finish())
            return AnalysisResult(ranges)
        } finally {
            runCatching { output.close() }
            runCatching { vad.close() }
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { extractor.release() }
        }
    }

    private suspend fun buildSpeechOnlyAudio(
        chunkId: String,
        pcmFile: File,
        ranges: List<AudioRange>,
        originalDurationMs: Long,
    ): SpeechFile? {
        if (ranges.isEmpty() || !pcmFile.exists()) return null

        val padded = mergeForCropping(ranges, originalDurationMs)
        if (padded.isEmpty()) return null

        val outputDir = File(applicationContext.filesDir, "speech").apply { mkdirs() }
        val encoder = AacMediaCodecEncoder()
        val writer = M4aChunkWriter(outputDir)
        val source = RandomAccessFile(pcmFile, "r")

        writer.start()
        encoder.open(SAMPLE_RATE, 1, BITRATE)

        try {
            val buffer = ByteArray(PCM_BLOCK_BYTES)
            for (range in padded) {
                val startByte = msToByteOffset(range.first)
                val endByte = msToByteOffset(range.last + 1)
                source.seek(startByte.coerceAtMost(source.length()))

                var remaining =
                    (endByte - startByte)
                        .coerceAtLeast(0L)
                        .coerceAtMost(source.length() - source.filePointer)

                while (remaining > 0L) {
                    val want = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = source.read(buffer, 0, want)
                    if (read <= 0) break

                    val frameBytes =
                        if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                    val encoded =
                        encoder.encode(
                            AudioFrame(
                                pcm = frameBytes,
                                timestampNs = 0L,
                                sampleRate = SAMPLE_RATE,
                                channels = 1,
                            ),
                        )
                    encoder.outputFormat?.let(writer::onFormat)
                    encoded.forEach(writer::write)
                    remaining -= read
                }
            }

            val tail = encoder.flush()
            encoder.outputFormat?.let(writer::onFormat)
            tail.forEach(writer::write)
        } finally {
            runCatching { encoder.close() }
            runCatching { source.close() }
        }

        val generated = writer.finish() ?: return null
        val generatedFile = File(generated.audioPath)
        val target = File(outputDir, chunkId + "_speech.m4a")
        if (target.exists()) target.delete()

        if (!generatedFile.renameTo(target)) {
            generatedFile.copyTo(target, overwrite = true)
            generatedFile.delete()
        }

        val duration = padded.sumOf { it.last - it.first + 1L }
        return SpeechFile(target, duration)
    }

    private fun mergeForCropping(
        ranges: List<AudioRange>,
        durationMs: Long,
    ): List<LongRange> {
        val expanded =
            ranges.map {
                val start = (it.startOffsetMs - PRE_ROLL_MS).coerceAtLeast(0L)
                val end = (it.endOffsetMs + POST_ROLL_MS).coerceAtMost(durationMs)
                start..end
            }.sortedBy { it.first }

        if (expanded.isEmpty()) return emptyList()

        val merged = mutableListOf<LongRange>()
        var current = expanded.first()

        for (next in expanded.drop(1)) {
            current =
                if (next.first <= current.last + MERGE_GAP_MS) {
                    current.first..maxOf(current.last, next.last)
                } else {
                    merged += current
                    next
                }
        }
        merged += current
        return merged
    }

    private fun msToByteOffset(ms: Long): Long =
        ms * SAMPLE_RATE * BYTES_PER_SAMPLE / 1000L

    private data class AnalysisResult(val ranges: List<AudioRange>)
    private data class SpeechFile(val file: File, val durationMs: Long)

    companion object {
        private const val KEY_CHUNK_ID = "chunk_id"
        private const val POST_PROCESS_QUEUE = "post-vad-queue"
        const val KEY_RANGE_COUNT = "range_count"
        const val KEY_SPEECH_DURATION_MS = "speech_duration_ms"

        private const val SAMPLE_RATE = 16_000
        private const val BITRATE = 24_000
        private const val BYTES_PER_SAMPLE = 2L
        private const val PCM_BLOCK_BYTES = 3_200
        private const val PRE_ROLL_MS = 400L
        private const val POST_ROLL_MS = 500L
        private const val MERGE_GAP_MS = 250L

        fun enqueue(context: Context, chunkId: String) {
            val request =
                OneTimeWorkRequestBuilder<AudioPostProcessWorker>()
                    .setInputData(workDataOf(KEY_CHUNK_ID to chunkId))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    )
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                POST_PROCESS_QUEUE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
