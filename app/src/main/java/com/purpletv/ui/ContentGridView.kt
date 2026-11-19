package com.purpletv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** Lets Up leave the grid for its header once there are no earlier cards to focus. */
class ContentGridView(context: Context, attrs: AttributeSet?) : RecyclerView(context, attrs) {
    internal var focusAbove: (() -> View)? = null

    override fun focusSearch(focused: View, direction: Int): View? {
        val next = super.focusSearch(focused, direction)
        if (direction != FOCUS_UP) return next
        if (next != null && next !== focused && findContainingItemView(next) != null) return next

        // RecyclerView may keep focus while scrolling a section heading into view.
        if (next === focused && canScrollVertically(-1)) return focused

        return focusAbove?.invoke()?.takeIf { it.isShown && it.isFocusable } ?: next
    }
}
