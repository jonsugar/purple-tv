package com.purpletv.home

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.purpletv.R
import com.purpletv.twitch.HelixClient
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

/** Cerca: channels (live first) and categories, 400 ms after the last keystroke. */
class SearchPage(
    private val context: Context,
    private val scope: CoroutineScope,
    private val helix: HelixClient,
) {
    val view: View = LayoutInflater.from(context).inflate(R.layout.page_search, null)

    private val field: SearchField = view.findViewById(R.id.search_field)
    private val channels = Row(view.findViewById(R.id.search_channels), R.string.row_channels, RowKind.CHANNELS)
    private val categories = Row(view.findViewById(R.id.search_categories), R.string.row_search_categories, RowKind.CATEGORIES)
    private var pending: Job? = null

    init {
        channels.root.visibility = View.GONE
        categories.root.visibility = View.GONE
        field.doAfterTextChanged { schedule(it?.toString().orEmpty()) }
        field.onDownFromField = { (if (channels.root.visibility == View.VISIBLE) channels else categories).focusFirst() }
        field.setOnEditorActionListener { _, actionId, event ->
            val search = actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (search) {
                field.closeKeyboard()
                channels.focusFirst()
            }
            search
        }
    }

    private fun schedule(query: String) {
        pending?.cancel()
        if (!SearchLogic.shouldSearch(query)) {
            channels.root.visibility = View.GONE
            categories.root.visibility = View.GONE
            return
        }
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
        }
    }

    private fun open(item: RowItem) {
        when (item) {
            is RowItem.ChannelItem ->
                if (item.channel.isLive) Nav.openStream(context, item.channel.login)
                else Toast.makeText(context, context.getString(R.string.channel_offline, item.channel.displayName), Toast.LENGTH_SHORT).show()
            is RowItem.CategoryItem -> Nav.openCategory(context, item.category)
            is RowItem.StreamItem -> Nav.openStream(context, item.stream.login, item.stream)
            RowItem.Skeleton -> Unit
        }
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

        fun focusFirst() {
            list.post { list.layoutManager?.findViewByPosition(0)?.requestFocus() }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
    }
}
