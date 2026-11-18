package com.purpletv.home

import com.purpletv.twitch.Stream
import com.purpletv.twitch.StreamLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class GhostFollowsTest {
    private val live = GhostFollow("1", "zulu", "Zulu")
    private val offline = GhostFollow("2", "alpha", "alpha")
    private val both = GhostFollow("3", "bravo", "Bravo")
    private val english = Stream(live.id, live.login, live.name, "", "", "", 20, "", language = "en")
    private val french = Stream(both.id, both.login, both.name, "", "", "", 100, "", language = "fr")

    @Test fun liveRowsFilterSortAndShowTheSavedGhostAgainAfterTwitchUnfollow() {
        val ids = listOf(live.id, offline.id, both.id)
        val streams = listOf(english, french, english)
        assertEquals(listOf(english), ghostOnlyStreams(streams, ids, listOf(french), StreamLanguage.ALL))
        assertEquals(listOf(french, english), ghostOnlyStreams(streams, ids, emptyList(), StreamLanguage.ALL))
        assertEquals(listOf(english), ghostOnlyStreams(streams, ids, emptyList(), StreamLanguage.ENGLISH))
        assertEquals(emptyList<Stream>(), ghostOnlyStreams(listOf(french), ids, emptyList(), StreamLanguage.ENGLISH))
    }

    @Test fun channelDirectoryKeepsOfflineChannelsAndIsAlphabeticalWithoutLiveLanguageFiltering() {
        val channels = listOf(live, both, offline).map(GhostFollow::channel)
        val directory = ghostOnlyChannels(channels, listOf(both.channel()), listOf(english, french))
        assertEquals(listOf(offline.id, live.id), directory.map { it.id })
        assertEquals(listOf(null, english), directory.map { it.live })
        val refreshed = ghostOnlyChannels(channels, emptyList(), listOf(french))
        assertEquals(listOf(offline.id, both.id, live.id), refreshed.map { it.id })
        assertEquals(listOf(null, french, null), refreshed.map { it.live })
    }
}
