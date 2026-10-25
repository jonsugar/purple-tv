package com.purpletv.player

import com.purpletv.twitch.Stream
import com.purpletv.twitch.sized

/** What the tune-in shows while the stream loads: the card that was opened, in large. */
data class TuneInfo(
    val login: String,
    val name: String,
    val title: String,
    val game: String,
    val viewers: Int,
    val thumbnail: String,
    val avatar: String?,
)

/** The card's own thumbnail size, so the picture is already in Coil's memory cache. */
fun Stream.toTuneInfo(avatar: String?) =
    TuneInfo(login, displayName, title, gameName, viewers, sized(thumbnailTemplate, THUMB_W, THUMB_H), avatar)

private const val THUMB_W = 440
private const val THUMB_H = 248
