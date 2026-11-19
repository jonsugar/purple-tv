package com.purpletv.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.purpletv.R

/** A grid with an independent status and a remote-focusable retry action. */
class ContentGrid(val view: View, private val emptyText: Int, focusAbove: () -> View, retry: () -> Unit) {
    constructor(context: Context, emptyText: Int, focusAbove: () -> View, retry: () -> Unit) :
        this(LayoutInflater.from(context).inflate(R.layout.view_content_grid, null), emptyText, focusAbove, retry)

    val grid: ContentGridView = view.findViewById(R.id.content_grid)
    private val status: TextView = view.findViewById(R.id.content_status)
    private val retryButton: View = view.findViewById(R.id.content_retry)

    init {
        retryButton.setOnClickListener { retry() }
        grid.focusAbove = { if (retryButton.isShown) retryButton else focusAbove() }
    }

    fun loading() {
        status.setText(R.string.loading)
        status.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
    }

    fun show(count: Int, failed: Boolean, empty: Int = emptyText) {
        status.setText(if (failed) R.string.row_error else empty)
        status.visibility = if (count == 0 || failed) View.VISIBLE else View.GONE
        val hadRetryFocus = retryButton.hasFocus()
        retryButton.visibility = if (failed) View.VISIBLE else View.GONE
        if (hadRetryFocus && !failed) grid.post { grid.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }
}
