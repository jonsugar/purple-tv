package com.purpletv.player

import android.view.KeyEvent
import com.purpletv.twitch.Stream

/** The channels the Channels strip offers: the row the stream was opened from. */
data class ZapList(val title: String, val streams: List<Stream>) {
    /** Where the strip's focus starts: the channel on air, else the first one. */
    fun focusIndex(currentLogin: String): Int =
        streams.indexOfFirst { it.login.equals(currentLogin, ignoreCase = true) }.coerceAtLeast(0)

    fun isEmpty(): Boolean = streams.isEmpty()
}

/** Up and Down stay in the open strip instead of moving to the overlay's buttons. */
fun zapPanelKeepsKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
