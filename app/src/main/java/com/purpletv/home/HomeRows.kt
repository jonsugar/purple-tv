package com.purpletv.home

import com.purpletv.twitch.Shelf
import com.purpletv.ui.RowItem

private const val TWITCH_FOLLOWED_ROW = "LiveFollowedChannels"

/** Twitch's Home rows without its own followed row: Home starts with the full list from Helix. */
fun rowsBelowFollowed(shelves: List<Shelf>): List<Shelf> = shelves.filter { it.rowName != TWITCH_FOLLOWED_ROW }

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
