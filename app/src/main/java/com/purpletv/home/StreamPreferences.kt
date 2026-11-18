package com.purpletv.home

import android.content.SharedPreferences
import com.purpletv.twitch.StreamLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One language setting for all live lists on this device. */
class StreamPreferences(private val prefs: SharedPreferences) {
    private val selection = MutableStateFlow(
        if (prefs.getBoolean(ENGLISH_ONLY, false)) StreamLanguage.ENGLISH else StreamLanguage.ALL,
    )
    val language = selection.asStateFlow()

    fun toggleLanguage() {
        val english = selection.value == StreamLanguage.ALL
        prefs.edit().putBoolean(ENGLISH_ONLY, english).apply()
        selection.value = if (english) StreamLanguage.ENGLISH else StreamLanguage.ALL
    }

    private companion object {
        const val ENGLISH_ONLY = "english_only"
    }
}
