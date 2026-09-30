package com.memoflow.recording
import com.memoflow.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import java.io.File
class RemoteAsrEngine(private val baseUrl:String,private val client:OkHttpClient=OkHttpClient()):AsrEngine {
 override suspend fun transcribe(reference:AudioReference):List<TranscriptSegment> = withContext(Dispatchers.IO){
  val f=File(reference.audioPath)
  val body=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("chunk_id",reference.chunkId).addFormDataPart("start_ms",reference.startMs.toString()).addFormDataPart("end_ms",reference.endMs.toString()).addFormDataPart("audio",f.name,f.asRequestBody("audio/mp4".toMediaType())).build()
  val res=client.newCall(Request.Builder().url("$baseUrl/asr").post(body).build()).execute(); if(!res.isSuccessful)return@withContext emptyList()
  val json=org.json.JSONObject(res.body?.string().orEmpty()); val arr=json.optJSONArray("segments")?:JSONArray(); buildList{for(i in 0 until arr.length()){val s=arr.getJSONObject(i);add(TranscriptSegment(reference.chunkId,s.optLong("start_ms"),s.optLong("end_ms"),s.optString("text"),s.optString("language"),s.optString("model_id"),s.optString("model_version")))}}
 }
}