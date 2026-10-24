package com.purpletv.ui

import android.content.Context
import android.content.Intent
import com.purpletv.PlayerActivity
import com.purpletv.category.CategoryActivity
import com.purpletv.twitch.Category
import com.purpletv.twitch.Stream

/** Where cards lead. */
object Nav {
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
