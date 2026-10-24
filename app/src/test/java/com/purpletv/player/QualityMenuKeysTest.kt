package com.purpletv.player

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityMenuKeysTest {

    @Test
    fun leftAndRightNeverLeaveTheMenu() {
        // Left from a row used to land on Back, one OK away from leaving the channel.
        assertTrue(menuKeepsKey(KeyEvent.KEYCODE_DPAD_LEFT, row = 2, rows = 4))
        assertTrue(menuKeepsKey(KeyEvent.KEYCODE_DPAD_RIGHT, row = 2, rows = 4))
    }

    @Test
    fun upStopsAtTheFirstRow() {
        assertTrue(menuKeepsKey(KeyEvent.KEYCODE_DPAD_UP, row = 0, rows = 4))
        assertFalse(menuKeepsKey(KeyEvent.KEYCODE_DPAD_UP, row = 1, rows = 4))
    }

    @Test
    fun downStopsAtTheLastRow() {
        assertTrue(menuKeepsKey(KeyEvent.KEYCODE_DPAD_DOWN, row = 3, rows = 4))
        assertFalse(menuKeepsKey(KeyEvent.KEYCODE_DPAD_DOWN, row = 0, rows = 4))
    }

    @Test
    fun okAndBackPassThrough() {
        assertFalse(menuKeepsKey(KeyEvent.KEYCODE_DPAD_CENTER, row = 0, rows = 4))
        assertFalse(menuKeepsKey(KeyEvent.KEYCODE_BACK, row = 0, rows = 4))
    }

    @Test
    fun anArrowWithTheFocusOutsideTheListIsKept() {
        assertTrue(menuKeepsKey(KeyEvent.KEYCODE_DPAD_DOWN, row = -1, rows = 4))
    }
}
