package com.purpletv.player

import android.content.SharedPreferences

/** Player choices remembered across launches. */
class PlayerPrefs(private val prefs: SharedPreferences) {
    /** The tallest rendition to start with; [AUTO] lets ExoPlayer adapt. */
    var maxHeight: Int
        get() = prefs.getInt(MAX_HEIGHT, DEFAULT_HEIGHT)
        set(value) = prefs.edit().putInt(MAX_HEIGHT, value).apply()

    var preferHighFrameRate: Boolean
        get() = prefs.getBoolean(HIGH_FPS, true)
        set(value) = prefs.edit().putBoolean(HIGH_FPS, value).apply()

    val isAuto get() = maxHeight == AUTO

    companion object {
        const val AUTO = Int.MAX_VALUE
        private const val DEFAULT_HEIGHT = 1080
        private const val MAX_HEIGHT = "max_height"
        private const val HIGH_FPS = "high_fps"
    }
}
