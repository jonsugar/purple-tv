package com.purpletv.home

import org.junit.Assert.assertEquals
import org.junit.Test

class BackActionTest {
    @Test
    fun backOnAnotherPageGoesHome() {
        for (screen in listOf(Screen.BROWSE, Screen.SEARCH, Screen.PROFILE)) {
            assertEquals(BackAction.GO_HOME, backAction(screen, homeAtTop = true))
        }
    }

    @Test
    fun backOnAScrolledHomeGoesToTheTop() {
        assertEquals(BackAction.HOME_TOP, backAction(Screen.HOME, homeAtTop = false))
    }

    @Test
    fun backAtTheTopOfHomeLeaves() {
        assertEquals(BackAction.EXIT, backAction(Screen.HOME, homeAtTop = true))
    }
}
