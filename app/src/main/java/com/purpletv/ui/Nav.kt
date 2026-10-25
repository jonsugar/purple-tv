package com.purpletv.ui

import android.content.Context
import android.content.Intent
import com.purpletv.category.CategoryActivity
import com.purpletv.player.PlayerActivity
import com.purpletv.player.toTuneInfo
import com.purpletv.twitch.Category
import com.purpletv.twitch.Stream

/** Where cards lead. */
object Nav {
    /** With the card's [stream] (and [avatar]), the player shows them while the video loads. */
    fun openStream(context: Context, login: String, stream: Stream? = null, avatar: String? = null) {
        val intent = Intent(context, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL, login)
        stream?.toTuneInfo(avatar)?.let { tune ->
            intent.putExtra(PlayerActivity.EXTRA_NAME, tune.name)
                .putExtra(PlayerActivity.EXTRA_TITLE, tune.title)
                .putExtra(PlayerActivity.EXTRA_GAME, tune.game)
                .putExtra(PlayerActivity.EXTRA_VIEWERS, tune.viewers)
                .putExtra(PlayerActivity.EXTRA_THUMB, tune.thumbnail)
                .putExtra(PlayerActivity.EXTRA_AVATAR, tune.avatar)
        }
        context.startActivity(intent)
    }

    fun openCategory(context: Context, category: Category) {
        context.startActivity(Intent(context, CategoryActivity::class.java)
            .putExtra(CategoryActivity.EXTRA_ID, category.id)
            .putExtra(CategoryActivity.EXTRA_NAME, category.name)
            .putExtra(CategoryActivity.EXTRA_ART, category.boxArtTemplate))
    }
}
