package com.purpletv.twitch

import com.purpletv.auth.SessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException

enum class FollowKind { CHANNEL, CATEGORY }

enum class FollowStatus { FOLLOWING, NOT_FOLLOWING, STILL_FOLLOWING, UNFOLLOW_UNVERIFIED }

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
    // Guarded by the session lock. Retain unfinished checks across screen bindings.
    private var pendingSession: SessionId? = null
    private val pendingChannels = mutableSetOf<String>()

    suspend fun status(kind: FollowKind, id: String, session: SessionId = gql.sessionId): FollowStatus {
        if (kind == FollowKind.CHANNEL && hasPendingUnfollow(id, session)) {
            unfollowRequest.withLock {
                if (hasPendingUnfollow(id, session)) return verifyChannelUnfollow(id, session)
            }
        }
        return if (readFollowing(kind, id, session)) FollowStatus.FOLLOWING else FollowStatus.NOT_FOLLOWING
    }

    private suspend fun readFollowing(kind: FollowKind, id: String, session: SessionId): Boolean = when (kind) {
        FollowKind.CHANNEL -> {
            val user = gql.query("ChannelFollowState", CHANNEL_STATE, JSONObject().put("id", id), session).optJSONObject("user")
                ?: throw IOException("Channel unavailable")
            val self = user.optJSONObject("self") ?: throw NotLoggedInException(session)
            when (self.opt("follower")) {
                JSONObject.NULL -> false
                is JSONObject -> true
                else -> throw IOException("Missing channel follow status")
            }
        }
        FollowKind.CATEGORY -> {
            // Unlike LIVE, ALL includes followed categories that currently have no streams.
            val user = gql.query("CategoryFollowState", CATEGORY_STATE, session = session).optJSONObject("currentUser") ?: throw NotLoggedInException(session)
            val nodes = user.optJSONObject("followedGames")?.optJSONArray("nodes") ?: throw IOException("Missing category follows")
            (0 until nodes.length()).any { nodes.optJSONObject(it)?.optString("id") == id }
        }
    }

    suspend fun follow(kind: FollowKind, id: String, session: SessionId = gql.sessionId) {
        if (id.isBlank()) throw IOException("Missing follow target")
        when (kind) {
            FollowKind.CHANNEL -> {
                val input = JSONObject().put("targetID", id).put("disableNotifications", false)
                val result = gql.mutate("FollowChannel", FOLLOW_CHANNEL, JSONObject().put("input", input), session)
                val follow = result.optJSONObject("followUser")?.optJSONObject("follow")
                    ?: throw IOException("Channel follow was not confirmed")
                if (follow.optString("followedAt").let { it.isBlank() || it == "null" }) throw IOException("Channel follow was not confirmed")
            }
            FollowKind.CATEGORY -> {
                val result = gql.mutate("FollowCategory", FOLLOW_CATEGORY, JSONObject().put("input", JSONObject().put("gameID", id)), session)
                if (result.optJSONObject("followGame")?.optJSONObject("game")?.optString("id") != id) {
                    throw IOException("Category follow was not confirmed")
                }
            }
        }
        gql.runIfCurrent(session) { changes.changed() }
    }

    suspend fun unfollow(kind: FollowKind, id: String, session: SessionId = gql.sessionId): FollowStatus {
        if (id.isBlank()) throw IOException("Missing unfollow target")
        if (!unfollowRequest.tryLock()) throw IOException("Another unfollow is in progress")
        try {
            if (kind == FollowKind.CHANNEL && hasPendingUnfollow(id, session)) {
                return verifyChannelUnfollow(id, session)
            }
            requestUnfollow(kind, id, session)
            gql.runIfCurrent(session) {
                if (kind == FollowKind.CATEGORY) {
                    publishUnfollow(kind, id, session)
                } else {
                    if (pendingSession !== session) {
                        pendingChannels.clear()
                        pendingSession = session
                    }
                    pendingChannels.add(id)
                }
            }
            if (kind == FollowKind.CATEGORY) return FollowStatus.NOT_FOLLOWING

            val status = verifyChannelUnfollow(id, session)
            return if (status == FollowStatus.UNFOLLOW_UNVERIFIED) verifyChannelUnfollow(id, session) else status
        } finally {
            unfollowRequest.unlock()
        }
    }

    private fun hasPendingUnfollow(id: String, session: SessionId): Boolean {
        var pending = false
        gql.runIfCurrent(session) { pending = pendingSession === session && id in pendingChannels }
        return pending
    }

    private suspend fun verifyChannelUnfollow(id: String, session: SessionId): FollowStatus {
        val following = try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(UNFOLLOW_CHECK_TIMEOUT_MS) { readFollowing(FollowKind.CHANNEL, id, session) }
            }
        } catch (_: IOException) {
            null
        }
        gql.runIfCurrent(session) {
            if (following != null) {
                pendingChannels.remove(id)
                if (!following) publishUnfollow(FollowKind.CHANNEL, id, session)
            }
        }
        return when (following) {
            null -> FollowStatus.UNFOLLOW_UNVERIFIED
            true -> FollowStatus.STILL_FOLLOWING
            false -> FollowStatus.NOT_FOLLOWING
        }
    }

    private fun publishUnfollow(kind: FollowKind, id: String, session: SessionId) {
        onUnfollowed(gql.credentials(session).userId, kind, id)
        changes.changed()
    }

    private suspend fun requestUnfollow(kind: FollowKind, id: String, session: SessionId) {
        when (kind) {
            FollowKind.CHANNEL -> {
                val input = JSONObject().put("targetID", id)
                val result = gql.mutate("UnfollowChannel", UNFOLLOW_CHANNEL, JSONObject().put("input", input), session)
                if (result.optJSONObject("unfollowUser")?.optString("__typename") != "UnfollowUserPayload") {
                    throw IOException("Channel unfollow was not acknowledged")
                }
            }
            FollowKind.CATEGORY -> {
                val input = JSONObject().put("gameID", id)
                val result = gql.mutate("UnfollowCategory", UNFOLLOW_CATEGORY, JSONObject().put("input", input), session)
                if (result.optJSONObject("unfollowGame")?.optJSONObject("game")?.optString("id") != id) {
                    throw IOException("Category unfollow was not confirmed")
                }
            }
        }
    }

    private companion object {
        const val UNFOLLOW_CHECK_TIMEOUT_MS = 15_000L
        const val CHANNEL_STATE = "query ChannelFollowState(\$id:ID!) { user(id:\$id) { self { follower { followedAt } } } }"
        const val CATEGORY_STATE = "query CategoryFollowState { currentUser { followedGames(first:100,type:ALL) { nodes { id } } } }"
        const val FOLLOW_CHANNEL = "mutation FollowChannel(\$input:FollowUserInput!) { followUser(input:\$input) { follow { followedAt } } }"
        const val FOLLOW_CATEGORY = "mutation FollowCategory(\$input:FollowGameInput!) { followGame(input:\$input) { game { id } } }"
        const val UNFOLLOW_CHANNEL = "mutation UnfollowChannel(\$input:UnfollowUserInput!) { unfollowUser(input:\$input) { __typename } }"
        const val UNFOLLOW_CATEGORY = "mutation UnfollowCategory(\$input:UnfollowGameInput!) { unfollowGame(input:\$input) { game { id } } }"
    }
}
