package com.memoflow.data

import android.content.Context
import androidx.work.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ChunkSyncWorker(app: Context, params: WorkerParameters) : CoroutineWorker(app, params) {
    private val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val db = MemoDatabase.get(applicationContext)
            val prefs = applicationContext.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
            val api = prefs.getString(KEY_BASE_URL, "")?.trimEnd('/') ?: ""
            if (api.isBlank()) return@withContext Result.success()

            var retry = false
            for (chunk in db.chunks().pending()) {
                try {
                    val file = File(chunk.audioPath)
                    if (!file.exists()) {
                        db.chunks().updateState(chunk.id, "FAILED")
                        continue
                    }

                    db.chunks().updateState(chunk.id, "UPLOAD_PENDING")
                    val ranges = db.chunks().rangesForChunk(chunk.id)
                    val metadata =
                        JSONObject()
                            .put("schemaVersion", chunk.schemaVersion)
                            .put(
                                "chunk",
                                JSONObject()
                                    .put("id", chunk.id)
                                    .put("deviceId", chunk.deviceId)
                                    .put("startTimeUtcMs", chunk.startTimeUtcMs)
                                    .put("endTimeUtcMs", chunk.endTimeUtcMs)
                                    .put("durationMs", chunk.durationMs)
                                    .put("codec", chunk.codec)
                                    .put("container", chunk.container)
                                    .put("sampleRate", chunk.sampleRate)
                                    .put("channels", chunk.channels)
                                    .put("bitrate", chunk.bitrate)
                                    .put("fileSize", chunk.fileSize)
                                    .put("sha256", chunk.checksumSha256),
                            )
                            .put(
                                "speechRanges",
                                JSONArray().apply {
                                    ranges.forEach { range ->
                                        put(
                                            JSONObject()
                                                .put("startOffsetMs", range.startOffsetMs)
                                                .put("endOffsetMs", range.endOffsetMs)
                                                .put("confidence", range.confidence),
                                        )
                                    }
                                },
                            )
                            .toString()

                    val body =
                        MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("chunk_id", chunk.id)
                            .addFormDataPart("metadata_json", metadata)
                            .addFormDataPart(
                                "audio",
                                file.name,
                                file.asRequestBody("audio/mp4".toMediaType()),
                            )
                            .build()

                    client.newCall(
                        Request.Builder().url("$api/audio").post(body).build(),
                    ).execute().use { response ->
                        if (response.isSuccessful) {
                            db.chunks().updateState(chunk.id, "UPLOADED")
                        } else {
                            db.chunks().updateState(chunk.id, "FAILED")
                            retry = true
                        }
                    }
                } catch (_: Exception) {
                    db.chunks().updateState(chunk.id, "FAILED")
                    retry = true
                }
            }

            if (retry) Result.retry() else Result.success()
        }

    companion object {
        const val PREFS_SYNC = "sync"
        const val KEY_BASE_URL = "base_url"

        fun schedule(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
            val wifiOnly = prefs.getBoolean("wifi_only", true)
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "memoflow-sync",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ChunkSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(networkType)
                            .build(),
                    )
                    .build(),
            )
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<ChunkSyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build(),
            )
        }
    }
}
