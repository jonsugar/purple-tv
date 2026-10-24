package com.purpletv.home

import com.purpletv.twitch.ChannelResult

object SearchLogic {
    private const val MIN_CHARS = 2

    fun shouldSearch(query: String) = query.trim().length >= MIN_CHARS

    /** Live channels first; within each group Twitch's own relevance order stays. */
    fun liveFirst(channels: List<ChannelResult>): List<ChannelResult> = channels.sortedByDescending { it.isLive }
}
