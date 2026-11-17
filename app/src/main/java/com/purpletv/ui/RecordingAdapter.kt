package com.purpletv.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.purpletv.R
import com.purpletv.twitch.Recording
import com.purpletv.twitch.durationText
import com.purpletv.twitch.fullCount
import com.purpletv.twitch.sized
import java.util.Locale

class RecordingAdapter(private val open: (Recording) -> Unit, private val more: () -> Unit) :
    ListAdapter<Recording, RecordingAdapter.Holder>(Diff) {
    class Holder(val card: RecordingCard) : RecyclerView.ViewHolder(card)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(RecordingCard(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = (8 * resources.displayMetrics.density).toInt()
        }
    })
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.card.bind(item)
        holder.card.setOnClickListener { open(item) }
        if (position >= itemCount - 4) holder.card.post(more)
    }
    private object Diff : DiffUtil.ItemCallback<Recording>() {
        override fun areItemsTheSame(a: Recording, b: Recording) = a.id == b.id && a.kind == b.kind
        override fun areContentsTheSame(a: Recording, b: Recording) = a == b
    }
}

class RecordingCard(context: Context) : FrameLayout(context) {
    init {
        LayoutInflater.from(context).inflate(R.layout.view_recording_card, this, true)
        findViewById<View>(R.id.recording_frame).clipToOutline = true
        focusScale(findViewById(R.id.recording_ring))
    }
    fun bind(item: Recording) {
        contentDescription = item.title
        findViewById<TextView>(R.id.recording_title).text = item.title
        findViewById<TextView>(R.id.recording_duration).text = durationText(item.durationSeconds)
        findViewById<TextView>(R.id.recording_meta).text = listOf(
            context.getString(R.string.recording_views, fullCount(item.views, Locale.getDefault())), item.publishedAt.substringBefore('T'),
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        findViewById<ImageView>(R.id.recording_thumb).apply {
            if (item.thumbnailUrl.isEmpty()) clear() else loadCard(sized(item.thumbnailUrl, 400, 225))
        }
    }
}
