package com.purpletv.home

import com.purpletv.twitch.Category
import com.purpletv.twitch.Stream

/**
 * What Home last loaded, for the screens that show the same things without asking Twitch again:
 * the empty search, the zap strip, the offline page and the category page's rail.
 */
class HomeSnapshot {
    var followedLive: List<Stream> = emptyList()
    var avatars: Map<String, String> = emptyMap()
    var recommendedCategories: List<Category> = emptyList()
    var meAvatar: String? = null
}
