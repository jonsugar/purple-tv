package com.purpletv.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.purpletv.R
import com.purpletv.player.ZapList
import com.purpletv.twitch.Category
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.Stream
import com.purpletv.ui.AvatarCache
import com.purpletv.ui.ContentGrid
import com.purpletv.ui.GridAdapter
import com.purpletv.ui.Nav
import com.purpletv.ui.Pager
import com.purpletv.ui.RowItem
import com.purpletv.ui.emptyMessage
import com.purpletv.ui.showStreamLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Sfoglia: a category grid and a grid of the most watched live channels, one tab each. */
class BrowsePage(
    private val context: Context,
    private val scope: CoroutineScope,
    private val helix: HelixClient,
    private val avatars: AvatarCache,
    private val streamPrefs: StreamPreferences,
    private val onTabSelected: (Int) -> Unit,
) {
    val view: View = LayoutInflater.from(context).inflate(R.layout.page_browse, null)

    private val tabCategories: TextView = view.findViewById(R.id.tab_categories)
    private val tabLive: TextView = view.findViewById(R.id.tab_live)
    private val languageControl: TextView = view.findViewById(R.id.browse_language)
    private var language = streamPrefs.language.value
    private var liveAvatars: Job? = null
    private val results: ContentGrid = ContentGrid(view.findViewById(R.id.browse_results), R.string.search_no_results,
        focusAbove = { if (selected === tabLive) languageControl else tabCategories }) {
        if (selected === tabLive) live.loadMore() else categories.loadMore()
    }
    private val grid get() = results.grid

    private val categoriesAdapter: GridAdapter = GridAdapter(CATEGORY_COLUMNS, ::open) {
        if (selected === tabCategories && !categories.hasFailed) categories.loadMore()
    }
    private val liveAdapter: GridAdapter = GridAdapter(STREAM_COLUMNS, ::open) {
        if (selected === tabLive && live.hasLoaded && !live.hasFailed) live.loadMore()
    }

    private val categories: Pager<Category> = Pager(scope, key = { it.id }, fetch = { helix.topCategories(it) }) { items, failed ->
        categoriesAdapter.submitList(items.map { RowItem.CategoryItem(it) })
        if (selected === tabCategories) results.show(items.size, failed)
    }
    private val live: Pager<Stream> = Pager(scope, key = Stream::userId, fetch = { cursor ->
        helix.topStreams(cursor = cursor, language = language)
    }) { items, failed ->
        liveAdapter.submitList(items.sortedByDescending { it.viewers }.map { RowItem.StreamItem(it) })
        if (selected === tabLive) showLiveStatus(failed)
        liveAvatars?.cancel()
        liveAvatars = scope.launch {
            runCatching { avatars.urls(items.map { it.userId }) }.getOrNull()?.let { liveAdapter.avatars = it }
        }
    }

    private var selected: View? = null

    init {
        tabCategories.setOnClickListener { select(tabCategories) }
        tabLive.setOnClickListener { select(tabLive) }
        languageControl.setOnClickListener {
            streamPrefs.toggleLanguage()
            refreshLanguage()
        }
        languageControl.showStreamLanguage(language)
    }

    /** Called each time the page is shown; the first time it opens the categories tab. */
    fun show() {
        refreshLanguage()
        if (selected == null) select(tabCategories)
    }

    private fun refreshLanguage() {
        val selection = streamPrefs.language.value
        if (language == selection) return
        language = selection
        languageControl.showStreamLanguage(selection)
        live.reset()
        liveAvatars?.cancel()
        liveAdapter.submitList(null)
        if (selected === tabLive) {
            grid.scrollToPosition(0)
            results.loading()
            live.loadMore()
        }
    }

    private fun select(tab: TextView) {
        selected = tab
        onTabSelected(tab.id)
        tabCategories.isSelected = tab === tabCategories
        tabLive.isSelected = tab === tabLive
        languageControl.visibility = if (tab === tabLive) View.VISIBLE else View.GONE
        tabLive.nextFocusRightId = if (tab === tabLive) languageControl.id else View.NO_ID
        if (tab === tabCategories) {
            categoriesAdapter.attachTo(grid)
            if (categories.hasLoaded) results.show(categories.currentItems.size, categories.hasFailed) else results.loading()
            if (!categories.hasLoaded || categories.hasFailed) categories.loadMore()
        } else {
            liveAdapter.attachTo(grid)
            if (live.hasLoaded) showLiveStatus(live.hasFailed) else results.loading()
            if (!live.hasLoaded || live.hasFailed) live.loadMore()
        }
    }

    private fun open(item: RowItem) = when (item) {
        is RowItem.StreamItem -> {
            val zap = ZapList(context.getString(R.string.tab_live), liveAdapter.currentList.filterIsInstance<RowItem.StreamItem>().map { it.stream })
            Nav.openStream(context, item.stream.login, item.stream, liveAdapter.avatars[item.stream.userId], zap)
        }
        is RowItem.CategoryItem -> Nav.openCategory(context, item.category)
        is RowItem.ChannelItem -> Nav.openStream(context, item.channel.login)
        RowItem.Skeleton -> Unit
    }

    private fun showLiveStatus(failed: Boolean) = results.show(
        live.currentItems.size, failed, language.emptyMessage(R.string.browse_live_empty),
    )

    private companion object {
        const val CATEGORY_COLUMNS = 7
        const val STREAM_COLUMNS = 4
    }
}
