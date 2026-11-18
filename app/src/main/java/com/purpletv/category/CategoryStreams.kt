package com.purpletv.category

import com.purpletv.home.FavouritePin
import com.purpletv.home.splitByPins
import com.purpletv.twitch.Stream
import com.purpletv.twitch.StreamLanguage

internal fun orderCategoryStreams(
    top: List<Stream>,
    followed: List<Stream>,
    categoryId: String,
    pins: List<FavouritePin>,
    language: StreamLanguage,
): List<Stream> {
    // The followed feed also supplies low-viewer streams before their public page is loaded.
    val streams = (top + followed.filter { it.gameId == categoryId }).distinctBy(Stream::userId)
    val matching = language.filter(streams).sortedByDescending { it.viewers }
    val followedIds = followed.mapTo(hashSetOf(), Stream::userId)
    val (pinned, rest) = splitByPins(matching, pins, Stream::userId)
    val (otherFollowed, unfollowed) = rest.partition { it.userId in followedIds }
    return pinned + otherFollowed + unfollowed
}
