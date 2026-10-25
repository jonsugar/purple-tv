package com.purpletv.home

import com.purpletv.twitch.Shelf
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowsTest {

    private fun shelf(rowName: String) = Shelf(rowName, rowName, emptyList(), emptyList(), emptyMap())

    @Test
    fun leavesOutTwitchsFollowedRowSinceHomeAlreadyStartsWithIt() {
        val shelves = listOf(shelf("LiveFollowedChannels"), shelf("TopLiveChannelsYouMayLike"), shelf("TopGamesForYou"))

        assertEquals(listOf("TopLiveChannelsYouMayLike", "TopGamesForYou"), rowsBelowFollowed(shelves).map { it.rowName })
    }
}
