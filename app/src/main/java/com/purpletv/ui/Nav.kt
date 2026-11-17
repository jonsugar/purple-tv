package com.purpletv.ui

import android.content.Context
import android.content.Intent
import com.purpletv.category.CategoryActivity
import com.purpletv.PurpleApp
import com.purpletv.player.PlayerActivity
import com.purpletv.player.ZapList
import com.purpletv.player.toTuneInfo
import com.purpletv.twitch.Category
import com.purpletv.twitch.Stream
import com.purpletv.twitch.FollowedChannel
import com.purpletv.twitch.Recording
import com.purpletv.channel.ChannelActivity
import com.purpletv.player.RecordedPlayerActivity
import com.purpletv.home.Screen

/** Where cards lead. */
object Nav {
    /**
     * With the card's [stream] (and [avatar]), the player shows them while the video loads; [zap] is
     * the row it came from, for the Channels strip (none: the followed channels live).
     */
    fun openStream(context: Context, login: String, stream: Stream? = null, avatar: String? = null, zap: ZapList? = null) {
        PurpleApp.instance.zap = zap
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

    fun openCategory(context: Context, category: Category, origin: Screen = Screen.BROWSE) {
        context.startActivity(Intent(context, CategoryActivity::class.java)
            .putExtra(CategoryActivity.EXTRA_ID, category.id)
            .putExtra(CategoryActivity.EXTRA_NAME, category.name)
            .putExtra(CategoryActivity.EXTRA_ART, category.boxArtTemplate)
            .putExtra(CategoryActivity.EXTRA_ORIGIN, origin.name))
    }

    fun openChannel(context: Context, channel: FollowedChannel) {
        context.startActivity(Intent(context, ChannelActivity::class.java)
            .putExtra(ChannelActivity.EXTRA_LOGIN, channel.login))
    }

    fun openRecording(context: Context, recording: Recording) {
        context.startActivity(Intent(context, RecordedPlayerActivity::class.java)
            .putExtra(RecordedPlayerActivity.EXTRA_ID, recording.id)
            .putExtra(RecordedPlayerActivity.EXTRA_KIND, recording.kind.name)
            .putExtra(RecordedPlayerActivity.EXTRA_TITLE, recording.title))
    }
}
