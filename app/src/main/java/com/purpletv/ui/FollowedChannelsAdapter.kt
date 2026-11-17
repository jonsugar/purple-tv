package com.purpletv.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.purpletv.R
import com.purpletv.twitch.FollowedChannel

class FollowedChannelsAdapter(
    private val onClick: (FollowedChannel) -> Unit,
    private val onNearEnd: (Int) -> Unit,
) : ListAdapter<FollowedChannel, FollowedChannelsAdapter.Holder>(Diff) {
    class Holder(val card: FollowedChannelCard) : RecyclerView.ViewHolder(card)

    fun attachTo(grid: RecyclerView) {
        grid.layoutManager = GridLayoutManager(grid.context, 4)
        grid.adapter = this
        grid.itemAnimator = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(FollowedChannelCard(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val gap = (8 * resources.displayMetrics.density).toInt()
            marginEnd = gap
            bottomMargin = gap
        }
    })

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.card.bind(getItem(position))
        holder.card.setOnClickListener { onClick(getItem(holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener)) }
        holder.card.nextFocusLeftId = if (position % 4 == 0) R.id.nav_following else View.NO_ID
        holder.card.nextFocusUpId = if (position < 4) R.id.following_channels else View.NO_ID
        holder.card.post { onNearEnd(position) }
    }

    private object Diff : DiffUtil.ItemCallback<FollowedChannel>() {
        override fun areItemsTheSame(a: FollowedChannel, b: FollowedChannel) = a.id == b.id
        override fun areContentsTheSame(a: FollowedChannel, b: FollowedChannel) = a == b
    }
}

class FollowedChannelCard(context: Context) : FrameLayout(context) {
    init {
        LayoutInflater.from(context).inflate(R.layout.view_followed_channel_card, this, true)
        findViewById<View>(R.id.followed_frame).clipToOutline = true
        focusScale(findViewById(R.id.followed_ring))
    }

    fun bind(channel: FollowedChannel) {
        findViewById<TextView>(R.id.followed_name).text = channel.displayName
        val state = context.getString(if (channel.live != null) R.string.live else R.string.offline)
        contentDescription = "${channel.displayName}, $state"
        findViewById<View>(R.id.followed_live_badge).visibility = if (channel.live == null) View.GONE else View.VISIBLE
        findViewById<ImageView>(R.id.followed_avatar).apply { if (channel.avatarUrl.isEmpty()) clear() else loadCard(channel.avatarUrl, circle = true) }
        findViewById<ImageView>(R.id.followed_background).apply { if (channel.backgroundUrl.isEmpty()) clear() else loadCard(channel.backgroundUrl) }
    }
}
