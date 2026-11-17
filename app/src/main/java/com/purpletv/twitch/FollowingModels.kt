package com.purpletv.twitch

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class FollowedChannel(
    val id: String,
    val login: String,
    val displayName: String,
    val followedAt: String,
    val avatarUrl: String = "",
    val backgroundUrl: String = "",
    val live: Stream? = null,
)

fun sortedChannels(channels: List<FollowedChannel>): List<FollowedChannel> = channels
    .distinctBy { it.id }
    .sortedWith(compareBy<FollowedChannel> { it.displayName.lowercase(Locale.ROOT) }.thenBy { it.id })

enum class RecordingKind { VOD, CLIP }

/** Metadata only. Expiring playback URLs are resolved when a recording is opened. */
data class Recording(
    val id: String,
    val kind: RecordingKind,
    val broadcasterId: String,
    val title: String,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val views: Int,
    val publishedAt: String,
)

enum class ClipPeriod(val days: Int?) { DAY(1), WEEK(7), MONTH(30), ALL(null) }

/** Fixed UTC boundaries for every page in one filter selection. */
data class ClipWindow(val start: String?, val end: String?)

fun clipWindow(period: ClipPeriod, nowMs: Long): ClipWindow {
    val days = period.days ?: return ClipWindow(null, null)
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return ClipWindow(format.format(Date(nowMs - days * 86_400_000L)), format.format(Date(nowMs)))
}

fun durationSeconds(text: String): Long = Regex("(\\d+)([hms])").findAll(text).sumOf {
    (it.groupValues[1].toLongOrNull() ?: 0L) * when (it.groupValues[2]) { "h" -> 3600L; "m" -> 60L; else -> 1L }
}

fun durationText(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
