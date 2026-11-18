package com.purpletv.home

import com.purpletv.twitch.Shelf
import com.purpletv.twitch.Stream
import com.purpletv.ui.RowItem
import com.purpletv.ui.RowKind
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowsTest {

    private fun shelf(rowName: String) = Shelf(rowName, rowName, emptyList(), emptyList(), emptyMap())

    @Test
    fun leavesOutTwitchsFollowedRowSinceHomeAlreadyStartsWithIt() {
        val shelves = listOf(shelf("LiveFollowedChannels"), shelf("TopLiveChannelsYouMayLike"), shelf("TopGamesForYou"))

        assertEquals(listOf("TopLiveChannelsYouMayLike", "TopGamesForYou"), rowsBelowFollowed(shelves).map { it.rowName })
    }

    @Test
    fun pinnedStreamsKeepTheSameOrderAcrossHomeAndFollowingAndAppearOnce() {
        fun item(id: String) = RowItem.StreamItem(Stream(id, id, id, "", "", "", 0, ""))
        val first = item("first")
        val second = item("second")
        val regular = item("regular")
        val recommended = item("recommended")
        val saved = listOf(FavouritePin("second", "Second"), FavouritePin("first", "First"))
        val followedItems = listOf(regular, first, second)
        val followed = HomeRow("Live followed channels", RowKind.STREAMS, Result.success(followedItems))
        val other = HomeRow("Recommended", RowKind.STREAMS, Result.success(listOf(second, recommended)))
        val expected = listOf(second, first)

        assertEquals(expected to listOf(regular), splitByPins(followedItems, saved) { it.stream.userId })
        val rows = withChannelPins(listOf(followed, other), saved)
        assertEquals(expected + regular, rows[0].items.getOrThrow())
        assertEquals(listOf(recommended), rows[1].items.getOrThrow())
        assertEquals(listOf(followed, other), withChannelPins(listOf(followed, other), emptyList()))
    }
}
