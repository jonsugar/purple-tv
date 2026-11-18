package com.purpletv.category

import com.purpletv.home.FavouritePin
import com.purpletv.twitch.Stream
import com.purpletv.twitch.StreamLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryStreamsTest {
    private val pinFirst = stream("pin-first", 3, "fr")
    private val pinSecond = stream("pin-second", 12, "en")
    private val followedLow = stream("followed-low", 2, "en")
    private val followedHigh = stream("followed-high", 100, "de")
    private val publicHigh = stream("public-high", 1000, "fr")
    private val publicLow = stream("public-low", 300, "en")
    private val unknown = stream("unknown", 1, "")
    private val pins = listOf(pinFirst, pinSecond).map { FavouritePin(it.userId, it.displayName) }
    private val followed = listOf(followedLow, pinSecond, followedHigh, pinFirst, stream("elsewhere", 50, "en", "other"))

    @Test fun priorityAppliesBeforeAndAfterThePublicPageThatContainsThePins() {
        val firstPage = listOf(publicHigh, publicLow)
        val expectedFirst = listOf(pinFirst, pinSecond, followedHigh, followedLow, publicHigh, publicLow)
        assertEquals(expectedFirst, orderCategoryStreams(firstPage, followed, "game", pins, StreamLanguage.ALL))
        val laterPage = listOf(followedHigh, pinSecond, pinFirst, followedLow, unknown)
        assertEquals(expectedFirst + unknown, orderCategoryStreams(firstPage + laterPage, followed, "game", pins, StreamLanguage.ALL))
    }

    @Test fun englishFiltersEveryPriorityGroupAndCanProduceNoMatches() {
        val top = listOf(publicHigh, publicLow, unknown, pinSecond)
        assertEquals(listOf(pinSecond, followedLow, publicLow), orderCategoryStreams(top, followed, "game", pins, StreamLanguage.ENGLISH))
        assertEquals(emptyList<Stream>(), orderCategoryStreams(listOf(publicHigh), listOf(pinFirst, followedHigh),
            "game", pins, StreamLanguage.ENGLISH))
    }

    private fun stream(id: String, viewers: Int, language: String, game: String = "game") =
        Stream(id, id, id, "", game, "", viewers, "", language = language)
}
