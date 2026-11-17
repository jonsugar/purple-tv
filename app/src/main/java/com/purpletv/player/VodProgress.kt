package com.purpletv.player

import android.content.SharedPreferences

/** Positions belong to an account and video, and survive activity and app restarts. */
class VodProgress(private val prefs: SharedPreferences) {
    fun position(account: String, video: String): Long = prefs.getLong(key(account, video), 0).coerceAtLeast(0)

    fun save(account: String, video: String, position: Long, completed: Boolean = false) {
        if (account.isEmpty() || video.isEmpty()) return
        prefs.edit().apply {
            if (completed || position <= 0) remove(key(account, video)) else putLong(key(account, video), position)
        }.apply()
    }

    private fun key(account: String, video: String) = "$account:$video"
}
