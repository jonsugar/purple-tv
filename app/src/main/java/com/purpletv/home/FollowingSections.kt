package com.purpletv.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.purpletv.R
import com.purpletv.ui.CardRow
import com.purpletv.ui.RowKind
import com.purpletv.ui.fixRowHeight

/** A horizontally scrolling favourites row at the top of the existing vertical grid. */
internal class FollowingSections(
    private val context: Context,
    private val grid: RecyclerView,
    private val heading: Int,
    private val kind: RowKind,
    private val pinnedCards: RecyclerView.Adapter<*>,
) : RecyclerView.Adapter<FollowingSections.Header>() {
    private var showPins = false
    private var showRegular = true
    private var pinnedTitle = context.getString(R.string.pinned_favourites)
    private val layout = grid.layoutManager as GridLayoutManager

    init {
        grid.adapter = ConcatAdapter(this, checkNotNull(grid.adapter))
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (position == 0) layout.spanCount else 1
        }
    }

    fun show(pinned: Int, regular: Int) {
        val pinsVisible = pinned > 0
        val regularVisible = pinned == 0 || regular > 0
        if (pinsVisible == showPins && regularVisible == showRegular) return
        val wasAtStart = layout.findFirstVisibleItemPosition() == 0
        showPins = pinsVisible
        showRegular = regularVisible
        notifyItemChanged(0)
        if (wasAtStart) layout.scrollToPositionWithOffset(0, 0)
    }

    fun focusPinned(position: Int) {
        if (!grid.isAttachedToWindow) return
        layout.scrollToPositionWithOffset(0, 0)
        grid.doOnNextLayout {
            val row = (grid.findViewHolderForAdapterPosition(0) as? Header)?.row ?: return@doOnNextLayout
            row.rememberedCard = position
            if (row.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() == true) return@doOnNextLayout
            row.scrollToPosition(position)
            row.doOnNextLayout { row.layoutManager?.findViewByPosition(position)?.requestFocus() }
        }
    }

    fun setMovingChannel(name: String?) {
        pinnedTitle = if (name == null) context.getString(R.string.pinned_favourites)
            else context.getString(R.string.moving_pinned_channel, name)
        notifyItemChanged(0)
    }

    fun focusRegular(position: Int) {
        if (!grid.isAttachedToWindow) return
        grid.scrollToPosition(position + 1)
        grid.doOnNextLayout { layout.findViewByPosition(position + 1)?.requestFocus() }
    }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Header {
        val root = LayoutInflater.from(context).inflate(R.layout.view_following_header, parent, false)
        val row = root.findViewById<CardRow>(R.id.row_list)
        row.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        row.adapter = pinnedCards
        row.itemAnimator = null
        row.fixRowHeight(kind)
        return Header(root)
    }

    override fun onBindViewHolder(holder: Header, position: Int) {
        holder.pinned.visibility = if (showPins) View.VISIBLE else View.GONE
        holder.pinnedTitle.text = pinnedTitle
        holder.heading.setText(heading)
        holder.heading.visibility = if (showRegular) View.VISIBLE else View.GONE
    }

    class Header(root: View) : RecyclerView.ViewHolder(root) {
        val pinned: View = root.findViewById(R.id.following_pinned)
        val pinnedTitle: TextView = root.findViewById(R.id.row_title)
        val row: CardRow = root.findViewById(R.id.row_list)
        val heading: TextView = root.findViewById(R.id.following_regular_heading)
    }
}
