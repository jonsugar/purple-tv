package com.purpletv.twitch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.IOException

enum class FollowKind { CHANNEL, CATEGORY }

/** Increased only after Twitch confirms a follow change, so cached lists can refresh. */
class FollowChanges {
    private val updates = MutableStateFlow(0L)
    val revisions = updates.asStateFlow()
    val revision get() = updates.value
    fun changed() { updates.update { it + 1 } }
}

/** Follows use the same internal API and signed-in TV client as playback and categories. */
class FollowApi(
    private val gql: TwitchGraphQl,
    private val changes: FollowChanges,
    private val onUnfollowed: (String?, FollowKind, String) -> Unit = { _, _, _ -> },
) {
    private val unfollowRequest = Mutex()

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

    suspend fun unfollow(kind: FollowKind, id: String, accountId: String? = null) {
        if (id.isBlank()) throw IOException("Missing unfollow target")
        if (!unfollowRequest.tryLock()) throw IOException("Another unfollow is in progress")
        try {
            requestUnfollow(kind, id)
            onUnfollowed(accountId, kind, id)
            changes.changed()
        } finally {
            unfollowRequest.unlock()
        }
    }

    private suspend fun requestUnfollow(kind: FollowKind, id: String) {
        when (kind) {
            FollowKind.CHANNEL -> {
                val input = JSONObject().put("targetID", id)
                val result = gql.mutate("UnfollowChannel", UNFOLLOW_CHANNEL, JSONObject().put("input", input))
                if (result.optJSONObject("unfollowUser")?.optString("__typename") != "UnfollowUserPayload" ||
                    isFollowing(kind, id)
                ) throw IOException("Channel unfollow was not confirmed")
            }
            FollowKind.CATEGORY -> {
                val input = JSONObject().put("gameID", id)
                val result = gql.mutate("UnfollowCategory", UNFOLLOW_CATEGORY, JSONObject().put("input", input))
                if (result.optJSONObject("unfollowGame")?.optJSONObject("game")?.optString("id") != id) {
                    throw IOException("Category unfollow was not confirmed")
                }
            }
        }
    }

    private companion object {
        const val CHANNEL_STATE = "query ChannelFollowState(\$id:ID!) { user(id:\$id) { self { follower { followedAt } } } }"
        const val CATEGORY_STATE = "query CategoryFollowState { currentUser { followedGames(first:100,type:ALL) { nodes { id } } } }"
        const val FOLLOW_CHANNEL = "mutation FollowChannel(\$input:FollowUserInput!) { followUser(input:\$input) { follow { followedAt } } }"
        const val FOLLOW_CATEGORY = "mutation FollowCategory(\$input:FollowGameInput!) { followGame(input:\$input) { game { id } } }"
        const val UNFOLLOW_CHANNEL = "mutation UnfollowChannel(\$input:UnfollowUserInput!) { unfollowUser(input:\$input) { __typename } }"
        const val UNFOLLOW_CATEGORY = "mutation UnfollowCategory(\$input:UnfollowGameInput!) { unfollowGame(input:\$input) { game { id } } }"
    }
}
