package com.purpletv.ui

import android.view.View
import android.view.animation.DecelerateInterpolator
import com.purpletv.R

private const val FOCUS_ANIM_MS = 160L
private const val FOCUS_SCALE = 1.06f
private val DECELERATE = DecelerateInterpolator()
private const val OPAQUE = 255

/**
 * TV focus look: the card grows slightly and [ring] shows the purple outline. A Runnable set as
 * the view's [R.id.focus_callback] tag runs each time it takes the focus. The outline is
 * always attached and only its alpha changes: swapping the background would request a layout,
 * and a horizontal RecyclerView laid out mid-focus scrolls the focused card to its start.
 */
fun View.focusScale(ring: View, scale: Float = FOCUS_SCALE, ringRes: Int = R.drawable.focus_ring) {
    isFocusable = true
    ring.setBackgroundResource(ringRes)
    ring.background.mutate().alpha = 0
    setOnFocusChangeListener { v, hasFocus ->
        val target = if (hasFocus) scale else 1f
        v.animate().scaleX(target).scaleY(target).setDuration(FOCUS_ANIM_MS).setInterpolator(DECELERATE).start()
        ring.background.alpha = if (hasFocus) OPAQUE else 0
        if (hasFocus) (v.getTag(R.id.focus_callback) as? Runnable)?.run()
    }
}
