package com.purpletv.ui

import android.content.Context
import android.content.Intent
import com.purpletv.category.CategoryActivity
import com.purpletv.player.PlayerActivity
import com.purpletv.twitch.Category
import com.purpletv.twitch.Stream

/** Where cards lead. */
object Nav {
    /** The player fetches the stream's details itself; [stream] is accepted for callers that have one. */
    @Suppress("UNUSED_PARAMETER")
    fun openStream(context: Context, login: String, stream: Stream? = null) {
        context.startActivity(Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, login))
    }

    fun openCategory(context: Context, category: Category) {
        context.startActivity(Intent(context, CategoryActivity::class.java)
            .putExtra(CategoryActivity.EXTRA_ID, category.id)
            .putExtra(CategoryActivity.EXTRA_NAME, category.name)
            .putExtra(CategoryActivity.EXTRA_ART, category.boxArtTemplate))
    }
}
