package com.purpletv.ui

import android.view.View
import android.widget.TextView
import com.purpletv.R
import com.purpletv.home.FavouritePin
import com.purpletv.home.PinStore
import com.purpletv.twitch.FollowKind

/** Shares the follow control's verified target and eligibility. A pin is a local edit. */
class PinButton(
    private val view: TextView,
    private val followView: View,
    private val pins: PinStore,
    private val account: () -> String?,
) {
    fun bind(target: Pair<FollowKind, String>?, name: String, eligible: Boolean, busy: Boolean) {
        val owner = account()
        if (target == null || !eligible || owner == null) {
            val hadFocus = view.hasFocus()
            view.visibility = View.GONE
            view.setOnClickListener(null)
            if (hadFocus) followView.requestFocus()
            return
        }
        val (kind, id) = target
        val pinned = pins.list(owner, kind).any { it.id == id }
        view.setText(if (pinned) R.string.unpin else R.string.pin)
        view.isSelected = pinned
        view.visibility = View.VISIBLE
        view.setOnClickListener {
            if (busy || owner != account()) return@setOnClickListener
            if (pinned) pins.remove(owner, kind, id) else pins.add(owner, kind, FavouritePin(id, name))
            bind(target, name, eligible = true, busy = false)
        }
        view.isClickable = !busy
        view.alpha = if (busy) 0.5f else 1f
    }
}
