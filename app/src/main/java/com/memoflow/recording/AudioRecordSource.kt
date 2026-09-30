package com.memoflow.recording
import android.media.*
import com.memoflow.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
class AudioRecordSource(private val sampleRate:Int=16000):AudioSource {
 private var recorder:AudioRecord?=null; private val frameSamples=1600
 override suspend fun start()=withContext(Dispatchers.IO){ val min=AudioRecord.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT); recorder=AudioRecord(MediaRecorder.AudioSource.MIC,sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,frameSamples*2*4)).also{it.startRecording()} }
 override suspend fun read():AudioFrame?=withContext(Dispatchers.IO){ val r=recorder?:return@withContext null; val b=ByteArray(frameSamples*2); val n=r.read(b,0,b.size,AudioRecord.READ_BLOCKING); if(n>0) AudioFrame(if(n==b.size)b else b.copyOf(n),System.nanoTime(),sampleRate,1) else null }
 override suspend fun stop(){ withContext(Dispatchers.IO){ recorder?.runCatching{stop();release()};recorder=null } }
}
