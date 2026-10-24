package com.purpletv.player

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** How long a stream has been live, "2:04:29", from Helix's started_at; "" when unknown. */
fun uptime(startedAt: String, nowMs: Long): String {
    if (startedAt.isEmpty()) return ""
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    val start = try {
        format.parse(startedAt)?.time ?: return ""
    } catch (e: ParseException) {
        return ""
    }
    val seconds = ((nowMs - start) / 1_000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
}

/** The player's info line as the official TV app shows it: "Counter-Strike | 10:49:19 | 32.534 spettatori". */
fun metaLine(game: String, uptime: String, viewers: String): String =
    listOf(game, uptime, viewers).filter { it.isNotEmpty() }.joinToString(" | ")
