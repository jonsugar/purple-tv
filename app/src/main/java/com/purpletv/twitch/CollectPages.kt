package com.purpletv.twitch

import java.io.IOException

/** Used when sorting needs the complete list, rather than just the first API page. */
suspend fun <T> collectPages(key: (T) -> String, fetch: suspend (String?) -> Page<T>): List<T> {
    val items = linkedMapOf<String, T>()
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    do {
        val page = fetch(cursor)
        page.items.forEach { item -> val id = key(item); if (id !in items) items[id] = item }
        cursor = page.cursor
        if (cursor != null && !seen.add(cursor)) throw IOException("Repeated pagination cursor")
    } while (cursor != null)
    return items.values.toList()
}
