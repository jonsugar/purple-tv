package com.purpletv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameColorTest {
    private val bg = 0xFF18181B.toInt()

    @Test
    fun darkBlueIsLightenedToReadable() {
        assertTrue(contrastRatio(readableNameColor("#0000FF"), bg) >= 4.5)
    }

    @Test
    fun pureRedIsLightenedJustEnough() {
        assertTrue(contrastRatio(readableNameColor("#FF0000"), bg) >= 4.5)
    }

    @Test
    fun aLightColourStaysAsItIs() {
        assertEquals(0xFFBFFF00.toInt(), readableNameColor("#BFFF00"))
    }

    @Test
    fun noColourOrNonsenseUsesTheFallback() {
        assertEquals(0xFFBF94FF.toInt(), readableNameColor(null))
        assertEquals(0xFFBF94FF.toInt(), readableNameColor("rosso"))
    }
}
