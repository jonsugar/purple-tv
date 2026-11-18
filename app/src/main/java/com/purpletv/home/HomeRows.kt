package com.purpletv.home

import com.purpletv.twitch.Shelf
import com.purpletv.twitch.StreamLanguage
import com.purpletv.ui.RowItem
import com.purpletv.ui.RowKind
import com.purpletv.ui.emptyMessage

private const val TWITCH_FOLLOWED_ROW = "LiveFollowedChannels"

/** Twitch's Home rows without its own followed row: Home starts with the full list from Helix. */
fun rowsBelowFollowed(shelves: List<Shelf>): List<Shelf> = shelves.filter { it.rowName != TWITCH_FOLLOWED_ROW }

internal fun filterLiveRows(rows: List<HomeRow>, language: StreamLanguage): List<HomeRow> {
    if (language == StreamLanguage.ALL) return rows
    return rows.mapIndexedNotNull { index, row ->
        if (row.kind != RowKind.STREAMS) return@mapIndexedNotNull row
        val items = row.items.getOrNull() ?: return@mapIndexedNotNull row
        val matching = items.filter { it !is RowItem.StreamItem || language.matches(it.stream) }
        when {
            matching.isEmpty() && index > 0 -> null
            matching.isNotEmpty() && matching.size == items.size -> row
            else -> row.copy(items = Result.success(matching), emptyText = language.emptyMessage(row.emptyText))
        }
    }
}

internal fun withChannelPins(rows: List<HomeRow>, pins: List<FavouritePin>): List<HomeRow> {
    val followed = rows.firstOrNull() ?: return rows
    val streams = followed.items.getOrNull()?.filterIsInstance<RowItem.StreamItem>().orEmpty()
    val (pinned, regular) = splitByPins(streams, pins) { it.stream.userId }
    if (pinned.isEmpty()) return rows
    val pinnedIds = pinned.mapTo(hashSetOf()) { it.stream.userId }
    val lower = rows.drop(1).mapNotNull { row ->
        val items = row.items.getOrNull() ?: return@mapNotNull row
        val remaining = items.filterNot { it is RowItem.StreamItem && it.stream.userId in pinnedIds }
        when {
            remaining.size == items.size -> row
            remaining.isEmpty() -> null
            else -> row.copy(items = Result.success(remaining))
        }
    }
    return listOf(followed.copy(items = Result.success(pinned + regular))) + lower
}
