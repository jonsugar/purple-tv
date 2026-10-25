package com.purpletv.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTest {
    @Test
    fun readsTheScreenByName() {
        assertEquals(Screen.SEARCH, screenFrom("SEARCH"))
    }

    @Test
    fun anythingElseIsHome() {
        assertEquals(Screen.HOME, screenFrom(null))
        assertEquals(Screen.HOME, screenFrom("nope"))
    }
}
