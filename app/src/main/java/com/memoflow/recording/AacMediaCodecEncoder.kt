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
    private var ptsUs = 0L

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
        ptsUs = 0L
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
            codec.queueInputBuffer(inputIndex, 0, frame.pcm.size, ptsUs, 0)

            val bytesPerSample = 2
            val samples =
                if (frame.channels > 0) frame.pcm.size / bytesPerSample / frame.channels else 0
            ptsUs +=
                if (frame.sampleRate > 0) samples * 1_000_000L / frame.sampleRate else 0L
        }

        drain(codec, output, waitForEos = false)
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
                ptsUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
        }

        drain(codec, output, waitForEos = true)
        return output
    }

    private fun drain(
        codec: MediaCodec,
        output: MutableList<EncodedAudioFrame>,
        waitForEos: Boolean,
    ) {
        val info = MediaCodec.BufferInfo()
        var idlePolls = 0

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, if (waitForEos) 10_000 else 0)
            when (outputIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitForEos || ++idlePolls >= 20) break
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = codec.outputFormat
                    idlePolls = 0
                }
                else -> {
                    if (outputIndex >= 0) {
                        idlePolls = 0
                        val isCodecConfig =
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isCodecConfig && info.size > 0) {
                            codec.getOutputBuffer(outputIndex)?.let { buffer ->
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
                        }

                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (eos) break
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
