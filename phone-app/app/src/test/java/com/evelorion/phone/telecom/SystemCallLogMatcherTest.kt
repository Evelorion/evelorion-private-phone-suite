package com.evelorion.phone.telecom

import android.provider.CallLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemCallLogMatcherTest {

    private val target = SystemCallLogTarget(
        number = "+86 188-0000-2222",
        kind = "outgoing",
        startedAt = 1_000_000L,
        endedAt = 1_060_000L,
        durationSeconds = 58,
    )

    @Test
    fun choosesClosestMatchingCallWithoutDeletingAdjacentCall() {
        val match = SystemCallLogMatcher.bestMatch(
            target,
            listOf(
                candidate(id = 1, date = 880_000L),
                candidate(id = 2, date = 1_001_000L),
                candidate(id = 3, date = 1_040_000L),
            ),
        )

        assertEquals(2L, match?.id)
    }

    @Test
    fun rejectsDifferentNumberTypeAndDuration() {
        val candidates = listOf(
            candidate(id = 1, number = "18800009999"),
            candidate(id = 2, type = CallLog.Calls.INCOMING_TYPE),
            candidate(id = 3, duration = 80),
        )

        assertNull(SystemCallLogMatcher.bestMatch(target, candidates))
    }

    @Test
    fun matchesFormattedNumberByLastEightDigits() {
        val match = SystemCallLogMatcher.bestMatch(
            target,
            listOf(candidate(id = 7, number = "18800002222")),
        )

        assertEquals(7L, match?.id)
    }

    private fun candidate(
        id: Long,
        number: String = "18800002222",
        type: Int = CallLog.Calls.OUTGOING_TYPE,
        date: Long = 1_000_500L,
        duration: Int = 58,
    ) = SystemCallLogCandidate(id, number, type, date, duration)
}
