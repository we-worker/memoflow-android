package com.memoflow.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.memoflow.domain.AudioEncoder
import com.memoflow.domain.AudioFrame
import com.memoflow.domain.EncodedAudioFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AacMediaCodecEncoder : AudioEncoder {
    var outputFormat: MediaFormat? = null
        private set

    private var codec: MediaCodec? = null
    private var pts = 0L
    private val frameUs = 100_000L

    override suspend fun open(
        sampleRate: Int,
        channels: Int,
        bitrate: Int,
    ) = withContext(Dispatchers.IO) {
        val format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
                .apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
                }

        codec =
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
        pts = 0L
        outputFormat = null
    }

    override suspend fun encode(frame: AudioFrame): List<EncodedAudioFrame> {
        val codec = codec ?: return emptyList()
        val output = mutableListOf<EncodedAudioFrame>()

        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            codec.getInputBuffer(inputIndex)?.let { buffer ->
                buffer.clear()
                buffer.put(frame.pcm)
            }
            codec.queueInputBuffer(inputIndex, 0, frame.pcm.size, pts, 0)
            pts += frameUs
        }

        drain(codec, output)
        return output
    }

    override suspend fun flush(): List<EncodedAudioFrame> {
        val codec = codec ?: return emptyList()
        val output = mutableListOf<EncodedAudioFrame>()

        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                pts,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
        }

        drain(codec, output)
        return output
    }

    private fun drain(
        codec: MediaCodec,
        output: MutableList<EncodedAudioFrame>,
    ) {
        val info = MediaCodec.BufferInfo()

        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> break
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                else -> {
                    if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val data = ByteArray(info.size)
                            buffer.get(data)
                            output +=
                                EncodedAudioFrame(
                                    data = data,
                                    presentationTimeUs = info.presentationTimeUs,
                                    flags = info.flags,
                                )
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            codec?.runCatching { stop() }
            codec?.runCatching { release() }
            codec = null
        }
    }
}
