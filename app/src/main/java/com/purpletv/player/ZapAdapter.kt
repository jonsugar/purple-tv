package com.purpletv.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.purpletv.R
import com.purpletv.twitch.Stream
import com.purpletv.twitch.sized
import com.purpletv.ui.focusScale
import com.purpletv.ui.loadCard

/** The Channels strip's cards: the live picture and the name, "in onda" under the channel playing. */
class ZapAdapter(private val onPick: (Stream) -> Unit) : RecyclerView.Adapter<ZapAdapter.Holder>() {
    private var streams: List<Stream> = emptyList()
    private var onAir = ""

    @android.annotation.SuppressLint("NotifyDataSetChanged") // the whole strip changes at once
    fun submit(list: List<Stream>, currentLogin: String) {
        streams = list
        onAir = currentLogin
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.zap_thumb)
        val name: TextView = view.findViewById(R.id.zap_name)
        val onAir: View = view.findViewById(R.id.zap_on_air)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_zap, parent, false)
        view.findViewById<View>(R.id.zap_frame).clipToOutline = true
        view.focusScale(view.findViewById(R.id.zap_ring))
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val stream = streams[position]
        holder.thumb.loadCard(sized(stream.thumbnailTemplate, THUMB_W, THUMB_H))
        holder.name.text = stream.displayName
        holder.onAir.visibility = if (stream.login.equals(onAir, ignoreCase = true)) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onPick(stream) }
    }

    override fun getItemCount() = streams.size

    private companion object {
        const val THUMB_W = 320
        const val THUMB_H = 180
    }
}
