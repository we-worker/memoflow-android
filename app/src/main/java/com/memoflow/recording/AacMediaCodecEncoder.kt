package com.memoflow.recording
import android.media.*
import com.memoflow.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
class AacMediaCodecEncoder:AudioEncoder { var outputFormat:MediaFormat?=null; private var codec:MediaCodec?=null; private var pts=0L; private var frameUs=100_000L
 override suspend fun open(sampleRate:Int,channels:Int,bitrate:Int)=withContext(Dispatchers.IO){ val f=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,sampleRate,channels).apply{setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);setInteger(MediaFormat.KEY_BIT_RATE,bitrate);setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384)};codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also{it.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);it.start()};pts=0 }
 override suspend fun encode(frame:AudioFrame):List<EncodedAudioFrame>{ val c=codec?:return emptyList(); val out=mutableListOf<EncodedAudioFrame>(); val ix=c.dequeueInputBuffer(10_000);if(ix>=0){val b=c.getInputBuffer(ix)!!;b.clear();b.put(frame.pcm);c.queueInputBuffer(ix,0,frame.pcm.size,pts,0);pts+=frameUs}; drain(c,out);return out }
 override suspend fun flush():List<EncodedAudioFrame>{codec?.let{val ix=it.dequeueInputBuffer(10_000);if(ix>=0)it.queueInputBuffer(ix,0,0,pts,MediaCodec.BUFFER_FLAG_END_OF_STREAM)};return buildList{codec?.let{drain(it,this)}}}
 private fun drain(c:MediaCodec,out:MutableList<EncodedAudioFrame>){val info=MediaCodec.BufferInfo();while(true){val ix=c.dequeueOutputBuffer(info,0);if(ix==MediaCodec.INFO_TRY_AGAIN_LATER)break;if(ix==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){outputFormat=c.outputFormat;continue;}if(ix>=0){val b=c.getOutputBuffer(ix)!!;val d=ByteArray(info.size);b.position(info.offset);b.get(d);out+=EncodedAudioFrame(d,info.presentationTimeUs,info.flags);c.releaseOutputBuffer(ix,false)}}}
 override suspend fun close(){withContext(Dispatchers.IO){codec?.stop();codec?.release();codec=null}}
}
