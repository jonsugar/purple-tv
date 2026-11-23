package com.purpletv.home

import com.purpletv.auth.SessionId
import com.purpletv.auth.TokenStore
import com.purpletv.twitch.Category
import com.purpletv.twitch.Stream
import com.purpletv.twitch.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Home data shared by Search, the player and the navigation rails for the active login. */
class HomeSnapshot(private val tokens: TokenStore) {
    data class Content(
        val followedLive: List<Stream> = emptyList(),
        val avatars: Map<String, String> = emptyMap(),
        val recommendedCategories: List<Category> = emptyList(),
        val profile: User? = null,
    )

    private data class Entry(val session: SessionId, val content: Content)

    private val empty = Content()
    private val entry = MutableStateFlow<Entry?>(null)

    val current: Content
        get() = contentFor(tokens.session.id)

    // Read the active identity again on each emission, including queued emissions from an old login.
    val updates = combine(tokens.sessionIds, entry) { _, _ ->
        val session = tokens.session.id
        session to contentFor(session)
    }.distinctUntilChanged()

    fun publishProfile(session: SessionId, user: User) = update(session) { it.copy(profile = user) }

    fun publishRows(
        session: SessionId,
        followedLive: List<Stream>,
        avatars: Map<String, String>,
        recommendedCategories: List<Category>,
    ) = update(session) {
        it.copy(followedLive = followedLive, avatars = avatars, recommendedCategories = recommendedCategories)
    }

    internal fun clearPreviousSession() {
        val session = tokens.session.id
        tokens.runIfCurrent(session) {
            if (entry.value?.session !== session) entry.value = null
        }
    }

    private fun contentFor(session: SessionId): Content =
        entry.value?.takeIf { it.session === session }?.content ?: empty

    private fun update(session: SessionId, change: (Content) -> Content): Boolean {
        if (tokens.accessToken == null) return false
        return tokens.runIfCurrent(session) {
            entry.value = Entry(session, change(contentFor(session)))
        }
    }
}
