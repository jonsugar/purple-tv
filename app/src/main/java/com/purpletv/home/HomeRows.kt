package com.purpletv.home

import com.purpletv.twitch.Shelf
import com.purpletv.twitch.Stream
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
    return listOf(followed.copy(items = Result.success(pinned + regular))) + withoutStreams(rows.drop(1), pinnedIds)
}

internal fun withGhostFollows(
    rows: List<HomeRow>, streams: List<Stream>, savedIds: Set<String>, title: String, avatars: Map<String, String>,
): List<HomeRow> {
    val followed = rows.firstOrNull() ?: return rows
    if (savedIds.isEmpty()) return rows
    val shown = streams.mapTo(hashSetOf(), Stream::userId)
    followed.items.getOrNull().orEmpty().filterIsInstance<RowItem.StreamItem>().forEach {
        if (it.stream.userId in savedIds) shown.add(it.stream.userId)
    }
    val top = if (streams.isEmpty()) listOf(followed)
        else listOf(followed, HomeRow(title, RowKind.STREAMS, Result.success(streams.map { RowItem.StreamItem(it) }), avatars))
    return top + withoutStreams(rows.drop(1), shown)
}

private fun withoutStreams(rows: List<HomeRow>, ids: Set<String>): List<HomeRow> =
    rows.mapNotNull { row ->
        val items = row.items.getOrNull() ?: return@mapNotNull row
        val remaining = items.filterNot { it is RowItem.StreamItem && it.stream.userId in ids }
        when {
            remaining.size == items.size -> row
            remaining.isEmpty() -> null
            else -> row.copy(items = Result.success(remaining))
        }
    }
