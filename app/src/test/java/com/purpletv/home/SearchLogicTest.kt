package com.purpletv.home

import com.purpletv.twitch.ChannelResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchLogicTest {

    private fun channel(login: String, live: Boolean) = ChannelResult(login, login, live, "", "", "")
    private val failure = Result.failure<List<ChannelResult>>(IllegalStateException("Search failed"))

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

    @Test
    fun keepsLiveOnlyResultsWhenAllChannelsFail() {
        val live = listOf(channel("b", true), channel("a", true))

        val result = SearchLogic.mergeChannels(Result.success(live), failure)

        assertEquals(live, result.getOrThrow())
    }

    @Test
    fun keepsAllChannelResultsWhenLiveOnlyFails() {
        val offline = channel("a", false)
        val live = channel("b", true)

        val result = SearchLogic.mergeChannels(failure, Result.success(listOf(offline, live, live)))

        assertEquals(listOf(live, offline), result.getOrThrow())
    }

    @Test
    fun mergesByLoginKeepingLiveMatchesFirstAndRelevanceWithinEachGroup() {
        val firstLive = channel("b", true)
        val secondLive = channel("a", true)
        val thirdLive = channel("c", true)
        val firstOffline = channel("e", false)
        val secondOffline = channel("d", false)
        val result = SearchLogic.mergeChannels(
            Result.success(listOf(firstLive, secondLive)),
            Result.success(listOf(firstOffline, channel("b", false), thirdLive, secondLive, secondOffline)),
        )

        assertEquals(listOf(firstLive, secondLive, thirdLive, firstOffline, secondOffline), result.getOrThrow())
    }

    @Test
    fun successfulEmptyResultSurvivesEitherFailure() {
        val empty = Result.success(emptyList<ChannelResult>())

        assertEquals(empty, SearchLogic.mergeChannels(empty, failure))
        assertEquals(empty, SearchLogic.mergeChannels(failure, empty))
        assertEquals(empty, SearchLogic.mergeChannels(empty, empty))
    }

    @Test
    fun bothFailedRequestsReturnFailure() {
        assertTrue(SearchLogic.mergeChannels(failure, failure).isFailure)
    }
}
