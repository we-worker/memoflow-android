package com.memoflow.domain

import kotlinx.coroutines.flow.Flow

interface AudioSource {
    suspend fun start()
    suspend fun read(): AudioFrame?
    suspend fun stop()
}

interface AudioEncoder {
    suspend fun open(sampleRate: Int, channels: Int, bitrate: Int)
    suspend fun encode(frame: AudioFrame): List<EncodedAudioFrame>
    suspend fun flush(): List<EncodedAudioFrame>
    suspend fun close()
}

interface VadEngine {
    suspend fun process(frame: AudioFrame): List<AudioRange>
    suspend fun flush(): List<AudioRange> = emptyList()
    suspend fun reset()
    suspend fun close() = Unit
}

interface RecordingEngine {
    suspend fun start()
    suspend fun stop()
    suspend fun pause()
    suspend fun resume()
    fun observeState(): Flow<RecordingState>
}

enum class RecordingState { IDLE, STARTING, RECORDING, PAUSED, STOPPING, ERROR }

interface AsrEngine {
    suspend fun transcribe(reference: AudioReference): List<TranscriptSegment>
}

data class AudioReference(
    val chunkId: String,
    val startMs: Int,
    val endMs: Int,
    val audioPath: String,
)
