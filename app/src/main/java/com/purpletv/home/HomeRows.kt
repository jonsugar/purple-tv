package com.purpletv.home

import com.purpletv.twitch.Shelf

private const val TWITCH_FOLLOWED_ROW = "LiveFollowedChannels"

/** Twitch's Home rows without its own followed row: Home starts with the full list from Helix. */
fun rowsBelowFollowed(shelves: List<Shelf>): List<Shelf> = shelves.filter { it.rowName != TWITCH_FOLLOWED_ROW }
