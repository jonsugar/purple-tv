package com.purpletv.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.purpletv.R
import com.purpletv.home.GhostFollow
import com.purpletv.home.GhostFollowStore

/** The player icon and channel page button use the same local action. */
class GhostFollowButton(
    private val view: View,
    private val store: GhostFollowStore,
    private val account: () -> String?,
    private val onLabelChanged: () -> Unit = {},
) {
    fun bind(id: String, login: String, name: String) {
        val owner = account()
        if (owner.isNullOrBlank() || id.isBlank() || login.isBlank()) {
            clear()
            return
        }
        view.visibility = View.VISIBLE
        render(id in store.ids(owner))
        view.setOnClickListener {
            if (account() == owner) render(store.toggle(owner, GhostFollow(id, login, name.ifBlank { login })))
        }
    }

    fun clear() {
        view.visibility = View.GONE
        view.setOnClickListener(null)
        onLabelChanged()
    }

    private fun render(following: Boolean) {
        val label = view.context.getString(if (following) R.string.ghost_unfollow else R.string.ghost_follow)
        view.isSelected = following
        view.contentDescription = label
        (view as? TextView)?.text = label
        (view as? ImageView)?.setImageResource(if (following) R.drawable.ic_ghost_followed else R.drawable.ic_ghost_follow)
        onLabelChanged()
    }
}
