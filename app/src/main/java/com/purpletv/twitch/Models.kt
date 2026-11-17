package com.purpletv.twitch

import java.text.NumberFormat
import java.util.Locale

data class Stream(
    val userId: String,
    val login: String,
    val displayName: String,
    val title: String,
    val gameId: String,
    val gameName: String,
    val viewers: Int,
    val thumbnailTemplate: String,
    /** ISO-8601 UTC, "" when Helix leaves it out. */
    val startedAt: String = "",
)

data class Category(val id: String, val name: String, val boxArtTemplate: String, val viewers: Int? = null)

data class User(
    val id: String,
    val login: String,
    val displayName: String,
    val profileImageUrl: String,
    /** The picture the channel shows while offline; "" when it has none. */
    val offlineImageUrl: String = "",
)

data class ChannelResult(
    val login: String,
    val displayName: String,
    val isLive: Boolean,
    val gameName: String,
    val title: String,
    val profileImageUrl: String,
)

/** A live stream as the channel card that shows it. */
fun Stream.asChannel(avatar: String?) = ChannelResult(login, displayName, true, gameName, title, avatar.orEmpty())

data class Page<T>(val items: List<T>, val cursor: String?)

/** Fills a Helix image template (`{width}x{height}` or `%{width}x%{height}`). */
fun sized(template: String, width: Int, height: Int): String = template
    .replace("%{width}", width.toString()).replace("%{height}", height.toString())
    .replace("{width}", width.toString()).replace("{height}", height.toString())

/**
 * Short viewer counts: 950, 1.2K, 12.3K, 1.2M (rounded down, ".0" dropped). The separator and the
 * millions suffix come from the language: Italian writes 1,2K and 1,2 Mln.
 */
fun formatViewers(n: Int, decimalSeparator: Char = '.', million: String = "M"): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> oneDecimal(n, 1_000, decimalSeparator) + "K"
    else -> oneDecimal(n, 1_000_000, decimalSeparator) + million
}

/** A count in full with the language's grouping: 32,534 in English, 32.534 in Italian. */
fun fullCount(n: Int, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(n)

private fun oneDecimal(n: Int, unit: Int, separator: Char): String {
    val tenths = n.toLong() * 10 / unit
    return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}$separator${tenths % 10}"
}
