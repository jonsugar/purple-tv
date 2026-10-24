package com.purpletv.ui

import com.purpletv.twitch.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Pages through a Helix list: one request at a time, items deduplicated by [key] (Twitch can
 * repeat an item across pages as viewer counts move), nothing more once the cursor runs out.
 */
class Pager<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> String,
    private val fetch: suspend (cursor: String?) -> Page<T>,
    private val onPage: (items: List<T>, failed: Boolean) -> Unit,
) {
    private val items = LinkedHashMap<String, T>()
    private var cursor: String? = null
    private var finished = false
    private var loading = false

    val isEmpty get() = items.isEmpty()

    fun loadMore() {
        if (loading || finished) return
        loading = true
        scope.launch {
            try {
                val page = fetch(cursor)
                page.items.forEach { items.putIfAbsent(key(it), it) }
                cursor = page.cursor
                finished = page.cursor == null || page.items.isEmpty()
                onPage(items.values.toList(), false)
            } catch (e: IOException) {
                onPage(items.values.toList(), true)
            } finally {
                loading = false
            }
        }
    }
}
