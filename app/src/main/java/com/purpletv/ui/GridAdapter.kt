package com.purpletv.ui

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** A vertical grid of cards that asks for the next page when its last rows come into view. */
class GridAdapter(
    private val columns: Int,
    onClick: (RowItem) -> Unit,
    private val onNearEnd: () -> Unit,
) : RowAdapter(onClick) {

    override fun onBindViewHolder(holder: Holder, position: Int) {
        super.onBindViewHolder(holder, position)
        if (nearEnd(position, itemCount, columns)) holder.card.post(onNearEnd)
    }

    /** Puts this adapter on [list] as a grid with spacing between rows. */
    fun attachTo(list: RecyclerView) {
        list.layoutManager = GridLayoutManager(list.context, columns)
        list.adapter = this
        list.itemAnimator = null
        if (list.itemDecorationCount == 0) list.addItemDecoration(RowSpacing((ROW_GAP_DP * list.resources.displayMetrics.density).toInt()))
    }

    private class RowSpacing(private val gap: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.bottom = gap
        }
    }

    companion object {
        private const val ROW_GAP_DP = 12

        /** True for items in the last [rows] rows of a grid of [count] items. */
        fun nearEnd(position: Int, count: Int, columns: Int, rows: Int = 2): Boolean =
            count > 0 && position >= count - columns * rows
    }
}
