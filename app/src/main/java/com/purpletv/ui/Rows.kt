package com.purpletv.ui

import androidx.recyclerview.widget.RecyclerView

/**
 * Row heights for horizontal card lists, cards plus the list's vertical padding. A fixed height
 * keeps every card measured against the same constraint: with wrap_content inside a ScrollView a
 * card measured before the list settled kept a clipped height (seen on the first search result).
 */
enum class RowKind(val heightDp: Int) { STREAMS(172), CATEGORIES(158), CHANNELS(132) }

fun RecyclerView.fixRowHeight(kind: RowKind) {
    layoutParams = layoutParams.apply { height = (kind.heightDp * resources.displayMetrics.density).toInt() }
    setHasFixedSize(true)
}
