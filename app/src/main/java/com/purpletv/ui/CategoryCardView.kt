package com.purpletv.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.purpletv.R
import com.purpletv.twitch.Category
import com.purpletv.twitch.sized

/** A category card: box art and the category name under it. */
class CategoryCardView(context: Context) : FrameLayout(context) {
    private val art: ImageView
    private val name: TextView
    private val viewers: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_category_card, this, true)
        art = findViewById(R.id.cat_art)
        name = findViewById(R.id.cat_name)
        viewers = findViewById(R.id.cat_viewers)
        findViewById<View>(R.id.cat_frame).clipToOutline = true
        focusScale(findViewById(R.id.cat_ring))
    }

    fun bind(category: Category) {
        contentDescription = category.name
        name.text = category.name
        viewers.visibility = if (category.viewers == null) View.GONE else View.VISIBLE
        viewers.text = category.viewers?.let(context::viewersText).orEmpty()
        if (category.boxArtTemplate.isEmpty()) art.clear()
        else art.loadCard(sized(category.boxArtTemplate, ART_W, ART_H))
    }

    private companion object {
        const val ART_W = 285
        const val ART_H = 380
    }
}
