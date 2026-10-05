package com.memoflow.processing

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.memoflow.data.ChunkSyncWorker
import com.memoflow.data.MemoDatabase
import com.memoflow.data.toEntity
import com.memoflow.domain.AudioFrame
import com.memoflow.domain.AudioRange
import com.memoflow.domain.VadEngine
import com.memoflow.recording.SherpaOnnxSileroVadEngine
import com.memoflow.service.RecordingForegroundService
import com.memoflow.vad.FireRedVadEngine
import com.memoflow.vad.VadBackend
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AudioPostProcessWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        processMutex.withLock {
            withContext(Dispatchers.IO) {
                doWorkLocked()
            }
        }

    private suspend fun doWorkLocked(): Result {
        val chunkId = inputData.getString(KEY_CHUNK_ID) ?: return Result.failure()
        val dao = MemoDatabase.get(applicationContext).chunks()
        val chunk = dao.getChunk(chunkId) ?: return Result.failure()

        // A deferred charging job may already have completed before the stop-recording
        // replacement job runs. Avoid decoding/processing the same chunk twice.
        if (chunk.postProcessState == "DONE") return Result.success()

        val original = File(chunk.audioPath)
        if (!original.exists()) {
            dao.updatePostProcessState(chunkId, "FAILED")
            return Result.failure()
        }

        dao.updatePostProcessState(chunkId, "PROCESSING")

        val waveformFile =
            File(applicationContext.filesDir, "waveforms/" + chunkId + ".waveform")
        val existingWaveform =
            chunk.waveformPath
                ?.let(::File)
                ?.takeIf { it.exists() }
                ?.let(WaveformStore::read)
                .orEmpty()
        val shouldGenerateWaveform = existingWaveform.isEmpty()

        try {
            val prefs =
                applicationContext.getSharedPreferences(
                    RecordingForegroundService.PREFS_RECORDING,
                    Context.MODE_PRIVATE,
                )
            val backend =
                VadBackend.fromStored(
                    prefs.getString(
                        RecordingForegroundService.KEY_VAD_ENGINE,
                        RecordingForegroundService.DEFAULT_VAD_ENGINE,
                    ),
                )
            val threshold =
                when (backend) {
                    VadBackend.SILERO ->
                        prefs.getFloat(
                            RecordingForegroundService.KEY_SILERO_THRESHOLD,
                            RecordingForegroundService.DEFAULT_SILERO_THRESHOLD,
                        )
                    VadBackend.FIRERED_NON_STREAM,
                    VadBackend.FIRERED_STREAM ->
                        prefs.getFloat(
                            RecordingForegroundService.KEY_FIRERED_THRESHOLD,
                            RecordingForegroundService.DEFAULT_FIRERED_THRESHOLD,
                        )
                }
            val mergeSilenceMs =
                prefs.getInt(
                    RecordingForegroundService.KEY_VAD_SEGMENT_GAP_MINUTES,
                    RecordingForegroundService.DEFAULT_VAD_SEGMENT_GAP_MINUTES,
                ).coerceIn(1, 10) * 60_000L

            // Charging path decodes once for VAD + waveform. If the user already
            // opened this detail and generated a waveform first, the VAD pass skips
            // waveform accumulation and reuses the existing sidecar.
            val analysis =
                decodeAnalyze(
                    source = original,
                    backend = backend,
                    threshold = threshold,
                    collectWaveform = shouldGenerateWaveform,
                )
            val originalWaveform =
                if (shouldGenerateWaveform) {
                    analysis.waveform.also {
                        if (it.isNotEmpty()) {
                            WaveformStore.write(waveformFile, it)
                        }
                    }
                } else {
                    existingWaveform
                }
            val sessions =
                groupVadSessions(
                    ranges = analysis.ranges,
                    mergeSilenceMs = mergeSilenceMs,
                    durationMs = chunk.durationMs,
                )

            // speech-only is now built by copying encoded AAC samples from the
            // original M4A. This removes the second PCM read and AAC re-encode.
            val speech =
                buildSpeechOnlyAudio(
                    chunkId = chunkId,
                    source = original,
                    ranges = analysis.ranges,
                    originalDurationMs = chunk.durationMs,
                    originalWaveform = originalWaveform,
                )

            if (speech == null) {
                chunk.speechAudioPath?.let { oldPath ->
                    val old = File(oldPath)
                    WaveformStore.sidecarForAudio(old).delete()
                    old.delete()
                }
            }

            dao.replaceRanges(chunkId, sessions.map { it.toEntity(chunkId) })
            dao.updatePostProcessResult(
                id = chunkId,
                speechAudioPath = speech?.file?.absolutePath,
                speechDurationMs = speech?.durationMs ?: 0L,
                waveformPath =
                    when {
                        shouldGenerateWaveform && originalWaveform.isNotEmpty() ->
                            waveformFile.absolutePath
                        !chunk.waveformPath.isNullOrBlank() -> chunk.waveformPath
                        else -> null
                    },
                state = "DONE",
                appliedVadEngine = backend.name,
                appliedVadThreshold = threshold,
                appliedVadMergeSilenceMs = mergeSilenceMs,
            )

            // Normal PC sync is event-driven once the chunk has complete VAD metadata.
            ChunkSyncWorker.enqueue(applicationContext)

            return Result.success(
                workDataOf(
                    KEY_RANGE_COUNT to analysis.ranges.size,
                    KEY_SPEECH_DURATION_MS to (speech?.durationMs ?: 0L),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            dao.updatePostProcessState(chunkId, "FAILED")
            return Result.failure()
        }
    }

    private suspend fun decodeAnalyze(
        source: File,
        backend: VadBackend,
        threshold: Float,
        collectWaveform: Boolean,
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

        val vad: VadEngine =
            when (backend) {
                VadBackend.SILERO ->
                    SherpaOnnxSileroVadEngine(
                        context = applicationContext,
                        threshold = threshold,
                    )
                VadBackend.FIRERED_NON_STREAM,
                VadBackend.FIRERED_STREAM ->
                    FireRedVadEngine(
                        context = applicationContext,
                        backend = backend,
                        threshold = threshold,
                    )
            }
        val ranges = mutableListOf<AudioRange>()
        val waveform = if (collectWaveform) WaveformAccumulator() else null

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

                            waveform?.accept(bytes)
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
            return AnalysisResult(ranges, waveform?.finish().orEmpty())
        } finally {
            runCatching { vad.close() }
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { extractor.release() }
        }
    }

    private fun buildSpeechOnlyAudio(
        chunkId: String,
        source: File,
        ranges: List<AudioRange>,
        originalDurationMs: Long,
        originalWaveform: List<Float>,
    ): SpeechFile? {
        if (ranges.isEmpty() || !source.exists()) return null

        val padded = mergeForCropping(ranges, originalDurationMs)
        if (padded.isEmpty()) return null

        val outputDir = File(applicationContext.filesDir, "speech").apply { mkdirs() }
        val target = File(outputDir, chunkId + "_speech.m4a")
        val temp = File(outputDir, chunkId + "_speech.new.m4a")
        temp.delete()

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            extractor.setDataSource(source.absolutePath)
            var sourceTrack = -1
            var sourceFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    sourceTrack = index
                    sourceFormat = format
                    break
                }
            }
            require(sourceTrack >= 0 && sourceFormat != null) {
                "No audio track in " + source.name
            }

            extractor.selectTrack(sourceTrack)
            val sampleRate =
                sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            val frameDurationUs = AAC_FRAME_SAMPLES * 1_000_000L / sampleRate
            val maxInputSize =
                if (sourceFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    sourceFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    DEFAULT_REMUX_BUFFER_BYTES
                }.coerceAtLeast(DEFAULT_REMUX_BUFFER_BYTES)

            muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val targetTrack = muxer.addTrack(sourceFormat)
            muxer.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocate(maxInputSize)
            val info = MediaCodec.BufferInfo()
            var outputBaseUs = 0L
            var wroteAny = false

            for (range in padded) {
                val startUs = range.first * 1_000L
                val endUs = (range.last + 1L) * 1_000L
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                var firstSourcePtsUs = -1L
                var lastOutputPtsUs = -1L

                while (true) {
                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs < 0L || sampleTimeUs >= endUs) break
                    if (sampleTimeUs < startUs) {
                        if (!extractor.advance()) break
                        continue
                    }

                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    if (firstSourcePtsUs < 0L) firstSourcePtsUs = sampleTimeUs
                    val outputPtsUs = outputBaseUs + (sampleTimeUs - firstSourcePtsUs)
                    info.set(0, sampleSize, outputPtsUs, 0)
                    muxer.writeSampleData(targetTrack, buffer, info)
                    wroteAny = true
                    lastOutputPtsUs = outputPtsUs

                    if (!extractor.advance()) break
                }

                if (lastOutputPtsUs >= 0L) {
                    outputBaseUs = lastOutputPtsUs + frameDurationUs
                }
            }

            require(wroteAny) { "No AAC samples selected for speech-only audio" }
            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            require(temp.exists() && temp.length() > 0L) {
                "speech-only remux produced an empty file"
            }

            replaceFileSafely(temp, target)

            val speechWaveform =
                cropWaveform(
                    waveform = originalWaveform,
                    ranges = padded,
                    originalDurationMs = originalDurationMs,
                )
            if (speechWaveform.isNotEmpty()) {
                WaveformStore.write(
                    WaveformStore.sidecarForAudio(target),
                    speechWaveform,
                )
            }

            return SpeechFile(
                file = target,
                durationMs = outputBaseUs / 1_000L,
            )
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            temp.delete()
        }
    }

    private fun replaceFileSafely(newFile: File, target: File) {
        val backup = File(target.parentFile, target.name + ".bak")
        backup.delete()

        if (target.exists()) {
            require(target.renameTo(backup)) {
                "Unable to preserve previous " + target.name
            }
        }

        try {
            if (!newFile.renameTo(target)) {
                newFile.copyTo(target, overwrite = false)
                newFile.delete()
            }
            require(target.exists() && target.length() > 0L) {
                "Unable to install " + target.name
            }
            backup.delete()
        } catch (error: Throwable) {
            target.delete()
            if (backup.exists()) backup.renameTo(target)
            throw error
        }
    }

    private fun cropWaveform(
        waveform: List<Float>,
        ranges: List<LongRange>,
        originalDurationMs: Long,
    ): List<Float> {
        if (waveform.isEmpty() || originalDurationMs <= 0L) return emptyList()

        val output = mutableListOf<Float>()
        for (range in ranges) {
            val startIndex =
                ((range.first.toDouble() / originalDurationMs) * waveform.size)
                    .toInt()
                    .coerceIn(0, waveform.size)
            val endIndex =
                kotlin.math.ceil(
                    ((range.last + 1L).toDouble() / originalDurationMs) * waveform.size,
                )
                    .toInt()
                    .coerceIn(startIndex, waveform.size)
            if (endIndex > startIndex) {
                output += waveform.subList(startIndex, endIndex)
            }
        }
        return output
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

    private data class AnalysisResult(
        val ranges: List<AudioRange>,
        val waveform: List<Float>,
    )

    private data class SpeechFile(
        val file: File,
        val durationMs: Long,
    )

    companion object {
        private const val KEY_CHUNK_ID = "chunk_id"
        const val KEY_RANGE_COUNT = "range_count"
        const val KEY_SPEECH_DURATION_MS = "speech_duration_ms"

        private const val SAMPLE_RATE = 16_000
        private const val AAC_FRAME_SAMPLES = 1_024L
        private const val DEFAULT_REMUX_BUFFER_BYTES = 256 * 1024
        private const val PRE_ROLL_MS = 400L
        private const val POST_ROLL_MS = 500L
        private const val MERGE_GAP_MS = 250L

        private val processMutex = Mutex()

        /** Immediate full processing requested explicitly by the user. */
        @JvmStatic
        fun enqueue(context: Context, chunkId: String) {
            val request =
                OneTimeWorkRequestBuilder<AudioPostProcessWorker>()
                    .setInputData(workDataOf(KEY_CHUNK_ID to chunkId))
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "post-vad-" + chunkId,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /**
         * Detail-open path: produce the waveform first so playback UI becomes useful
         * quickly, then run VAD/speech-only processing.
         */
        fun enqueueForDetail(context: Context, chunkId: String) {
            val waveformRequest =
                OneTimeWorkRequestBuilder<WaveformGenerationWorker>()
                    .setInputData(
                        workDataOf(WaveformGenerationWorker.KEY_CHUNK_ID to chunkId),
                    )
                    .build()
            val vadRequest =
                OneTimeWorkRequestBuilder<AudioPostProcessWorker>()
                    .setInputData(workDataOf(KEY_CHUNK_ID to chunkId))
                    .build()

            WorkManager.getInstance(context)
                .beginUniqueWork(
                    "post-vad-" + chunkId,
                    ExistingWorkPolicy.REPLACE,
                    waveformRequest,
                )
                .then(vadRequest)
                .enqueue()
        }

        /** Background processing waits for both charging and a non-low battery. */
        fun enqueueDeferred(context: Context, chunkId: String) {
            val constraints =
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresCharging(true)
                    .build()

            val request =
                OneTimeWorkRequestBuilder<AudioPostProcessWorker>()
                    .setInputData(workDataOf(KEY_CHUNK_ID to chunkId))
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "post-vad-" + chunkId,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
