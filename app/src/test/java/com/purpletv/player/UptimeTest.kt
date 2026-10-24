package com.purpletv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class UptimeTest {
    // 2026-10-24T15:00:00Z in epoch milliseconds.
    private val start = 1_792_854_000_000L

    @Test
    fun formatsHoursMinutesSeconds() {
        val now = start + (2 * 3600 + 4 * 60 + 29) * 1_000L
        assertEquals("2:04:29", uptime("2026-10-24T15:00:00Z", now))
    }

    @Test
    fun formatsMinutesUnderAnHour() {
        assertEquals("0:05:07", uptime("2026-10-24T15:00:00Z", start + 307_000L))
    }

    @Test
    fun emptyForMissingOrBrokenDates() {
        assertEquals("", uptime("", start))
        assertEquals("", uptime("yesterday", start))
    }
}
