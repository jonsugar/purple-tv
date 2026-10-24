package com.purpletv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun backsOffAndCaps() {
        val policy = RetryPolicy()
        assertEquals(2_000L, policy.delayFor(0))
        assertEquals(4_000L, policy.delayFor(1))
        assertEquals(8_000L, policy.delayFor(2))
        assertEquals(30_000L, policy.delayFor(10))
    }
}
