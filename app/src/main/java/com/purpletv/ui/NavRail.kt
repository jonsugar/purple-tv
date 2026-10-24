package com.purpletv.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import coil.transform.CircleCropTransformation
import com.purpletv.R
import com.purpletv.home.Screen

/**
 * The left navigation rail: icons only, widening to show labels while it holds the focus.
 * The item of the current screen stays highlighted.
 */
class NavRail(private val rail: LinearLayout, private val onSelect: (Screen) -> Unit) {
    private val items = mapOf(
        Screen.HOME to item(R.id.nav_home, R.drawable.ic_home, R.string.nav_home),
        Screen.BROWSE to item(R.id.nav_browse, R.drawable.ic_browse, R.string.nav_browse),
        Screen.SEARCH to item(R.id.nav_search, R.drawable.ic_search, R.string.nav_search),
        Screen.PROFILE to rail.findViewById<View>(R.id.nav_profile),
    )
    private val density = rail.resources.displayMetrics.density
    private var expanded = false
    private var current = Screen.HOME

    init {
        items.forEach { (screen, view) -> view.setOnClickListener { onSelect(screen) } }
        // Up and Down stay inside the rail: Profilo sits at the bottom, and the nearest view below
        // Cerca would otherwise be a card on the page.
        val column = listOf(Screen.HOME, Screen.BROWSE, Screen.SEARCH, Screen.PROFILE).map { items.getValue(it) }
        column.forEachIndexed { i, view ->
            view.nextFocusUpId = column[maxOf(i - 1, 0)].id
            view.nextFocusDownId = column[minOf(i + 1, column.lastIndex)].id
        }
        rail.viewTreeObserver.addOnGlobalFocusChangeListener { oldFocus, newFocus ->
            val entering = newFocus != null && isInside(newFocus) && (oldFocus == null || !isInside(oldFocus))
            setExpanded(newFocus != null && isInside(newFocus))
            // Coming from the page, land on the current screen's item, not the geometrically nearest one.
            if (entering && newFocus !== items[current]) items[current]?.requestFocus()
        }
    }

    fun select(screen: Screen) {
        current = screen
        items.forEach { (s, view) -> view.isSelected = s == screen }
    }

    fun focus(screen: Screen) {
        items[screen]?.requestFocus()
    }

    fun setAvatar(url: String) {
        rail.findViewById<ImageView>(R.id.nav_avatar).apply {
            clipToOutline = true
            load(url) { crossfade(false); transformations(CircleCropTransformation()) }
        }
    }

    private fun item(id: Int, icon: Int, label: Int): View = rail.findViewById<View>(id).apply {
        findViewById<ImageView>(R.id.nav_icon).setImageResource(icon)
        findViewById<TextView>(R.id.nav_label).setText(label)
        contentDescription = rail.context.getString(label)
    }

    private fun isInside(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === rail) return true
            v = v.parent as? View
        }
        return false
    }

    private fun setExpanded(value: Boolean) {
        if (value == expanded) return
        expanded = value
        rail.layoutParams = rail.layoutParams.apply { width = dp(if (value) EXPANDED_DP else COLLAPSED_DP) }
        rail.gravity = if (value) android.view.Gravity.START else android.view.Gravity.CENTER_HORIZONTAL
        items.values.filter { it.findViewById<TextView?>(R.id.nav_label) != null }.forEach { view ->
            view.findViewById<TextView>(R.id.nav_label).visibility = if (value) View.VISIBLE else View.GONE
            view.layoutParams = view.layoutParams.apply { width = if (value) ViewGroup.LayoutParams.MATCH_PARENT else dp(ITEM_DP) }
        }
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val COLLAPSED_DP = 56
        const val EXPANDED_DP = 120
        const val ITEM_DP = 32
    }
}
