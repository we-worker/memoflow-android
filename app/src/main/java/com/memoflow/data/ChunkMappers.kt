package com.memoflow.data

import com.memoflow.domain.AudioChunk
import com.memoflow.domain.AudioRange
import com.memoflow.domain.TranscriptSegment

fun AudioChunk.toEntity() =
    AudioChunkEntity(
        id = id,
        deviceId = deviceId,
        startTimeUtcMs = startTimeUtcMs,
        endTimeUtcMs = endTimeUtcMs,
        durationMs = durationMs,
        audioPath = audioPath,
        codec = codec,
        container = container,
        sampleRate = sampleRate,
        channels = channels,
        bitrate = bitrate,
        fileSize = fileSize,
        checksumSha256 = checksumSha256,
        state = state.name,
        schemaVersion = schemaVersion,
        postProcessState = "PENDING",
        originalAvailable = true,
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
