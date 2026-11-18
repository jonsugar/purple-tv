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
    ghosts: List<Stream> = emptyList(),
    ghostIds: Set<String> = emptySet(),
): List<Stream> {
    // The account's feeds also supply low-viewer streams before their public page is loaded.
    val streams = (top + followed.filter { it.gameId == categoryId } +
        ghosts.filter { it.gameId == categoryId && it.userId in ghostIds }).distinctBy(Stream::userId)
    val matching = language.filter(streams).sortedByDescending { it.viewers }
    val followedIds = followed.mapTo(hashSetOf(), Stream::userId)
    val (pinned, rest) = splitByPins(matching, pins.filter { it.id in followedIds }, Stream::userId)
    val (otherFollowed, unfollowed) = rest.partition { it.userId in followedIds }
    val (ghostFollowed, regular) = unfollowed.partition { it.userId in ghostIds }
    return pinned + otherFollowed + ghostFollowed + regular
}
