package com.purpletv.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.purpletv.R
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.HomeApi
import com.purpletv.twitch.NotLoggedInException
import com.purpletv.twitch.Shelf
import com.purpletv.ui.AvatarCache
import com.purpletv.ui.Nav
import com.purpletv.ui.RowAdapter
import com.purpletv.ui.RowItem
import com.purpletv.ui.RowKind
import com.purpletv.ui.fixRowHeight
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home like the official TV app: followed live channels first, then the rows Twitch's own TV Home
 * has for the user. Without those, the most watched streams and categories.
 */
class HomePage(
    private val context: Context,
    private val helix: HelixClient,
    private val homeApi: HomeApi,
    private val avatars: AvatarCache,
) {
    val view: View = LayoutInflater.from(context).inflate(R.layout.page_home, null)

    private val clock: TextView = view.findViewById(R.id.home_clock)
    private val clockTicker = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.ITALY)
    private val followed = Row(view.findViewById(R.id.row_followed), context.getString(R.string.row_followed), RowKind.STREAMS)
    private val shelvesBox: LinearLayout = view.findViewById(R.id.home_shelves)
    private var shelfRows = emptyList<Row>()

    private val tick = object : Runnable {
        override fun run() {
            clock.text = clockFormat.format(Date())
            clockTicker.postDelayed(this, (CLOCK_TICK_MS - System.currentTimeMillis() % CLOCK_TICK_MS))
        }
    }

    fun start() = tick.run()

    fun stop() = clockTicker.removeCallbacks(tick)

    /** Loads the rows; only a lost login escapes as [NotLoggedInException]. */
    suspend fun load(userId: String) = coroutineScope {
        val followedStreams = async { runCatching { helix.followedStreams(userId) } }
        val shelves = async { runCatching { homeApi.shelves() } }
        val followedResult = followedStreams.await()
        (followedResult.exceptionOrNull() as? NotLoggedInException)?.let { throw it }
        val twitchRows = shelves.await()
            .onFailure { Log.w(TAG, "home rows unavailable: ${it.message}") }
            .getOrDefault(emptyList())
        twitchRows.forEach {
            Log.i(TAG, "home row ${it.rowName} \"${it.title}\" streams=${it.streams.size} categories=${it.categories.size}")
        }
        val below = rowsBelowFollowed(twitchRows)
        val rows = if (below.isNotEmpty()) below.map(::shelfRow) else helixRows()

        followed.show(followedResult.map { list -> list.map { RowItem.StreamItem(it) } }, R.string.followed_empty)
        showRows(rows)
        (if (followed.hasItems) followed else shelfRows.firstOrNull())?.focusFirst()

        // Twitch's rows carry their avatars; the followed row and the fallback ask Helix for them.
        val bare = rows.indices.filter { rows[it].avatars.isEmpty() }
        val ids = followedResult.getOrDefault(emptyList()).map { it.userId } + bare.flatMap { i ->
            rows[i].items.getOrDefault(emptyList()).mapNotNull { (it as? RowItem.StreamItem)?.stream?.userId }
        }
        runCatching { avatars.urls(ids) }.getOrNull()?.let { urls ->
            followed.adapter.avatars = urls
            bare.forEach { shelfRows[it].adapter.avatars = urls }
        }
    }

    fun showError() {
        followed.show(Result.failure(RuntimeException()))
    }

    private class RowData(val title: String, val kind: RowKind, val items: Result<List<RowItem>>, val avatars: Map<String, String> = emptyMap())

    private fun shelfRow(shelf: Shelf): RowData =
        if (shelf.streams.isNotEmpty()) RowData(shelf.title, RowKind.STREAMS, Result.success(shelf.streams.map { RowItem.StreamItem(it) }), shelf.avatars)
        else RowData(shelf.title, RowKind.CATEGORIES, Result.success(shelf.categories.map { RowItem.CategoryItem(it) }))

    /** The rows Home had before Twitch's own: the most watched streams and categories. */
    private suspend fun helixRows(): List<RowData> = coroutineScope {
        val topStreams = async { runCatching { helix.topStreams().items } }
        val topCategories = async { runCatching { helix.topCategories().items } }
        listOf(topStreams.await(), topCategories.await())
            .firstNotNullOfOrNull { it.exceptionOrNull() as? NotLoggedInException }?.let { throw it }
        listOf(
            RowData(context.getString(R.string.row_top), RowKind.STREAMS, topStreams.await().map { list -> list.map { RowItem.StreamItem(it) } }),
            RowData(context.getString(R.string.row_categories), RowKind.CATEGORIES, topCategories.await().map { list -> list.map { RowItem.CategoryItem(it) } }),
        )
    }

    private fun showRows(rows: List<RowData>) {
        shelvesBox.removeAllViews()
        shelfRows = rows.map { data ->
            val root = LayoutInflater.from(context).inflate(R.layout.view_row, shelvesBox, false)
            shelvesBox.addView(root)
            Row(root, data.title, data.kind).apply {
                show(data.items)
                adapter.avatars = data.avatars
            }
        }
    }

    private inner class Row(root: View, title: String, kind: RowKind) {
        private val empty: TextView = root.findViewById(R.id.row_empty)
        private val list: RecyclerView = root.findViewById(R.id.row_list)
        val adapter = RowAdapter { item ->
            when (item) {
                is RowItem.StreamItem -> Nav.openStream(context, item.stream.login, item.stream)
                is RowItem.CategoryItem -> Nav.openCategory(context, item.category)
                is RowItem.ChannelItem -> Nav.openStream(context, item.channel.login)
            }
        }
        val hasItems get() = adapter.itemCount > 0

        init {
            root.findViewById<TextView>(R.id.row_title).text = title
            list.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            list.adapter = adapter
            list.itemAnimator = null
            list.fixRowHeight(kind)
        }

        fun show(items: Result<List<RowItem>>, emptyText: Int = R.string.row_error) {
            val value = items.getOrNull()
            adapter.submitList(value.orEmpty())
            empty.visibility = if (value.isNullOrEmpty()) View.VISIBLE else View.GONE
            empty.setText(if (value == null) R.string.row_error else emptyText)
        }

        fun focusFirst() {
            list.post { list.layoutManager?.findViewByPosition(0)?.requestFocus() }
        }
    }

    private companion object {
        const val CLOCK_TICK_MS = 60_000L
        const val TAG = "PurpleTV"
    }
}
