package com.memoflow.recording

import com.memoflow.data.ChunkSyncWorker
import com.memoflow.domain.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject

class RemoteAsrEngine(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : AsrEngine {
    override suspend fun transcribe(reference: AudioReference): List<TranscriptSegment> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) error("MemoFlow API key is empty")

            val file = File(reference.audioPath)
            val body =
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chunk_id", reference.chunkId)
                    .addFormDataPart("start_ms", reference.startMs.toString())
                    .addFormDataPart("end_ms", reference.endMs.toString())
                    .addFormDataPart(
                        "audio",
                        file.name,
                        file.asRequestBody("audio/mp4".toMediaType()),
                    )
                    .build()

            client.newCall(
                Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/asr")
                    .header(ChunkSyncWorker.API_KEY_HEADER, apiKey)
                    .post(body)
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    error("ASR request failed: HTTP " + response.code)
                }

                val json = JSONObject(response.body?.string().orEmpty())
                val array = json.optJSONArray("segments") ?: JSONArray()
                buildList {
                    for (index in 0 until array.length()) {
                        val segment = array.getJSONObject(index)
                        add(
                            TranscriptSegment(
                                reference.chunkId,
                                segment.optLong("start_ms"),
                                segment.optLong("end_ms"),
                                segment.optString("text"),
                                segment.optString("language"),
                                segment.optString("model_id"),
                                segment.optString("model_version"),
                            ),
                        )
                    }
                }
            }
        }
}
