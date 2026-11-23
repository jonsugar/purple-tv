package com.purpletv.player

import android.app.Activity
import android.content.Intent
import android.os.BadParcelableException
import android.os.Bundle

/** Accepts a channel login from the install shortcut and starts the internal player. */
class PlayerShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val channel = try {
            intent.getStringExtra(PlayerActivity.EXTRA_CHANNEL)
        } catch (_: BadParcelableException) {
            null
        }
        if (channel != null && CHANNEL_LOGIN.matches(channel)) {
            startActivity(Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL, channel.lowercase())
                .putExtra(PlayerActivity.EXTRA_SHORTCUT, true))
        }
        finish()
    }

    private companion object {
        val CHANNEL_LOGIN = Regex("[A-Za-z0-9_]{1,25}")
    }
}
