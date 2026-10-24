package com.purpletv.twitch

data class Stream(
    val userId: String,
    val login: String,
    val displayName: String,
    val title: String,
    val gameId: String,
    val gameName: String,
    val viewers: Int,
    val thumbnailTemplate: String,
)

data class Category(val id: String, val name: String, val boxArtTemplate: String)

data class User(val id: String, val login: String, val displayName: String, val profileImageUrl: String)

data class ChannelResult(
    val login: String,
    val displayName: String,
    val isLive: Boolean,
    val gameName: String,
    val title: String,
    val profileImageUrl: String,
)

data class Page<T>(val items: List<T>, val cursor: String?)

/** Fills a Helix image template (`{width}x{height}` or `%{width}x%{height}`). */
fun sized(template: String, width: Int, height: Int): String = template
    .replace("%{width}", width.toString()).replace("%{height}", height.toString())
    .replace("{width}", width.toString()).replace("{height}", height.toString())

/** Viewer counts the Italian way: 950, 1,2K, 12,3K, 1,2 Mln (rounded down, ",0" dropped). */
fun formatViewers(n: Int): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> oneDecimal(n, 1_000) + "K"
    else -> oneDecimal(n, 1_000_000) + " Mln"
}

private fun oneDecimal(n: Int, unit: Int): String {
    val tenths = n.toLong() * 10 / unit
    return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10},${tenths % 10}"
}
