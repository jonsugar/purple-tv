package com.purpletv.twitch

import android.util.Log
import com.purpletv.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlin.random.Random

class StreamOfflineException(login: String) : IOException("$login is offline")

/**
 * Resolves a channel to its HLS master playlist.
 *
 * The public Helix API has no playback endpoint, so this asks the GraphQL API of the twitch.tv
 * web player for a playback access token, the same way Streamlink does, and signs the usher
 * playlist URL with it. Personal use only: this is not a documented Twitch API. With the user's
 * twitch.tv session set, Twitch applies the user's subscriptions to the token.
 */
class PlaybackApi(
    private val http: OkHttpClient,
    private val tokens: TokenStore,
    private val gqlUrl: String = "https://gql.twitch.tv/gql",
    private val usherBase: String = "https://usher.ttvnw.net/",
) {
    /** The master playlist URL of a live channel, checked to exist. */
    suspend fun liveStreamUrl(login: String): String = withContext(Dispatchers.IO) {
        val channel = login.trim().lowercase()
        val token = requestToken(channel)
        val url = usherBase.toHttpUrl().newBuilder()
            .addPathSegments("api/channel/hls/$channel.m3u8")
            .addQueryParameter("sig", token.getString("signature"))
            .addQueryParameter("token", token.getString("value"))
            .addQueryParameter("allow_source", "true")
            .addQueryParameter("player_backend", "mediaplayer")
            .addQueryParameter("playlist_include_framerate", "true")
            .addQueryParameter("supported_codecs", "avc1")
            .addQueryParameter("p", Random.nextInt(1_000_000).toString())
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code == 404) throw StreamOfflineException(channel)
            if (!response.isSuccessful) throw IOException("usher answered HTTP ${response.code}")
        }
        url.toString()
    }

    private fun requestToken(channel: String): JSONObject {
        val variables = JSONObject()
            .put("isLive", true)
            .put("login", channel)
            .put("isVod", false)
            .put("vodID", "")
            .put("playerType", "site")
        val body = JSONObject()
            .put("operationName", "PlaybackAccessToken_Template")
            .put("query", TOKEN_QUERY)
            .put("variables", variables)
        val request = Request.Builder()
            .url(gqlUrl)
            .header("Client-ID", WEB_CLIENT_ID)
            .apply { tokens.sessionAuthToken?.let { header("Authorization", "OAuth $it") } }
            .post(body.toString().toRequestBody(JSON))
            .build()
        val text = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GQL answered HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val token = JSONObject(text).optJSONObject("data")?.optJSONObject("streamPlaybackAccessToken")
            ?: throw IOException("no playback token for $channel")
        logEntitlements(channel, token.optString("value"))
        return token
    }

    /** Only the two booleans: the token itself never reaches the log. */
    private fun logEntitlements(channel: String, value: String) {
        val claims = runCatching { JSONObject(value) }.getOrNull() ?: return
        Log.i(TAG, "token for $channel subscriber=${claims.optBoolean("subscriber")} turbo=${claims.optBoolean("turbo")}")
    }

    private companion object {
        const val TAG = "PurpleTV"
        const val WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
        val JSON = "application/json".toMediaType()
        const val TOKEN_QUERY = "query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, " +
            "\$vodID: ID!, \$isVod: Boolean!, \$playerType: String!) { " +
            "streamPlaybackAccessToken(channelName: \$login, params: {platform: \"web\", playerBackend: \"mediaplayer\", " +
            "playerType: \$playerType}) @include(if: \$isLive) { value signature __typename } " +
            "videoPlaybackAccessToken(id: \$vodID, params: {platform: \"web\", playerBackend: \"mediaplayer\", " +
            "playerType: \$playerType}) @include(if: \$isVod) { value signature __typename } }"
    }
}
