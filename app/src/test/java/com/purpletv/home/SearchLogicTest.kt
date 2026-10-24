package com.purpletv.home

import com.purpletv.twitch.ChannelResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchLogicTest {

    private fun channel(login: String, live: Boolean) = ChannelResult(login, login, live, "", "", "")

    @Test
    fun searchesFromTwoCharacters() {
        assertFalse(SearchLogic.shouldSearch(""))
        assertFalse(SearchLogic.shouldSearch(" m "))
        assertTrue(SearchLogic.shouldSearch("mi"))
    }

    @Test
    fun liveChannelsComeFirstKeepingTwitchOrder() {
        val sorted = SearchLogic.liveFirst(listOf(
            channel("a", false), channel("b", true), channel("c", false), channel("d", true),
        ))
        assertEquals(listOf("b", "d", "a", "c"), sorted.map { it.login })
    }
}
