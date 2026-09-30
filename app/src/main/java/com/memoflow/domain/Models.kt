package com.memoflow.domain
enum class ChunkState { RECORDING, COMPLETE, UPLOAD_PENDING, UPLOADED, FAILED }
data class AudioChunk(val id:String,val deviceId:String,val startTimeUtcMs:Long,val endTimeUtcMs:Long,val durationMs:Long,val audioPath:String,val codec:String,val container:String,val sampleRate:Int,val channels:Int,val bitrate:Int,val fileSize:Long,val checksumSha256:String,val state:ChunkState,val schemaVersion:Int=1)
data class AudioFrame(val pcm:ByteArray,val timestampNs:Long,val sampleRate:Int,val channels:Int)
data class EncodedAudioFrame(val data:ByteArray,val presentationTimeUs:Long,val flags:Int)
data class AudioRange(val chunkId:String,val startOffsetMs:Long,val endOffsetMs:Long,val type:String="speech",val confidence:Float?=null,val modelId:String?=null,val modelVersion:String?=null)
data class TranscriptSegment(val chunkId:String,val startOffsetMs:Long,val endOffsetMs:Long,val text:String,val language:String?=null,val modelId:String?=null,val modelVersion:String?=null)
