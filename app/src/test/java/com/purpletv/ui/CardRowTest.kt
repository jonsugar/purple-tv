package com.purpletv.ui

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CardRowTest {

    @Test
    fun rightPastTheLastCardStays() {
        // The window-wide search would land on a card of the row below that sits further right.
        assertEquals(RowExit.STAY, rowExit(View.FOCUS_RIGHT, nextInRow = false, nextInOtherRow = true))
        assertEquals(RowExit.STAY, rowExit(View.FOCUS_RIGHT, nextInRow = false, nextInOtherRow = false))
    }

    @Test
    fun movesWithinTheRowAsAndroidFindsThem() {
        assertEquals(RowExit.NEXT, rowExit(View.FOCUS_RIGHT, nextInRow = true, nextInOtherRow = false))
        assertEquals(RowExit.NEXT, rowExit(View.FOCUS_LEFT, nextInRow = true, nextInOtherRow = false))
    }

    @Test
    fun upAndDownEnterTheOtherRowOnItsRememberedCard() {
        // Not the card that happens to sit above or below: the one the user left that row on.
        assertEquals(RowExit.ENTRY, rowExit(View.FOCUS_UP, nextInRow = false, nextInOtherRow = true))
        assertEquals(RowExit.ENTRY, rowExit(View.FOCUS_DOWN, nextInRow = false, nextInOtherRow = true))
    }

    @Test
    fun leavingTheRowsGoesWhereAndroidFindsIt() {
        // Left from the first card reaches the rail; Up from the top row has nothing of its own.
        assertEquals(RowExit.NEXT, rowExit(View.FOCUS_LEFT, nextInRow = false, nextInOtherRow = false))
        assertEquals(RowExit.NEXT, rowExit(View.FOCUS_UP, nextInRow = false, nextInOtherRow = false))
    }

    @Test
    fun rowsOfCardsAreCardRows() {
        // Home, Search and the offline page build their rows from view_row.
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/res/layout/view_row.xml"))
        val lists = doc.getElementsByTagName("*").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
            .filter { it.getAttributeNS("http://schemas.android.com/apk/res/android", "id") == "@+id/row_list" }
        assertEquals(listOf("com.purpletv.ui.CardRow"), lists.map { it.tagName })
    }
}
