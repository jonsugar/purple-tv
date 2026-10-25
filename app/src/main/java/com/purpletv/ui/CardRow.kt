package com.purpletv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView

/**
 * A horizontal row of cards that remembers its last focused card. Up and Down from another row
 * come back to that card, not to whichever card sits above or below; past the last card Right stays
 * put, where RecyclerView would hand the search to the whole window and drop to the row below.
 */
class CardRow(context: Context, attrs: AttributeSet?) : RecyclerView(context, attrs) {
    /** The adapter position of the card last focused here: where the focus comes back to. */
    var rememberedCard = 0

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        val position = child?.let(::getChildAdapterPosition) ?: NO_POSITION
        if (position != NO_POSITION) rememberedCard = position
    }

    override fun focusSearch(focused: View, direction: Int): View? {
        val next = super.focusSearch(focused, direction)
        val target = next?.let(::rowOf)
        return when (rowExit(direction, nextInRow = target === this, nextInOtherRow = target != null && target !== this)) {
            RowExit.STAY -> focused
            RowExit.ENTRY -> target?.rememberedView() ?: next
            RowExit.NEXT -> next
        }
    }

    // Off screen the remembered card is not laid out: then Android's own pick stands.
    private fun rememberedView(): View? = layoutManager?.findViewByPosition(rememberedCard)?.takeIf { it.isFocusable }
}

/** Where the focus goes from a row's card: stay on it, the other row's remembered card, or Android's pick. */
enum class RowExit { STAY, ENTRY, NEXT }

fun rowExit(direction: Int, nextInRow: Boolean, nextInOtherRow: Boolean): RowExit = when {
    nextInRow -> RowExit.NEXT
    direction == View.FOCUS_RIGHT -> RowExit.STAY
    nextInOtherRow && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) -> RowExit.ENTRY
    else -> RowExit.NEXT
}

/**
 * The row [view] belongs to. With the row above off screen, Home's list answers with that row's
 * whole item (title and cards) instead of a card: then it is the row inside it.
 */
private fun rowOf(view: View): CardRow? {
    var v: View? = view
    while (v != null && v !is CardRow) v = v.parent as? View
    return v as? CardRow ?: (view as? ViewGroup)?.children?.filterIsInstance<CardRow>()?.singleOrNull()
}
