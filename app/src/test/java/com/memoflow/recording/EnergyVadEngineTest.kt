package com.memoflow.recording

import com.memoflow.domain.AudioFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVadEngineTest {
    @Test
    fun speechRangeUsesChunkRelativeOffsets() = runBlocking {
        val vad = EnergyVadEngine(threshold = 0.01)
        val base = 123_456_000_000L

        assertTrue(vad.process(frame(amplitude = 0, timestampNs = base)).isEmpty())
        assertTrue(vad.process(frame(amplitude = 4000, timestampNs = base + 100_000_000L)).isEmpty())
        assertTrue(vad.process(frame(amplitude = 4000, timestampNs = base + 250_000_000L)).isEmpty())

        val ranges = vad.process(frame(amplitude = 0, timestampNs = base + 400_000_000L))
        assertEquals(1, ranges.size)
        assertEquals(100L, ranges.single().startOffsetMs)
        assertEquals(400L, ranges.single().endOffsetMs)
    }

    @Test
    fun resetStartsOffsetsFromZeroAgain() = runBlocking {
        val vad = EnergyVadEngine(threshold = 0.01)
        val firstBase = 10_000_000_000L
        vad.process(frame(4000, firstBase))
        vad.process(frame(0, firstBase + 200_000_000L))

        vad.reset()

        val secondBase = 90_000_000_000L
        vad.process(frame(4000, secondBase))
        val ranges = vad.process(frame(0, secondBase + 120_000_000L))
        assertEquals(0L, ranges.single().startOffsetMs)
        assertEquals(120L, ranges.single().endOffsetMs)
    }

    private fun frame(amplitude: Int, timestampNs: Long): AudioFrame {
        val pcm = ByteArray(3200)
        var index = 0
        while (index < pcm.size) {
            val sample = amplitude.toShort().toInt()
            pcm[index] = (sample and 0xff).toByte()
            pcm[index + 1] = ((sample shr 8) and 0xff).toByte()
            index += 2
        }
        return AudioFrame(pcm, timestampNs, sampleRate = 16000, channels = 1)
    }
}
