package com.memoflow.data
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
class ChunkSyncWorker(app:Context,params:WorkerParameters):CoroutineWorker(app,params){
 private val client=OkHttpClient.Builder().callTimeout(90,TimeUnit.SECONDS).build()
 override suspend fun doWork():Result=withContext(Dispatchers.IO){val db=MemoDatabase.get(applicationContext);val api=applicationContext.getSharedPreferences("sync",0).getString("base_url","")?.trimEnd('/')?:"";if(api.isBlank())return@withContext Result.success();var retry=false;for(c in db.chunks().pending()){try{val f=File(c.audioPath);if(!f.exists()){db.chunks().updateState(c.id,"FAILED");continue};val body=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("chunk_id",c.id).addFormDataPart("metadata_json",c.toString()).addFormDataPart("audio",f.name,f.asRequestBody("audio/mp4".toMediaType())).build();val r=client.newCall(Request.Builder().url("$api/audio").post(body).build()).execute();if(r.isSuccessful)db.chunks().updateState(c.id,"UPLOADED")else{db.chunks().updateState(c.id,"FAILED");retry=true}}catch(_:Exception){retry=true}};if(retry)Result.retry() else Result.success()}
 companion object{fun schedule(context:Context){WorkManager.getInstance(context).enqueueUniquePeriodicWork("memoflow-sync",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<ChunkSyncWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())}}
}