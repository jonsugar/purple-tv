package com.purpletv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View

/**
 * An invisible view that takes the focus Android hands out when the focused view goes away: it
 * goes to the window's first focusable view, so this sits first in the rail. The D-pad never moves
 * onto it, because it lists no focusables for the search.
 */
class FocusSink(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) = Unit
}
