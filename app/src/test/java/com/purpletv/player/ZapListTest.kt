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
        assertTrue(zapPanelKeepsKey(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(zapPanelKeepsKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(zapPanelKeepsKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse(zapPanelKeepsKey(KeyEvent.KEYCODE_DPAD_CENTER))
    }
}
