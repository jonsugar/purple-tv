package com.purpletv.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.purpletv.R

/**
 * The chat column on the right of the player: newest message at the bottom, the last [LIMIT]
 * kept. Messages are added in batches every [FLUSH_MS] so a busy chat does not redraw per line.
 */
class ChatView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val lines = ArrayList<CharSequence>()
    private val pending = ArrayList<ChatMessage>()
    private val adapter = LineAdapter()
    private lateinit var list: RecyclerView
    private lateinit var channelName: TextView

    override fun onFinishInflate() {
        super.onFinishInflate()
        channelName = findViewById(R.id.chat_channel)
        list = findViewById(R.id.chat_list)
        list.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
        list.adapter = adapter
        list.itemAnimator = null
    }

    fun bind(channel: String) {
        channelName.text = channel
        clear()
    }

    fun add(message: ChatMessage) {
        pending += message
        if (pending.size == 1) postDelayed(flush, FLUSH_MS)
    }

    fun clear() {
        removeCallbacks(flush)
        pending.clear()
        lines.clear()
        adapter.notifyDataSetChanged()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(flush)
        super.onDetachedFromWindow()
    }

    private val flush = Runnable {
        val start = lines.size
        pending.forEach { lines += line(it) }
        adapter.notifyItemRangeInserted(start, pending.size)
        pending.clear()
        val overflow = lines.size - LIMIT
        if (overflow > 0) {
            lines.subList(0, overflow).clear()
            adapter.notifyItemRangeRemoved(0, overflow)
        }
        list.scrollToPosition(lines.size - 1)
    }

    private fun line(message: ChatMessage): CharSequence {
        val color = message.color?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: context.getColor(R.color.purple_light)
        return SpannableStringBuilder().apply {
            append(message.user, ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(": ")
            append(message.text)
        }
    }

    private inner class LineAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = lines.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(TextView(parent.context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            setTextColor(context.getColor(R.color.text_soft))
            val gap = (LINE_GAP_DP * resources.displayMetrics.density).toInt()
            setPadding(0, gap, 0, gap)
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).text = lines[position]
        }
    }

    private companion object {
        const val LIMIT = 150
        const val FLUSH_MS = 200L
        const val TEXT_SP = 10f
        const val LINE_GAP_DP = 2
    }
}
