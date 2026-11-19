package com.purpletv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ancestors
import com.purpletv.R

/** Vertical focus searches from the content stay on its side of the navigation rail. */
class NavContentLayout(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private lateinit var content: ViewGroup

    override fun onFinishInflate() {
        super.onFinishInflate()
        content = findViewById(R.id.content)
    }

    override fun focusSearch(focused: View, direction: Int): View? {
        if ((direction == FOCUS_UP || direction == FOCUS_DOWN) && focused.ancestors.any { it === content }) {
            return FocusFinder.getInstance().findNextFocus(content, focused, direction) ?: focused
        }
        return super.focusSearch(focused, direction)
    }
}
