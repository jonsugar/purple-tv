package com.purpletv.ui

import com.purpletv.twitch.Category
import com.purpletv.twitch.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardTypeTest {
    private val stream = RowItem.StreamItem(Stream("a", "a", "A", "t", "g", "G", 1, "x"))

    @Test
    fun aSkeletonIsAStreamCardWaitingForItsStream() {
        // The skeleton cards Home starts with become its first stream cards instead of being thrown away.
        assertEquals(cardType(stream), cardType(RowItem.Skeleton))
        assertNotEquals(cardType(stream), cardType(RowItem.CategoryItem(Category("1", "c", "x"))))
    }

    @Test
    fun spareCardsAreMadeStreamsFirstThenCategories() {
        assertEquals(STREAM_CARD, cardToWarm(spareStreams = 0, spareCategories = 0))
        assertEquals(CATEGORY_CARD, cardToWarm(spareStreams = SPARE_STREAM_CARDS, spareCategories = 0))
        assertNull(cardToWarm(spareStreams = SPARE_STREAM_CARDS, spareCategories = SPARE_CATEGORY_CARDS))
    }
}
