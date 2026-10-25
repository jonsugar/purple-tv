package com.purpletv.player

import android.view.KeyEvent
import com.purpletv.twitch.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZapListTest {
    private fun s(login: String) = Stream(login, login, login, "t", "g", "G", 1, "x")

    @Test
    fun focusStartsOnTheChannelOnAir() {
        assertEquals(1, ZapList("Riga", listOf(s("a"), s("b"), s("c"))).focusIndex("B"))
    }

    @Test
    fun focusStartsAtTheFirstChannelWhenTheOneOnAirIsNotInTheRow() {
        assertEquals(0, ZapList("Riga", listOf(s("a"))).focusIndex("z"))
    }

    @Test
    fun anEmptyRowHasNothingToZapTo() {
        assertTrue(ZapList("Riga", emptyList()).isEmpty())
    }

    @Test
    fun upAndDownStayInTheStrip() {
        assertTrue(zapPanelKeepsKey(KeyEvent.KEYCODE_DPAD_UP, index = 1, count = 3))
        assertTrue(zapPanelKeepsKey(KeyEvent.KEYCODE_DPAD_DOWN, index = 1, count = 3))
        assertFalse(zapPanelKeepsKey(KeyEvent.KEYCODE_DPAD_CENTER, index = 1, count = 3))
    }

    @Test
    fun leftStopsAtTheFirstChannel() {
        // Left from the first card used to land on Back, one OK away from leaving the player.
        assertTrue(zapPanelKeepsKey(KeyEvent.KEYCODE_DPAD_LEFT, index = 0, count = 3))
        assertFalse(zapPanelKeepsKey(KeyEvent.KEYCODE_DPAD_LEFT, index = 1, count = 3))
    }

    @Test
    fun rightStopsAtTheLastChannel() {
        assertTrue(zapPanelKeepsKey(KeyEvent.KEYCODE_DPAD_RIGHT, index = 2, count = 3))
        assertFalse(zapPanelKeepsKey(KeyEvent.KEYCODE_DPAD_RIGHT, index = 1, count = 3))
    }

    @Test
    fun sideArrowsWithTheFocusOutsideTheStripAreKept() {
        assertTrue(zapPanelKeepsKey(KeyEvent.KEYCODE_DPAD_LEFT, index = -1, count = 3))
        assertTrue(zapPanelKeepsKey(KeyEvent.KEYCODE_DPAD_RIGHT, index = -1, count = 3))
    }
}
