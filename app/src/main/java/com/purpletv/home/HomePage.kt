package com.purpletv.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.purpletv.R
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.NotLoggedInException
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

/** Home: followed live channels, the most watched streams and popular categories. */
class HomePage(
    private val context: Context,
    private val helix: HelixClient,
    private val avatars: AvatarCache,
) {
    val view: View = LayoutInflater.from(context).inflate(R.layout.page_home, null)

    private val clock: TextView = view.findViewById(R.id.home_clock)
    private val clockTicker = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.ITALY)
    private val followed = Row(view.findViewById(R.id.row_followed), R.string.row_followed, RowKind.STREAMS)
    private val top = Row(view.findViewById(R.id.row_top), R.string.row_top, RowKind.STREAMS)
    private val categories = Row(view.findViewById(R.id.row_categories), R.string.row_categories, RowKind.CATEGORIES)

    private val tick = object : Runnable {
        override fun run() {
            clock.text = clockFormat.format(Date())
            clockTicker.postDelayed(this, (CLOCK_TICK_MS - System.currentTimeMillis() % CLOCK_TICK_MS))
        }
    }

    fun start() = tick.run()

    fun stop() = clockTicker.removeCallbacks(tick)

    /** Loads the three rows; only a lost login escapes as [NotLoggedInException]. */
    suspend fun load(userId: String) = coroutineScope {
        val followedStreams = async { runCatching { helix.followedStreams(userId) } }
        val topStreams = async { runCatching { helix.topStreams().items } }
        val topCategories = async { runCatching { helix.topCategories().items } }
        val results = listOf(followedStreams.await(), topStreams.await(), topCategories.await())
        results.firstNotNullOfOrNull { it.exceptionOrNull() as? NotLoggedInException }?.let { throw it }

        followed.show(followedStreams.await().map { list -> list.map { RowItem.StreamItem(it) } }, R.string.followed_empty)
        top.show(topStreams.await().map { list -> list.map { RowItem.StreamItem(it) } })
        categories.show(topCategories.await().map { list -> list.map { RowItem.CategoryItem(it) } })
        (if (followed.hasItems) followed else top).focusFirst()

        val ids = (followedStreams.await().getOrDefault(emptyList()) + topStreams.await().getOrDefault(emptyList()))
            .map { it.userId }
        runCatching { avatars.urls(ids) }.getOrNull()?.let { urls ->
            followed.adapter.avatars = urls
            top.adapter.avatars = urls
        }
    }

    fun showError() {
        followed.show(Result.failure(RuntimeException()))
    }

    private inner class Row(root: View, title: Int, kind: RowKind) {
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
            root.findViewById<TextView>(R.id.row_title).setText(title)
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
    }
}
