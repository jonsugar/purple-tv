package com.purpletv.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.purpletv.R
import com.purpletv.twitch.Stream
import com.purpletv.twitch.sized

/** A live stream card: thumbnail with LIVE and viewers, then avatar, title, channel and game. */
class StreamCardView(context: Context) : FrameLayout(context) {
    private val thumb: ImageView
    private val viewers: TextView
    private val avatar: ImageView
    private val title: TextView
    private val name: TextView
    private val game: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_stream_card, this, true)
        thumb = findViewById(R.id.card_thumb)
        viewers = findViewById(R.id.card_viewers)
        avatar = findViewById(R.id.card_avatar)
        title = findViewById(R.id.card_title)
        name = findViewById(R.id.card_name)
        game = findViewById(R.id.card_game)
        findViewById<View>(R.id.card_frame).clipToOutline = true
        avatar.clipToOutline = true
        focusScale(findViewById(R.id.card_ring))
    }

    fun bind(stream: Stream, avatarUrl: String?) {
        isFocusable = true
        listOf(R.id.card_live, R.id.card_viewers).forEach { findViewById<View>(it).visibility = View.VISIBLE }
        contentDescription = "${stream.displayName}, ${stream.title}"
        if (stream.thumbnailTemplate.isEmpty()) thumb.clear()
        else thumb.loadCard(sized(stream.thumbnailTemplate, THUMB_W, THUMB_H))
        viewers.text = context.viewersText(stream.viewers)
        title.text = stream.title
        name.text = stream.displayName
        game.text = stream.gameName
        bindAvatar(avatarUrl)
    }

    /** A placeholder while Home loads: the empty thumbnail frame, nothing else, not focusable. */
    fun bindSkeleton() {
        isFocusable = false
        contentDescription = null
        thumb.clear()
        avatar.clear()
        listOf(R.id.card_live, R.id.card_viewers).forEach { findViewById<View>(it).visibility = View.INVISIBLE }
        listOf(title, name, game).forEach { it.text = "" }
    }

    fun bindAvatar(url: String?) {
        if (url.isNullOrEmpty()) avatar.clear()
        else avatar.loadCard(url, circle = true)
    }

    private companion object {
        const val THUMB_W = 440
        const val THUMB_H = 248
    }
}
