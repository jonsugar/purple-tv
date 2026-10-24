package com.purpletv.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFreshnessTest {

    @Test
    fun loadsWhenNeverLoaded() {
        assertTrue(homeIsStale(loadedAtMs = 0, nowMs = 1_000))
    }

    @Test
    fun keepsRowsYoungerThanFiveMinutes() {
        assertFalse(homeIsStale(loadedAtMs = 10_000, nowMs = 10_000 + 4 * 60_000))
    }

    @Test
    fun reloadsRowsFiveMinutesOld() {
        // Back from standby the next morning, yesterday's live channels lead to "offline".
        assertTrue(homeIsStale(loadedAtMs = 10_000, nowMs = 10_000 + 5 * 60_000))
    }
}
