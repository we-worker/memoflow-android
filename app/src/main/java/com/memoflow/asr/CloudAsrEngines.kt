package com.memoflow.asr

import android.util.Base64
import com.memoflow.domain.AsrEngine
import com.memoflow.domain.AudioReference
import com.memoflow.domain.TranscriptSegment
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

interface CloudAsrProvider : AsrEngine {
    val providerName: String
}

class AliyunQwenAsrEngine(
    private val apiKey: String,
    private val model: String = "qwen3-asr-flash",
    private val endpoint: String =
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
    private val client: OkHttpClient = defaultCloudClient(),
) : CloudAsrProvider {
    override val providerName: String = "阿里云 Qwen ASR"

    override suspend fun transcribe(reference: AudioReference): List<TranscriptSegment> =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "阿里云 API Key 未配置" }
            val source = File(reference.audioPath)
            require(source.exists()) { "音频文件不存在" }

            val tempDir = File(source.parentFile ?: source.parentFile, "aliyun_asr_tmp")
            tempDir.mkdirs()

            val output = mutableListOf<TranscriptSegment>()
            var startMs = reference.startMs.toLong()
            val endMs = reference.endMs.toLong().coerceAtLeast(startMs + 1L)

            try {
                while (startMs < endMs) {
                    val clipEnd = minOf(startMs + ALIYUN_MAX_WINDOW_MS, endMs)
                    val clip =
                        if (startMs == reference.startMs.toLong() && clipEnd == endMs) {
                            source
                        } else {
                            AsrAudioTools.clipM4a(
                                source = source,
                                destination =
                                    File(
                                        tempDir,
                                        reference.chunkId + "-" + startMs + "-" + clipEnd + ".m4a",
                                    ),
                                startMs = startMs,
                                endMs = clipEnd,
                            )
                        }

                    val text = transcribeClip(clip)
                    if (text.isNotBlank()) {
                        output +=
                            TranscriptSegment(
                                chunkId = reference.chunkId,
                                startOffsetMs = startMs,
                                endOffsetMs = clipEnd,
                                text = text,
                                modelId = "aliyun-" + model,
                                modelVersion = "cloud",
                            )
                    }

                    if (clip !== source) clip.delete()
                    startMs = clipEnd
                }
            } finally {
                tempDir.deleteRecursively()
            }

            output
        }

    private fun transcribeClip(file: File): String {
        val dataUri =
            "data:audio/mp4;base64," +
                Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)

        val body =
            JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "input_audio")
                                        .put(
                                            "input_audio",
                                            JSONObject().put("data", dataUri),
                                        ),
                                ),
                            ),
                    ),
                )
                .put("stream", false)
                .put(
                    "asr_options",
                    JSONObject().put("enable_itn", true),
                )

        val request =
            Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("阿里云 ASR 请求失败 HTTP " + response.code + ": " + responseText.take(400))
            }
            return parseAssistantText(JSONObject(responseText))
        }
    }

    companion object {
        private const val ALIYUN_MAX_WINDOW_MS = 290_000L
    }
}

class DoubaoArkAsrEngine(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String =
        "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
    private val client: OkHttpClient = defaultCloudClient(),
) : CloudAsrProvider {
    override val providerName: String = "豆包 / 火山方舟"

    override suspend fun transcribe(reference: AudioReference): List<TranscriptSegment> =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "豆包 Ark API Key 未配置" }
            require(model.isNotBlank()) { "请填写支持音频输入的豆包模型 ID" }

            val file = File(reference.audioPath)
            require(file.exists()) { "音频文件不存在" }
            require(file.length() <= 25L * 1024L * 1024L) {
                "豆包单次 Base64 音频限制为 25 MB，当前文件过大"
            }

            val base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            val body =
                JSONObject()
                    .put("model", model)
                    .put(
                        "messages",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("role", "system")
                                    .put(
                                        "content",
                                        "你是语音转写引擎。请忠实逐字转写音频，只输出转写文本，不要总结、解释或添加标题。",
                                    ),
                            )
                            .put(
                                JSONObject()
                                    .put("role", "user")
                                    .put(
                                        "content",
                                        JSONArray()
                                            .put(
                                                JSONObject()
                                                    .put("type", "input_audio")
                                                    .put(
                                                        "input_audio",
                                                        JSONObject()
                                                            .put("data", base64)
                                                            .put("format", "audio/mp4"),
                                                    ),
                                            )
                                            .put(
                                                JSONObject()
                                                    .put("type", "text")
                                                    .put("text", "请逐字转写这段录音。"),
                                            ),
                                    ),
                            ),
                    )
                    .put("temperature", 0)

            val request =
                Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("豆包 ASR 请求失败 HTTP " + response.code + ": " + responseText.take(400))
                }
                val text = parseAssistantText(JSONObject(responseText))
                if (text.isBlank()) {
                    emptyList()
                } else {
                    listOf(
                        TranscriptSegment(
                            chunkId = reference.chunkId,
                            startOffsetMs = reference.startMs.toLong(),
                            endOffsetMs = reference.endMs.toLong(),
                            text = text,
                            modelId = "doubao-" + model,
                            modelVersion = "cloud",
                        ),
                    )
                }
            }
        }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private fun defaultCloudClient() =
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()

internal fun parseAssistantText(json: JSONObject): String {
    val choices = json.optJSONArray("choices") ?: return ""
    if (choices.length() == 0) return ""
    val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
    val content = message.opt("content")

    return when (content) {
        is String -> content.trim()
        is JSONArray -> {
            buildString {
                for (index in 0 until content.length()) {
                    val item = content.opt(index)
                    when (item) {
                        is String -> append(item)
                        is JSONObject -> {
                            val text = item.optString("text")
                            if (text.isNotBlank()) append(text)
                        }
                    }
                }
            }.trim()
        }
        else -> ""
    }
}
