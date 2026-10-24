package com.purpletv.ui

import android.content.Context
import android.content.Intent
import com.purpletv.PlayerActivity
import com.purpletv.twitch.Category
import com.purpletv.twitch.Stream

/** Where cards lead. */
object Nav {
    fun openStream(context: Context, login: String, stream: Stream? = null) {
        context.startActivity(Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL, login))
    }

    /** The category screen arrives with the Browse task. */
    @Suppress("UNUSED_PARAMETER")
    fun openCategory(context: Context, category: Category) = Unit
}
