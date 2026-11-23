package com.purpletv.ui

import com.purpletv.twitch.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import com.purpletv.twitch.NotLoggedInException

/**
 * Pages through a Helix list: one request at a time, items deduplicated by [key] (Twitch can
 * repeat an item across pages as viewer counts move), nothing more once the cursor runs out.
 */
class Pager<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> String,
    private val fetch: suspend (cursor: String?) -> Page<T>,
    private val onAuthRequired: (NotLoggedInException) -> Unit = {},
    private val onPage: (items: List<T>, failed: Boolean) -> Unit,
) {
    private val items = LinkedHashMap<String, T>()
    private val seenCursors = mutableSetOf<String>()
    private var cursor: String? = null
    private var finished = false
    private var loading = false
    private var job: Job? = null
    private var generation = 0
    var hasFailed = false
        private set
    var hasLoaded = false
        private set

    val currentItems get() = items.values.toList()

    /** Stops obsolete requests before replacing an account or filter. */
    fun reset() {
        generation++
        job?.cancel()
        items.clear()
        seenCursors.clear()
        cursor = null
        finished = false
        loading = false
        hasFailed = false
        hasLoaded = false
    }

    val isEmpty get() = items.isEmpty()

    fun loadMore() {
        if (loading || finished) return
        loading = true
        hasFailed = false
        val requestGeneration = generation
        job = scope.launch {
            try {
                val previousSize = items.size
                // An empty or duplicate-only page cannot trigger a grid's load-more callback.
                do {
                    val page = fetch(cursor)
                    if (requestGeneration != generation) return@launch
                    if (page.cursor != null && !seenCursors.add(page.cursor)) throw IOException("Repeated pagination cursor")
                    page.items.forEach { item -> val id = key(item); if (id !in items) items[id] = item }
                    cursor = page.cursor
                } while (cursor != null && items.size == previousSize)
                finished = cursor == null
                hasLoaded = true
                onPage(items.values.toList(), false)
            } catch (e: IOException) {
                if (requestGeneration != generation) return@launch
                hasFailed = true
                hasLoaded = true
                onPage(items.values.toList(), true)
                if (e is NotLoggedInException) onAuthRequired(e)
            } finally {
                if (requestGeneration == generation) loading = false
            }
        }
    }
}
