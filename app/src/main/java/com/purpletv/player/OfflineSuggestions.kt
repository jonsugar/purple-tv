package com.purpletv.player

import com.purpletv.twitch.Stream

/** What the offline page offers instead: followed channels live, without the one that is offline. */
fun offlineSuggestions(followed: List<Stream>, offlineLogin: String, max: Int = 4): List<Stream> =
    followed.filterNot { it.login.equals(offlineLogin, ignoreCase = true) }.take(max)
