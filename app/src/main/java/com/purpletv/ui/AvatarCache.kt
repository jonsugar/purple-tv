package com.purpletv.ui

import com.purpletv.twitch.HelixClient

/** Profile pictures by user id, asked from Helix only for ids not seen before. */
class AvatarCache(private val helix: HelixClient) {
    private val known = mutableMapOf<String, String>()

    suspend fun urls(userIds: List<String>): Map<String, String> {
        val missing = userIds.distinct().filter { it.isNotEmpty() && it !in known }
        if (missing.isNotEmpty()) helix.users(missing).forEach { known[it.id] = it.profileImageUrl }
        return userIds.mapNotNull { id -> known[id]?.let { id to it } }.toMap()
    }
}
