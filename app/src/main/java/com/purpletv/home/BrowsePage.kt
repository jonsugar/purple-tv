package com.purpletv.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.purpletv.R
import com.purpletv.twitch.Category
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.Stream
import com.purpletv.ui.AvatarCache
import com.purpletv.ui.GridAdapter
import com.purpletv.ui.Nav
import com.purpletv.ui.Pager
import com.purpletv.ui.RowItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Sfoglia: a category grid and a grid of the most watched live channels, one tab each. */
class BrowsePage(
    private val context: Context,
    private val scope: CoroutineScope,
    private val helix: HelixClient,
    private val avatars: AvatarCache,
) {
    val view: View = LayoutInflater.from(context).inflate(R.layout.page_browse, null)

    private val grid: RecyclerView = view.findViewById(R.id.browse_grid)
    private val status: TextView = view.findViewById(R.id.browse_status)
    private val tabCategories: TextView = view.findViewById(R.id.tab_categories)
    private val tabLive: TextView = view.findViewById(R.id.tab_live)

    private val categoriesAdapter: GridAdapter = GridAdapter(CATEGORY_COLUMNS, ::open) { categories.loadMore() }
    private val liveAdapter: GridAdapter = GridAdapter(STREAM_COLUMNS, ::open) { live.loadMore() }

    private val categories: Pager<Category> = Pager(scope, key = { it.id }, fetch = { helix.topCategories(it) }) { items, failed ->
        categoriesAdapter.submitList(items.map { RowItem.CategoryItem(it) })
        showStatus(items.isEmpty() && failed)
    }
    private val live: Pager<Stream> = Pager(scope, key = { it.userId }, fetch = { helix.topStreams(cursor = it) }) { items, failed ->
        liveAdapter.submitList(items.map { RowItem.StreamItem(it) })
        showStatus(items.isEmpty() && failed)
        scope.launch { runCatching { avatars.urls(items.map { it.userId }) }.getOrNull()?.let { liveAdapter.avatars = it } }
    }

    private var selected: View? = null

    init {
        tabCategories.setOnClickListener { select(tabCategories) }
        tabLive.setOnClickListener { select(tabLive) }
    }

    /** Called each time the page is shown; the first time it opens the categories tab. */
    fun show() {
        if (selected == null) select(tabCategories)
    }

    private fun select(tab: TextView) {
        selected = tab
        tabCategories.isSelected = tab === tabCategories
        tabLive.isSelected = tab === tabLive
        status.visibility = View.GONE
        if (tab === tabCategories) {
            categoriesAdapter.attachTo(grid)
            if (categories.isEmpty) categories.loadMore()
        } else {
            liveAdapter.attachTo(grid)
            if (live.isEmpty) live.loadMore()
        }
    }

    private fun open(item: RowItem) = when (item) {
        is RowItem.StreamItem -> Nav.openStream(context, item.stream.login, item.stream)
        is RowItem.CategoryItem -> Nav.openCategory(context, item.category)
        is RowItem.ChannelItem -> Nav.openStream(context, item.channel.login)
    }

    private fun showStatus(error: Boolean) {
        status.visibility = if (error) View.VISIBLE else View.GONE
        status.setText(R.string.row_error)
    }

    private companion object {
        const val CATEGORY_COLUMNS = 7
        const val STREAM_COLUMNS = 4
    }
}
