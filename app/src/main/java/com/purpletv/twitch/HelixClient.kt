package com.purpletv.twitch

import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.LoginSession
import com.purpletv.auth.SessionChangedException
import com.purpletv.auth.SessionId
import com.purpletv.auth.TokenStore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class NotLoggedInException(
    val session: SessionId? = null,
    private val signedOut: SessionId? = null,
) : IOException("not logged in to Twitch") {
    fun clearLogin(tokens: TokenStore): Boolean {
        if (signedOut != null) return tokens.session.id === signedOut
        val expected = session ?: return tokens.accessToken == null
        return tokens.clearLogin(expected) != null
    }
}

internal fun LoginSession.requireAccessToken(): String = accessToken ?: throw NotLoggedInException(id)

/** The official Helix API with the user's login. A 401 refreshes the tokens once, then retries. */
class HelixClient(
    private val http: OkHttpClient,
    private val tokens: TokenStore,
    private val auth: DeviceAuth,
    baseUrl: String = "https://api.twitch.tv/helix/",
) {
    private val base = baseUrl.toHttpUrl()

    val sessionId: SessionId get() = auth.sessionId

    suspend fun me(session: SessionId = sessionId): User = HelixParser.profile(get("users", session = session))

    suspend fun users(ids: List<String>, session: SessionId = sessionId): List<User> =
        ids.chunked(MAX_IDS).flatMap { chunk ->
            HelixParser.users(get("users", chunk.map { "id" to it }, session))
        }

    /** One channel by its login, live or not; null when there is no such account. */
    suspend fun userByLogin(login: String): User? =
        HelixParser.users(get("users", listOf("login" to login.trim().lowercase()))).firstOrNull()

    suspend fun followedStreams(userId: String, session: SessionId = sessionId): List<Stream> =
        collectPages(key = Stream::userId) { followedStreamsPage(userId, it, session) }

    suspend fun followedStreamsPage(userId: String, cursor: String? = null, session: SessionId = sessionId): Page<Stream> =
        HelixParser.streams(get("streams/followed", listOfNotNull("user_id" to userId, "first" to "100", cursor?.let { "after" to it }), session))

    suspend fun followedChannels(userId: String, cursor: String? = null, session: SessionId = sessionId): Page<FollowedChannel> =
        HelixParser.followedChannels(get("channels/followed", listOfNotNull("user_id" to userId, "first" to "100", cursor?.let { "after" to it }), session))

    suspend fun allFollowedChannels(userId: String, session: SessionId = sessionId): List<FollowedChannel> =
        sortedChannels(collectPages(key = { it.id }) { followedChannels(userId, it, session) })

    suspend fun videos(broadcasterId: String, cursor: String? = null): Page<Recording> =
        HelixParser.videos(get("videos", listOfNotNull("user_id" to broadcasterId, "type" to "archive", "sort" to "time",
            "first" to PAGE_SIZE, cursor?.let { "after" to it })))

    suspend fun clips(broadcasterId: String, window: ClipWindow, cursor: String? = null): Page<Recording> =
        HelixParser.clips(get("clips", listOfNotNull("broadcaster_id" to broadcasterId, "first" to PAGE_SIZE,
            window.start?.let { "started_at" to it }, window.end?.let { "ended_at" to it }, cursor?.let { "after" to it })))

    /** The live stream of one channel, or null when it is offline. */
    suspend fun streamByLogin(login: String): Stream? =
        HelixParser.streams(get("streams", listOf("user_login" to login.trim().lowercase()))).items.firstOrNull()

    /** Live streams and their language for a set of channel IDs. */
    suspend fun streamsByUserIds(ids: List<String>, session: SessionId = sessionId): List<Stream> =
        ids.distinct().chunked(MAX_IDS).flatMap { chunk ->
            HelixParser.streams(get("streams", listOf("first" to chunk.size.toString()) + chunk.map { "user_id" to it }, session)).items
        }

    suspend fun topStreams(gameId: String? = null, cursor: String? = null, language: StreamLanguage = StreamLanguage.ALL, session: SessionId = sessionId): Page<Stream> =
        HelixParser.streams(get("streams", listOfNotNull(
            "first" to PAGE_SIZE, gameId?.let { "game_id" to it }, cursor?.let { "after" to it },
            language.code?.let { "language" to it }), session)).let { it.copy(items = language.filter(it.items)) }

    suspend fun topCategories(cursor: String? = null, session: SessionId = sessionId): Page<Category> =
        HelixParser.categories(get("games/top", listOfNotNull("first" to PAGE_SIZE, cursor?.let { "after" to it }), session))

    suspend fun searchChannels(query: String, liveOnly: Boolean = false, session: SessionId = sessionId): List<ChannelResult> =
        HelixParser.channels(get("search/channels", listOf("query" to query, "live_only" to liveOnly.toString(), "first" to "20"), session))

    suspend fun searchCategories(query: String, session: SessionId = sessionId): List<Category> =
        HelixParser.categories(get("search/categories", listOf("query" to query, "first" to "20"), session)).items

    private suspend fun get(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        session: SessionId = sessionId,
    ): String {
        val url = base.newBuilder().addPathSegments(path)
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val credentials = auth.credentials(session)
        val first = fetch(url, session, credentials.requireAccessToken())
        if (first.code != 401) return first.requireOk()
        val renewed = auth.refresh(credentials)
        if (renewed != null) {
            val retry = fetch(url, session, renewed)
            if (retry.code != 401) return retry.requireOk()
        }
        val signedOut = tokens.clearLogin(session) ?: throw SessionChangedException()
        throw NotLoggedInException(session, signedOut)
    }

    private class Reply(val code: Int, val body: String) {
        fun requireOk(): String = if (code in 200..299) body else throw IOException("Helix answered HTTP $code")
    }

    private suspend fun fetch(url: HttpUrl, session: SessionId, token: String): Reply {
        val request = Request.Builder().url(url)
            .header("Client-Id", auth.clientId)
            .header("Authorization", "Bearer $token")
            .build()
        return auth.execute(session, http.newCall(request)) { Reply(it.code, it.body?.string().orEmpty()) }
    }

    private companion object {
        const val MAX_IDS = 100
        const val PAGE_SIZE = "40"
    }
}
