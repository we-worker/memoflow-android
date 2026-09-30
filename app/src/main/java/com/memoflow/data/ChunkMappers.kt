package com.memoflow.data
import com.memoflow.domain.*
fun AudioChunk.toEntity()=AudioChunkEntity(id,deviceId,startTimeUtcMs,endTimeUtcMs,durationMs,audioPath,codec,container,sampleRate,channels,bitrate,fileSize,checksumSha256,state.name,schemaVersion)
fun AudioRange.toEntity(id:String)=AudioRangeEntity(id,startOffsetMs,endOffsetMs,type,confidence,modelId,modelVersion)
