package com.purpletv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameColorTest {
    private val bg = 0xFF18181B.toInt()
    private val fallback = 0xFFF3DB9A.toInt()

    @Test
    fun darkBlueIsLightenedToReadable() {
        assertTrue(contrastRatio(readableNameColor("#0000FF", fallback), bg) >= 4.5)
    }

    @Test
    fun pureRedIsLightenedJustEnough() {
        assertTrue(contrastRatio(readableNameColor("#FF0000", fallback), bg) >= 4.5)
    }

    @Test
    fun aLightColourStaysAsItIs() {
        assertEquals(0xFFBFFF00.toInt(), readableNameColor("#BFFF00", fallback))
    }

    @Test
    fun noColourOrNonsenseUsesTheFallback() {
        assertEquals(fallback, readableNameColor(null, fallback))
        assertEquals(fallback, readableNameColor("rosso", fallback))
    }
}
