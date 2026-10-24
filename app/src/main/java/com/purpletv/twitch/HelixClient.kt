package com.purpletv.twitch

import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class NotLoggedInException : IOException("not logged in to Twitch")

/** The official Helix API with the user's login. A 401 refreshes the tokens once, then retries. */
class HelixClient(
    private val http: OkHttpClient,
    private val tokens: TokenStore,
    private val auth: DeviceAuth,
    baseUrl: String = "https://api.twitch.tv/helix/",
) {
    private val base = baseUrl.toHttpUrl()

    suspend fun me(): User = HelixParser.users(get("users")).firstOrNull() ?: throw NotLoggedInException()

    suspend fun users(ids: List<String>): List<User> = ids.chunked(MAX_IDS).flatMap { chunk ->
        HelixParser.users(get("users", chunk.map { "id" to it }))
    }

    suspend fun followedStreams(userId: String): List<Stream> =
        HelixParser.streams(get("streams/followed", listOf("user_id" to userId, "first" to "100"))).items

    /** The live stream of one channel, or null when it is offline. */
    suspend fun streamByLogin(login: String): Stream? =
        HelixParser.streams(get("streams", listOf("user_login" to login.trim().lowercase()))).items.firstOrNull()

    suspend fun topStreams(gameId: String? = null, cursor: String? = null): Page<Stream> =
        HelixParser.streams(get("streams", listOfNotNull(
            "first" to PAGE_SIZE, gameId?.let { "game_id" to it }, cursor?.let { "after" to it })))

    suspend fun topCategories(cursor: String? = null): Page<Category> =
        HelixParser.categories(get("games/top", listOfNotNull("first" to PAGE_SIZE, cursor?.let { "after" to it })))

    suspend fun searchChannels(query: String, liveOnly: Boolean = false): List<ChannelResult> =
        HelixParser.channels(get("search/channels", listOf("query" to query, "live_only" to liveOnly.toString(), "first" to "20")))

    suspend fun searchCategories(query: String): List<Category> =
        HelixParser.categories(get("search/categories", listOf("query" to query, "first" to "20"))).items

    private suspend fun get(path: String, query: List<Pair<String, String>> = emptyList()): String {
        val url = base.newBuilder().addPathSegments(path)
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val token = tokens.accessToken ?: throw NotLoggedInException()
        val first = fetch(url, token)
        if (first.code != 401) return first.requireOk()
        if (auth.refresh(failed = token)) {
            val retry = fetch(url, tokens.accessToken ?: throw NotLoggedInException())
            if (retry.code != 401) return retry.requireOk()
        }
        tokens.clearLogin()
        throw NotLoggedInException()
    }

    private class Reply(val code: Int, val body: String) {
        fun requireOk(): String = if (code in 200..299) body else throw IOException("Helix answered HTTP $code")
    }

    private suspend fun fetch(url: HttpUrl, token: String): Reply = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("Client-Id", auth.clientId)
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { Reply(it.code, it.body?.string().orEmpty()) }
    }

    private companion object {
        const val MAX_IDS = 100
        const val PAGE_SIZE = "40"
    }
}
