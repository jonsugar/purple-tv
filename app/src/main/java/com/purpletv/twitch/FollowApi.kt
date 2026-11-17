package com.purpletv.twitch

import org.json.JSONObject
import java.io.IOException

enum class FollowKind { CHANNEL, CATEGORY }

/** Increased only after Twitch confirms a follow, so cached lists can refresh on return. */
class FollowChanges {
    var revision = 0L
        private set
    fun changed() { revision++ }
}

/** Follows use the same internal API and signed-in TV client as playback and categories. */
class FollowApi(private val gql: TwitchGraphQl, private val changes: FollowChanges) {
    suspend fun isFollowing(kind: FollowKind, id: String): Boolean = when (kind) {
        FollowKind.CHANNEL -> {
            val user = gql.query("ChannelFollowState", CHANNEL_STATE, JSONObject().put("id", id)).optJSONObject("user")
                ?: throw IOException("Channel unavailable")
            val self = user.optJSONObject("self") ?: throw NotLoggedInException()
            self.optJSONObject("follower") != null
        }
        FollowKind.CATEGORY -> {
            // Unlike LIVE, ALL includes followed categories that currently have no streams.
            val user = gql.query("CategoryFollowState", CATEGORY_STATE).optJSONObject("currentUser") ?: throw NotLoggedInException()
            val nodes = user.optJSONObject("followedGames")?.optJSONArray("nodes") ?: throw IOException("Missing category follows")
            (0 until nodes.length()).any { nodes.optJSONObject(it)?.optString("id") == id }
        }
    }

    suspend fun follow(kind: FollowKind, id: String) {
        if (id.isBlank()) throw IOException("Missing follow target")
        when (kind) {
            FollowKind.CHANNEL -> {
                val input = JSONObject().put("targetID", id).put("disableNotifications", false)
                val result = gql.mutate("FollowChannel", FOLLOW_CHANNEL, JSONObject().put("input", input))
                val follow = result.optJSONObject("followUser")?.optJSONObject("follow")
                    ?: throw IOException("Channel follow was not confirmed")
                if (follow.optString("followedAt").let { it.isBlank() || it == "null" }) throw IOException("Channel follow was not confirmed")
            }
            FollowKind.CATEGORY -> {
                val result = gql.mutate("FollowCategory", FOLLOW_CATEGORY, JSONObject().put("input", JSONObject().put("gameID", id)))
                if (result.optJSONObject("followGame")?.optJSONObject("game")?.optString("id") != id) {
                    throw IOException("Category follow was not confirmed")
                }
            }
        }
        changes.changed()
    }

    private companion object {
        const val CHANNEL_STATE = "query ChannelFollowState(\$id:ID!) { user(id:\$id) { self { follower { followedAt } } } }"
        const val CATEGORY_STATE = "query CategoryFollowState { currentUser { followedGames(first:100,type:ALL) { nodes { id } } } }"
        const val FOLLOW_CHANNEL = "mutation FollowChannel(\$input:FollowUserInput!) { followUser(input:\$input) { follow { followedAt } } }"
        const val FOLLOW_CATEGORY = "mutation FollowCategory(\$input:FollowGameInput!) { followGame(input:\$input) { game { id } } }"
    }
}
