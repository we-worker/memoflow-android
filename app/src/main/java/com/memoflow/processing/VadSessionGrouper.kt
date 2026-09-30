package com.memoflow.processing

import com.memoflow.domain.AudioRange

internal fun groupVadSessions(
    ranges: List<AudioRange>,
    mergeSilenceMs: Long,
    durationMs: Long,
): List<AudioRange> {
    if (ranges.isEmpty()) return emptyList()

    val sorted =
        ranges
            .filter { it.endOffsetMs > it.startOffsetMs }
            .sortedBy { it.startOffsetMs }
    if (sorted.isEmpty()) return emptyList()

    val result = mutableListOf<AudioRange>()
    var current = sorted.first()

    for (next in sorted.drop(1)) {
        val silenceGap = next.startOffsetMs - current.endOffsetMs
        current =
            if (silenceGap < mergeSilenceMs) {
                current.copy(
                    startOffsetMs = current.startOffsetMs.coerceAtLeast(0L),
                    endOffsetMs =
                        maxOf(current.endOffsetMs, next.endOffsetMs)
                            .coerceAtMost(durationMs),
                )
            } else {
                result += current
                next
            }
    }

    result += current
    return result
}
