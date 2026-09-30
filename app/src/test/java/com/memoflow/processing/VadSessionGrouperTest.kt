package com.memoflow.processing

import com.memoflow.domain.AudioRange
import org.junit.Assert.assertEquals
import org.junit.Test

class VadSessionGrouperTest {
    private fun range(startMs: Long, endMs: Long) =
        AudioRange(
            chunkId = "test",
            startOffsetMs = startMs,
            endOffsetMs = endMs,
            modelId = "silero",
        )

    @Test
    fun tenSecondPauseStaysInSameSession() {
        val grouped =
            groupVadSessions(
                ranges = listOf(range(0, 5_000), range(15_000, 20_000)),
                mergeSilenceMs = 5 * 60_000L,
                durationMs = 10 * 60_000L,
            )

        assertEquals(1, grouped.size)
        assertEquals(0L, grouped[0].startOffsetMs)
        assertEquals(20_000L, grouped[0].endOffsetMs)
    }

    @Test
    fun fourMinutesFiftyNineSecondsStillMerges() {
        val grouped =
            groupVadSessions(
                ranges =
                    listOf(
                        range(0, 1_000),
                        range(1_000 + 4 * 60_000L + 59_000L, 310_000L),
                    ),
                mergeSilenceMs = 5 * 60_000L,
                durationMs = 10 * 60_000L,
            )

        assertEquals(1, grouped.size)
    }

    @Test
    fun moreThanFiveMinutesStartsNewSession() {
        val grouped =
            groupVadSessions(
                ranges =
                    listOf(
                        range(0, 1_000),
                        range(1_000 + 5 * 60_000L + 1_000L, 320_000L),
                    ),
                mergeSilenceMs = 5 * 60_000L,
                durationMs = 10 * 60_000L,
            )

        assertEquals(2, grouped.size)
    }
}
