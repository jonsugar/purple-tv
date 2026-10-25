package com.purpletv.home

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.purpletv.R
import com.purpletv.twitch.Category
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.asChannel
import com.purpletv.ui.Nav
import com.purpletv.ui.RowAdapter
import com.purpletv.ui.RowItem
import com.purpletv.ui.RowKind
import com.purpletv.ui.SearchField
import com.purpletv.ui.fixRowHeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cerca: channels (live first) and categories, 400 ms after the last keystroke. With the field
 * (nearly) empty, the recent searches, categories Twitch suggests and the followed channels live.
 */
class SearchPage(
    private val context: Context,
    private val scope: CoroutineScope,
    private val helix: HelixClient,
    private val snapshot: HomeSnapshot,
    private val recent: RecentSearches,
) {
    val view: View = LayoutInflater.from(context).inflate(R.layout.page_search, null)

    private val field: SearchField = view.findViewById(R.id.search_field)
    private val channels = Row(view.findViewById(R.id.search_channels), R.string.row_channels, RowKind.CHANNELS)
    private val categories = Row(view.findViewById(R.id.search_categories), R.string.row_search_categories, RowKind.CATEGORIES)
    private val empty: View = view.findViewById(R.id.search_empty)
    private val recentTitle: View = view.findViewById(R.id.search_recent_title)
    private val recentScroll: View = view.findViewById(R.id.search_recent_scroll)
    private val recentChips: LinearLayout = view.findViewById(R.id.search_recent)
    private val suggest = Row(view.findViewById(R.id.search_suggest), R.string.search_suggest_categories, RowKind.CATEGORIES)
    private val followed = Row(view.findViewById(R.id.search_followed), R.string.row_followed, RowKind.CHANNELS)
    private var topCategories: List<Category>? = null
    private var focusResults = false
    private var pending: Job? = null

    init {
        channels.root.visibility = View.GONE
        categories.root.visibility = View.GONE
        field.doAfterTextChanged { schedule(it?.toString().orEmpty()) }
        field.onDownFromField = {
            when {
                empty.visibility == View.VISIBLE -> focusEmptyState()
                channels.root.visibility == View.VISIBLE -> channels.focusFirst()
                else -> categories.focusFirst()
            }
        }
        field.setOnEditorActionListener { _, actionId, event ->
            val search = actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (search) {
                field.closeKeyboard()
                channels.focusFirst()
            }
            search
        }
    }

    /** Each time Cerca opens: the empty state follows the latest searches and Home. */
    fun show() {
        if (!SearchLogic.shouldSearch(field.text.toString())) showEmptyState()
    }

    private fun schedule(query: String) {
        pending?.cancel()
        if (!SearchLogic.shouldSearch(query)) {
            channels.root.visibility = View.GONE
            categories.root.visibility = View.GONE
            showEmptyState()
            return
        }
        empty.visibility = View.GONE
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            // Twitch ranks exact-name offline accounts first; asking for live ones too keeps them on top.
            val liveChannels = async { runCatching { helix.searchChannels(query.trim(), liveOnly = true) } }
            val allChannels = async { runCatching { helix.searchChannels(query.trim()) } }
            val foundChannels = liveChannels.await().fold(
                onSuccess = { live -> allChannels.await().map { SearchLogic.liveFirst((live + it).distinctBy { c -> c.login }) } },
                onFailure = { allChannels.await().map { SearchLogic.liveFirst(it) } },
            )
            val foundCategories = async { runCatching { helix.searchCategories(query.trim()) } }
            channels.show(foundChannels.map { list -> list.map { RowItem.ChannelItem(it) } })
            categories.show(foundCategories.await().map { list -> list.map { RowItem.CategoryItem(it) } })
            if (focusResults) {
                focusResults = false
                (if (foundChannels.getOrNull().isNullOrEmpty()) categories else channels).focusFirst()
            }
        }
    }

    private fun showEmptyState() {
        empty.visibility = View.VISIBLE
        val queries = recent.list()
        recentChips.removeAllViews()
        for (query in queries) {
            val chip = LayoutInflater.from(context).inflate(R.layout.item_recent_chip, recentChips, false) as TextView
            chip.text = query
            chip.setOnClickListener { searchAgain(query) }
            recentChips.addView(chip)
        }
        recentTitle.visibility = if (queries.isEmpty()) View.GONE else View.VISIBLE
        recentScroll.visibility = recentTitle.visibility
        followed.showOrHide(snapshot.followedLive.map { RowItem.ChannelItem(it.asChannel(snapshot.avatars[it.userId])) })
        val suggested = snapshot.recommendedCategories.ifEmpty { topCategories.orEmpty() }
        suggest.showOrHide(suggested.map { RowItem.CategoryItem(it) })
        if (suggested.isEmpty()) scope.launch {
            // Without Twitch's own rows from Home, the most watched categories.
            runCatching { helix.topCategories().items }.onSuccess { top ->
                topCategories = top
                if (empty.visibility == View.VISIBLE) suggest.showOrHide(top.map { RowItem.CategoryItem(it) })
            }
        }
    }

    private fun focusEmptyState() {
        val chip = recentChips.getChildAt(0)
        when {
            chip != null -> chip.requestFocus()
            suggest.root.visibility == View.VISIBLE -> suggest.focusFirst()
            else -> followed.focusFirst()
        }
    }

    /** A recent chip: its query goes back in the field, and the focus to the results once they land. */
    private fun searchAgain(query: String) {
        focusResults = true
        field.requestFocus() // the chip goes away with the empty state
        field.setText(query)
        field.setSelection(query.length)
        field.closeKeyboard()
    }

    private fun open(item: RowItem) {
        val opened = when (item) {
            is RowItem.ChannelItem -> {
                if (item.channel.isLive) Nav.openStream(context, item.channel.login)
                else Toast.makeText(context, context.getString(R.string.channel_offline, item.channel.displayName), Toast.LENGTH_SHORT).show()
                item.channel.isLive
            }
            is RowItem.CategoryItem -> { Nav.openCategory(context, item.category); true }
            is RowItem.StreamItem -> { Nav.openStream(context, item.stream.login, item.stream); true }
            RowItem.Skeleton -> false
        }
        // Only a search that led somewhere is worth keeping; the empty state's own cards add nothing.
        if (opened) recent.add(field.text.toString())
    }

    private inner class Row(val root: View, title: Int, kind: RowKind) {
        private val empty: TextView = root.findViewById(R.id.row_empty)
        private val list: RecyclerView = root.findViewById(R.id.row_list)
        private val adapter = RowAdapter(::open)

        init {
            root.findViewById<TextView>(R.id.row_title).setText(title)
            list.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            list.adapter = adapter
            list.itemAnimator = null
            list.fixRowHeight(kind)
        }

        fun show(items: Result<List<RowItem>>) {
            val value = items.getOrNull()
            root.visibility = View.VISIBLE
            adapter.submitList(value.orEmpty())
            empty.visibility = if (value.isNullOrEmpty()) View.VISIBLE else View.GONE
            empty.setText(if (value == null) R.string.row_error else R.string.search_no_results)
        }

        /** For the empty state: the row only when it has something to show. */
        fun showOrHide(items: List<RowItem>) {
            root.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            empty.visibility = View.GONE
            adapter.submitList(items)
        }

        // Fresh results may not be laid out yet: try again shortly until the first card takes the focus.
        fun focusFirst(attempt: Int = 0) {
            list.postDelayed({
                val focused = list.layoutManager?.findViewByPosition(0)?.requestFocus() == true
                if (!focused && attempt < FOCUS_ATTEMPTS) focusFirst(attempt + 1)
            }, if (attempt == 0) 0L else FOCUS_RETRY_MS)
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
        const val FOCUS_ATTEMPTS = 10
        const val FOCUS_RETRY_MS = 32L
    }
}
