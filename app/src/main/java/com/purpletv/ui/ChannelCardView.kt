package com.purpletv.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.transform.CircleCropTransformation
import com.purpletv.R
import com.purpletv.twitch.ChannelResult

/** A search result channel: round avatar, red ring and LIVE when live, dimmed when offline. */
class ChannelCardView(context: Context) : FrameLayout(context) {
    private val border: View
    private val avatar: ImageView
    private val live: TextView
    private val name: TextView
    private val sub: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_channel_card, this, true)
        border = findViewById(R.id.ch_border)
        avatar = findViewById(R.id.ch_avatar)
        live = findViewById(R.id.ch_live)
        name = findViewById(R.id.ch_name)
        sub = findViewById(R.id.ch_sub)
        focusScale(findViewById(R.id.ch_ring), ringRes = R.drawable.focus_ring_oval)
    }

    fun bind(channel: ChannelResult) {
        name.text = channel.displayName
        sub.text = if (channel.isLive) channel.gameName else context.getString(R.string.offline)
        contentDescription = "${channel.displayName}, ${sub.text}"
        live.visibility = if (channel.isLive) View.VISIBLE else View.GONE
        border.setBackgroundResource(if (channel.isLive) R.drawable.avatar_ring_live else R.drawable.avatar_ring_off)
        alpha = if (channel.isLive) 1f else OFFLINE_ALPHA
        if (channel.profileImageUrl.isEmpty()) avatar.clear()
        else avatar.load(channel.profileImageUrl) { transformations(CircleCropTransformation()) }
    }

    private companion object {
        const val OFFLINE_ALPHA = 0.55f
    }
}
