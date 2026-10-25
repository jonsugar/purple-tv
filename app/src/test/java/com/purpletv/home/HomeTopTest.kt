package com.purpletv.home

import com.purpletv.twitch.Stream
import com.purpletv.ui.RowItem
import com.purpletv.ui.RowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTopTest {
    private fun row(vararg items: RowItem) = HomeRow("Riga", RowKind.STREAMS, Result.success(items.toList()))
    private val card = RowItem.StreamItem(Stream("a", "a", "A", "t", "g", "G", 1, "x"))

    @Test
    fun theTopRowIsTheFirstWithCards() {
        // Nobody followed is live: Home starts at the row below the empty one.
        assertEquals(1, topRow(listOf(row(), row(card))))
        assertEquals(1, topRow(listOf(row(RowItem.Skeleton), row(card))))
        assertEquals(0, topRow(listOf(row(card), row(card))))
    }

    @Test
    fun homeIsAtTopOnTheFirstCardOfTheTopRow() {
        assertTrue(homeAtTop(topRow = 1, focusedRow = 1, onFirstCard = true))
        assertFalse(homeAtTop(topRow = 1, focusedRow = 1, onFirstCard = false))
        assertFalse(homeAtTop(topRow = 1, focusedRow = 2, onFirstCard = true))
    }

    @Test
    fun aHomeWithoutCardsIsAlwaysAtTop() {
        // Rows that failed to load leave nothing to go back to: Back closes the app.
        assertTrue(homeAtTop(topRow = -1, focusedRow = -1, onFirstCard = false))
    }
}
