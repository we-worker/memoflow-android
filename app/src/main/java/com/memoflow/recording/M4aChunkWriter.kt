package com.memoflow.recording
import android.media.*
import com.memoflow.domain.*
import java.io.File
import java.security.MessageDigest
class M4aChunkWriter(private val dir:File,private val sampleRate:Int=16000,private val channels:Int=1,private val bitrate:Int=24000){ var currentId:String=""; private var mux:MediaMuxer?=null;private var track=-1;private var file:File?=null;private var start=0L
 fun start(){dir.mkdirs();file=File(dir,"chunk_"+System.currentTimeMillis()+".part");currentId=file!!.nameWithoutExtension;mux=MediaMuxer(file!!.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);track=-1;start=System.currentTimeMillis()}
 fun write(f:EncodedAudioFrame){val m=mux?:return;if(track<0)return;val info=MediaCodec.BufferInfo();info.set(0,f.data.size,f.presentationTimeUs,f.flags);m.writeSampleData(track,java.nio.ByteBuffer.wrap(f.data),info)}
 fun onFormat(format:MediaFormat){val m=mux?:return;if(track<0){track=m.addTrack(format);m.start()}}
 fun finish():AudioChunk?{val m=mux?:return null;runCatching{m.stop();m.release()};val p=file?:return null;val out=File(p.parent,p.name.removeSuffix(".part")+".m4a");p.renameTo(out);val end=System.currentTimeMillis();val bytes=out.readBytes();val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){ "%02x".format(it)};mux=null;return AudioChunk(out.name.substringBefore('.'),"android-device",start,end,end-start,out.absolutePath,"aac-lc","m4a",sampleRate,channels,bitrate,bytes.size.toLong(),sha,ChunkState.COMPLETE)}
}
