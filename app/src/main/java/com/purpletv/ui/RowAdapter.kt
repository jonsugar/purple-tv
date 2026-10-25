package com.purpletv.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.purpletv.R
import com.purpletv.twitch.Category
import com.purpletv.twitch.ChannelResult
import com.purpletv.twitch.Stream

sealed interface RowItem {
    data class StreamItem(val stream: Stream) : RowItem
    data class CategoryItem(val category: Category) : RowItem
    data class ChannelItem(val channel: ChannelResult) : RowItem
    /** A placeholder card while a row loads. */
    object Skeleton : RowItem
}

const val STREAM_CARD = 0
const val CATEGORY_CARD = 1
const val CHANNEL_CARD = 2
const val SPARE_STREAM_CARDS = 12
const val SPARE_CATEGORY_CARDS = 6

/** The card an item needs; a skeleton is a stream card still waiting for its stream. */
fun cardType(item: RowItem): Int = when (item) {
    is RowItem.StreamItem, RowItem.Skeleton -> STREAM_CARD
    is RowItem.CategoryItem -> CATEGORY_CARD
    is RowItem.ChannelItem -> CHANNEL_CARD
}

/** Which spare card to make next for Home's shared pool, or none once it holds enough. */
fun cardToWarm(spareStreams: Int, spareCategories: Int): Int? = when {
    spareStreams < SPARE_STREAM_CARDS -> STREAM_CARD
    spareCategories < SPARE_CATEGORY_CARDS -> CATEGORY_CARD
    else -> null
}

/** Cards in a row (or a grid): streams, categories and channels. Stream avatars arrive after the list. */
open class RowAdapter(
    private val onClick: (RowItem) -> Unit,
    private val onFocus: (RowItem) -> Unit = {},
) :
    ListAdapter<RowItem, RowAdapter.Holder>(Diff) {

    var avatars: Map<String, String> = emptyMap()
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount, AVATAR_PAYLOAD)
        }

    class Holder(val card: android.view.View) : RecyclerView.ViewHolder(card)

    override fun getItemViewType(position: Int) = cardType(getItem(position))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val card = when (viewType) {
            STREAM_CARD -> StreamCardView(parent.context)
            CATEGORY_CARD -> CategoryCardView(parent.context)
            else -> ChannelCardView(parent.context)
        }
        card.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = (CARD_GAP_DP * parent.resources.displayMetrics.density).toInt() }
        return Holder(card)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        when (item) {
            is RowItem.StreamItem -> (holder.card as StreamCardView).bind(item.stream, avatars[item.stream.userId])
            is RowItem.CategoryItem -> (holder.card as CategoryCardView).bind(item.category)
            is RowItem.ChannelItem -> (holder.card as ChannelCardView).bind(item.channel)
            RowItem.Skeleton -> (holder.card as StreamCardView).bindSkeleton()
        }
        holder.card.setOnClickListener { onClick(item) }
        holder.card.setTag(R.id.focus_callback, Runnable { onFocus(item) })
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        val item = getItem(position)
        if (payloads.contains(AVATAR_PAYLOAD) && item is RowItem.StreamItem) {
            (holder.card as StreamCardView).bindAvatar(avatars[item.stream.userId])
        } else {
            onBindViewHolder(holder, position)
        }
    }

    private object Diff : DiffUtil.ItemCallback<RowItem>() {
        override fun areItemsTheSame(a: RowItem, b: RowItem) = when {
            a is RowItem.StreamItem && b is RowItem.StreamItem -> a.stream.userId == b.stream.userId
            a is RowItem.CategoryItem && b is RowItem.CategoryItem -> a.category.id == b.category.id
            a is RowItem.ChannelItem && b is RowItem.ChannelItem -> a.channel.login == b.channel.login
            a is RowItem.Skeleton && b is RowItem.Skeleton -> true
            else -> false
        }

        override fun areContentsTheSame(a: RowItem, b: RowItem) = a == b
    }

    private companion object {
        const val CARD_GAP_DP = 8
        const val AVATAR_PAYLOAD = "avatar"
    }
}
