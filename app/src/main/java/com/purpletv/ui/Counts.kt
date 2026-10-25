package com.purpletv.ui

import android.content.Context
import com.purpletv.R
import com.purpletv.twitch.formatViewers
import java.text.DecimalFormatSymbols
import java.util.Locale

/** "1.2K viewers", or "1,2K spettatori" on a TV set to Italian. */
fun Context.viewersText(n: Int): String {
    val separator = DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator
    return getString(R.string.viewers, formatViewers(n, separator, getString(R.string.count_million)))
}
