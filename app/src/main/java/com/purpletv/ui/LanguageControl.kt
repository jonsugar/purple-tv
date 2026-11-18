package com.purpletv.ui

import android.widget.TextView
import com.purpletv.R
import com.purpletv.twitch.StreamLanguage

internal fun TextView.showStreamLanguage(language: StreamLanguage) {
    val option = if (language == StreamLanguage.ENGLISH) R.string.language_english else R.string.language_all
    text = context.getString(R.string.language_control, context.getString(option))
}

internal fun StreamLanguage.emptyMessage(default: Int): Int =
    if (this == StreamLanguage.ENGLISH) R.string.language_no_matches else default
