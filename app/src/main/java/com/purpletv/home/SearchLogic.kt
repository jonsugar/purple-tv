package com.purpletv.home

import com.purpletv.twitch.ChannelResult

object SearchLogic {
    private const val MIN_CHARS = 2

    fun shouldSearch(query: String) = query.trim().length >= MIN_CHARS

    fun mergeChannels(
        liveOnly: Result<List<ChannelResult>>,
        allChannels: Result<List<ChannelResult>>,
    ): Result<List<ChannelResult>> {
        if (liveOnly.isFailure && allChannels.isFailure) return allChannels

        val channels = liveOnly.getOrNull().orEmpty() + allChannels.getOrNull().orEmpty()
        return Result.success(liveFirst(channels).distinctBy { it.login })
    }

    /** Live channels first; within each group Twitch's own relevance order stays. */
    fun liveFirst(channels: List<ChannelResult>): List<ChannelResult> = channels.sortedByDescending { it.isLive }
}
