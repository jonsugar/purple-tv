package com.purpletv.player

import com.purpletv.twitch.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineSuggestionsTest {
    private fun s(login: String) = Stream(login, login, login, "t", "g", "G", 1, "x")

    @Test
    fun leavesOutTheOfflineChannelAndKeepsFour() {
        assertEquals(
            listOf("a", "c", "d", "e"),
            offlineSuggestions(listOf(s("a"), s("b"), s("c"), s("d"), s("e"), s("f")), "B").map { it.login },
        )
    }

    @Test
    fun nothingLiveMeansNoSuggestions() {
        assertEquals(emptyList<Stream>(), offlineSuggestions(emptyList(), "b"))
    }
}
