package com.memoflow.data

import com.memoflow.domain.AudioChunk
import com.memoflow.domain.AudioRange
import com.memoflow.domain.TranscriptSegment

fun AudioChunk.toEntity() =
    AudioChunkEntity(
        id,
        deviceId,
        startTimeUtcMs,
        endTimeUtcMs,
        durationMs,
        audioPath,
        codec,
        container,
        sampleRate,
        channels,
        bitrate,
        fileSize,
        checksumSha256,
        state.name,
        schemaVersion,
    )

fun AudioRange.toEntity(id: String) =
    AudioRangeEntity(id, startOffsetMs, endOffsetMs, type, confidence, modelId, modelVersion)

fun TranscriptSegment.toEntity() =
    TranscriptSegmentEntity(
        chunkId = chunkId,
        startOffsetMs = startOffsetMs,
        endOffsetMs = endOffsetMs,
        text = text,
        language = language,
        modelId = modelId,
        modelVersion = modelVersion,
    )
