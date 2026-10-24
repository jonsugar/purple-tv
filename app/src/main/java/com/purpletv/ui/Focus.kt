package com.purpletv.ui

import android.view.View
import com.purpletv.R

private const val FOCUS_ANIM_MS = 120L
private const val OPAQUE = 255

/**
 * TV focus look: the card grows slightly and [ring] shows the purple outline. The outline is
 * always attached and only its alpha changes: swapping the background would request a layout,
 * and a horizontal RecyclerView laid out mid-focus scrolls the focused card to its start.
 */
fun View.focusScale(ring: View, scale: Float = 1.05f) {
    isFocusable = true
    ring.setBackgroundResource(R.drawable.focus_ring)
    ring.background.mutate().alpha = 0
    setOnFocusChangeListener { v, hasFocus ->
        val target = if (hasFocus) scale else 1f
        v.animate().scaleX(target).scaleY(target).setDuration(FOCUS_ANIM_MS).start()
        ring.background.alpha = if (hasFocus) OPAQUE else 0
    }
}
