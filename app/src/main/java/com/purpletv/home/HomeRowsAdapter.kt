package com.purpletv.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.purpletv.R
import com.purpletv.ui.RowAdapter
import com.purpletv.ui.RowItem
import com.purpletv.ui.RowKind
import com.purpletv.ui.fixRowHeight

/** One Home row: its title, its cards (or the failure to load them) and the avatars of its streams. */
data class HomeRow(
    val title: String,
    val kind: RowKind,
    val items: Result<List<RowItem>>,
    val avatars: Map<String, String> = emptyMap(),
    val emptyText: Int = R.string.row_error,
)

/**
 * Home's rows as one vertical list. Every row's horizontal list takes new cards from one shared
 * pool.
 */
class HomeRowsAdapter(
    private val context: Context,
    private val onFocus: (row: Int, item: RowItem) -> Unit,
    private val onClick: (RowItem) -> Unit,
) : RecyclerView.Adapter<HomeRowsAdapter.Holder>() {
    private val pool = RecyclerView.RecycledViewPool()
    private var focusRow = -1
    var rows: List<HomeRow> = emptyList()
        private set

    fun submit(newRows: List<HomeRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    /** Focuses the first card of [row] when that row is next bound, which a fresh list does first. */
    fun focusFirstCard(row: Int) {
        focusRow = row
        notifyItemChanged(row)
    }

    /** Fills in avatars that arrive after the rows; rows that already carry their own keep them. */
    fun addAvatars(urls: Map<String, String>) {
        rows = rows.map { if (it.avatars.isEmpty()) it.copy(avatars = urls) else it }
        notifyItemRangeChanged(0, rows.size, AVATARS)
    }

    inner class Holder(root: View) : RecyclerView.ViewHolder(root) {
        val title: TextView = root.findViewById(R.id.row_title)
        val empty: TextView = root.findViewById(R.id.row_empty)
        val list: RecyclerView = root.findViewById(R.id.row_list)
        val adapter = RowAdapter(onClick) { item -> onFocus(bindingAdapterPosition, item) }

        init {
            // The cards stay in their row while it is off screen. A row that gave them back on
            // detach during a layout pass came back empty, and Down then focused the empty list.
            list.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            list.setRecycledViewPool(pool)
            list.adapter = adapter
            list.itemAnimator = null
        }

        private var focusWhenReady = false

        /** Focuses the first card; if the row still shows its skeleton, as soon as the cards land. */
        fun focusFirst() {
            if (adapter.currentList.any { it !is RowItem.Skeleton }) focusNow() else focusWhenReady = true
        }

        fun onCardsCommitted() {
            if (focusWhenReady && adapter.currentList.any { it !is RowItem.Skeleton }) {
                focusWhenReady = false
                focusNow()
            }
        }

        // Right after the cards commit, the list may still hold the skeleton until its next layout,
        // and a skeleton refuses the focus; a row scrolled sideways has no first card laid out.
        // Bring the row back to its start and try again shortly until a real card takes it.
        private fun focusNow(attempt: Int = 0) {
            list.postDelayed({
                val card = list.layoutManager?.findViewByPosition(0)
                if (card == null) list.scrollToPosition(0)
                val focused = card != null && card.isFocusable && card.requestFocus()
                if (!focused && attempt < FOCUS_ATTEMPTS) focusNow(attempt + 1)
            }, if (attempt == 0) 0L else FOCUS_RETRY_MS)
        }
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(context).inflate(R.layout.view_row, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.title.text = row.title
        holder.list.fixRowHeight(row.kind)
        val items = row.items.getOrNull()
        holder.adapter.submitList(items.orEmpty()) { holder.onCardsCommitted() }
        holder.adapter.avatars = row.avatars
        holder.empty.visibility = if (items.isNullOrEmpty()) View.VISIBLE else View.GONE
        holder.empty.setText(if (items == null) R.string.row_error else row.emptyText)
        if (position == focusRow) {
            focusRow = -1
            holder.focusFirst()
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(AVATARS)) holder.adapter.avatars = rows[position].avatars
        else onBindViewHolder(holder, position)
    }

    private companion object {
        const val AVATARS = "avatars"
        const val FOCUS_ATTEMPTS = 10
        const val FOCUS_RETRY_MS = 32L
    }
}
