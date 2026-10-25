package com.purpletv.home

import com.purpletv.ui.RowItem

/** What Back does: other pages return to Home, Home returns to its start, and only then the app closes. */
enum class BackAction { GO_HOME, HOME_TOP, EXIT }

fun backAction(screen: Screen, homeAtTop: Boolean): BackAction = when {
    screen != Screen.HOME -> BackAction.GO_HOME
    !homeAtTop -> BackAction.HOME_TOP
    else -> BackAction.EXIT
}

/** Home's start: the first row with cards. An empty followed row above it stays out of view. */
fun topRow(rows: List<HomeRow>): Int =
    rows.indexOfFirst { row -> row.items.getOrNull().orEmpty().any { it !is RowItem.Skeleton } }

/** At its start Home has the top row's first card focused; with no cards there is nothing to go back to. */
fun homeAtTop(topRow: Int, focusedRow: Int, onFirstCard: Boolean): Boolean =
    topRow < 0 || (onFirstCard && focusedRow == topRow)
