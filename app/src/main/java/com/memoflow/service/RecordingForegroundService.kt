package com.memoflow.service
import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import com.memoflow.data.*
import com.memoflow.recording.*
import kotlinx.coroutines.*
import java.io.File
class RecordingForegroundService:Service(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);private var job:Job?=null;private lateinit var db:MemoDatabase
 override fun onCreate(){super.onCreate();db=MemoDatabase.get(this);createChannel();ChunkSyncWorker.schedule(this)}
 override fun onStartCommand(i:Intent?,flags:Int,id:Int):Int{when(i?.action){ACTION_STOP->{stopRecording();stopSelf()};ACTION_START->startRecording()};return START_STICKY}
 private fun startRecording(){if(job?.isActive==true)return;startForeground(7,NotificationCompat.Builder(this,CH).setContentTitle("MemoFlow recording").setContentText("Microphone capture is active").setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build());job=scope.launch{val source=AudioRecordSource();val enc=AacMediaCodecEncoder();source.start();enc.open(16000,1,24000);var writer=M4aChunkWriter(File(filesDir,"audio"));var vad=EnergyVadEngine();writer.start();var chunkStart=System.currentTimeMillis();val ranges=mutableListOf<com.memoflow.domain.AudioRange>();try{while(isActive){val frame=source.read()?:break;ranges+=vad.process(frame).map{it.copy(chunkId=writer.currentId)};val encoded=enc.encode(frame);enc.outputFormat?.let{writer.onFormat(it)};encoded.forEach{writer.write(it)};if(System.currentTimeMillis()-chunkStart>=10*60*1000L){finishChunk(writer,ranges);ranges.clear();writer=M4aChunkWriter(File(filesDir,"audio"));writer.start();vad=EnergyVadEngine();chunkStart=System.currentTimeMillis()}}}finally{enc.flush();finishChunk(writer,ranges);enc.close();source.stop()}}}
 private suspend fun finishChunk(writer:M4aChunkWriter,ranges:List<com.memoflow.domain.AudioRange>){writer.finish()?.let{chunk->db.chunks().upsert(chunk.toEntity());db.chunks().insertRanges(ranges.map{it.toEntity(chunk.id)})}}
 private fun stopRecording(){job?.cancel();job=null}
 override fun onDestroy(){stopRecording();scope.cancel();super.onDestroy()};override fun onBind(i:Intent?)=null
 private fun createChannel(){if(android.os.Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CH,"Recording",NotificationManager.IMPORTANCE_LOW))}
 companion object{const val ACTION_START="start";const val ACTION_STOP="stop";const val CH="recording"}
}