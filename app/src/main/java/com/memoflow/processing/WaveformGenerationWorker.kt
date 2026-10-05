package com.memoflow.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.memoflow.data.MemoDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * User-visible fast path: generate the waveform first when a recording detail is opened.
 * This worker does not run VAD and does not change postProcessState.
 */
class WaveformGenerationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val chunkId = inputData.getString(KEY_CHUNK_ID) ?: return@withContext Result.failure()
            val dao = MemoDatabase.get(applicationContext).chunks()
            val chunk = dao.getChunk(chunkId) ?: return@withContext Result.failure()

            if (!chunk.originalAvailable) return@withContext Result.success()
            val source = File(chunk.audioPath)
            if (!source.exists()) return@withContext Result.success()

            val existing =
                chunk.waveformPath
                    ?.let(::File)
                    ?.takeIf { it.exists() && WaveformStore.read(it).isNotEmpty() }
            if (existing != null) return@withContext Result.success()

            val waveform = AudioWaveformExtractor.extract(source)
            // Do not block the following VAD worker if fast waveform extraction
            // fails; the full post-process pass can still regenerate it.
            if (waveform.isEmpty()) return@withContext Result.success()

            val target =
                File(applicationContext.filesDir, "waveforms/" + chunkId + ".waveform")
            WaveformStore.write(target, waveform)
            dao.updateWaveformPath(chunkId, target.absolutePath)
            Result.success()
        }

    companion object {
        const val KEY_CHUNK_ID = "chunk_id"
    }
}
