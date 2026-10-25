package com.purpletv.home

import com.purpletv.player.uptime
import com.purpletv.twitch.Category
import com.purpletv.twitch.Stream
import com.purpletv.twitch.sized

/** What the top of Home shows for the focused card. [viewers] is null for a category. */
data class HeroInfo(
    val title: String,
    val name: String,
    val game: String,
    val viewers: Int?,
    val uptime: String,
    val backdrop: String,
    val avatar: String?,
    val live: Boolean,
)

private const val BACKDROP_W = 1280
private const val BACKDROP_H = 720
private const val ART_W = 600
private const val ART_H = 800

fun heroFor(stream: Stream, avatar: String?, nowMs: Long): HeroInfo = HeroInfo(
    title = stream.title,
    name = stream.displayName,
    game = stream.gameName,
    viewers = stream.viewers,
    uptime = uptime(stream.startedAt, nowMs),
    backdrop = sized(stream.thumbnailTemplate, BACKDROP_W, BACKDROP_H),
    avatar = avatar,
    live = true,
)

fun heroFor(category: Category): HeroInfo = HeroInfo(
    title = category.name,
    name = "",
    game = "",
    viewers = null,
    uptime = "",
    backdrop = sized(category.boxArtTemplate, ART_W, ART_H),
    avatar = null,
    live = false,
)
