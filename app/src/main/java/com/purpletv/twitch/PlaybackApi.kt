package com.purpletv.twitch

import android.util.Log
import com.purpletv.auth.DeviceAuth
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
 * playlist URL with it. Personal use only: this is not a documented Twitch API. Signed in, it
 * asks the way Twitch's own Android TV app does (its StreamPlayer_AccessToken query, platform
 * androidtv_web_tv, the main "pulsar" player) with the user's token and that app's Client-ID, so
 * Twitch treats the user exactly as in its TV app, subscriptions included. Signed out, it asks
 * like the twitch.tv web player.
 */
class PlaybackApi(
    private val http: OkHttpClient,
    private val auth: DeviceAuth,
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

    private suspend fun requestToken(channel: String): JSONObject {
        val oauth = auth.accessToken
        if (oauth != null) {
            askForToken(channel, auth.clientId, oauth)?.let { return it }
            if (auth.refresh(failed = oauth)) askForToken(channel, auth.clientId, auth.accessToken)?.let { return it }
            // Play without the login rather than not at all; Helix decides whether to sign out.
            Log.i(TAG, "playback token refused, playing signed out")
        }
        return askForToken(channel, WEB_CLIENT_ID, null) ?: throw IOException("no playback token for $channel")
    }

    /** The playback token, or null when Twitch refuses the OAuth token (HTTP 401). */
    private fun askForToken(channel: String, clientId: String, oauth: String?): JSONObject? {
        val body = if (oauth != null) tvAppBody(channel) else webBody(channel)
        val request = Request.Builder()
            .url(gqlUrl)
            .header("Client-ID", clientId)
            .apply { oauth?.let { header("Authorization", "OAuth $it") } }
            .post(body.toString().toRequestBody(JSON))
            .build()
        val text = http.newCall(request).execute().use { response ->
            if (response.code == 401 && oauth != null) return null
            if (!response.isSuccessful) throw IOException("GQL answered HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val data = JSONObject(text).optJSONObject("data")
        val token = if (oauth == null) {
            data?.optJSONObject("streamPlaybackAccessToken")
        } else {
            data?.optJSONObject("user")?.let { user ->
                val stream = user.optJSONObject("stream") ?: throw StreamOfflineException(channel)
                stream.optJSONObject("playbackAccessToken")
            }
        } ?: throw IOException("no playback token for $channel")
        logEntitlements(channel, token.optString("value"))
        return token
    }

    private fun tvAppBody(channel: String) = JSONObject()
        .put("operationName", "StreamPlayer_AccessToken")
        .put("query", TV_TOKEN_QUERY)
        .put("variables", JSONObject()
            .put("login", channel)
            .put("platform", TV_PLATFORM)
            .put("playerType", TV_PLAYER_TYPE))

    private fun webBody(channel: String) = JSONObject()
        .put("operationName", "PlaybackAccessToken_Template")
        .put("query", TOKEN_QUERY)
        .put("variables", JSONObject()
            .put("isLive", true)
            .put("login", channel)
            .put("isVod", false)
            .put("vodID", "")
            .put("playerType", "site"))

    /** Only flags: the token, its signature and the ids in it never reach the log. */
    private fun logEntitlements(channel: String, value: String) {
        val claims = runCatching { JSONObject(value) }.getOrNull() ?: return
        Log.i(TAG, "token for $channel signedIn=${!claims.isNull("user_id")} subscriber=${claims.optBoolean("subscriber")} " +
            "turbo=${claims.optBoolean("turbo")} hideAds=${claims.optBoolean("hide_ads")} showAds=${claims.optBoolean("show_ads")}")
    }

    private companion object {
        const val TAG = "PurpleTV"
        const val WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
        val JSON = "application/json".toMediaType()

        // The live player of Twitch's Android TV app (tv.twitch.android.app 13, web app "celadon").
        const val TV_PLATFORM = "androidtv_web_tv"
        const val TV_PLAYER_TYPE = "pulsar"
        const val TV_TOKEN_QUERY = "query StreamPlayer_AccessToken(\$login: String!, \$platform: String!, \$playerType: String!) { " +
            "user(login: \$login) { stream { playbackAccessToken(params: {platform: \$platform, playerType: \$playerType}) " +
            "{ value signature } } } }"
        const val TOKEN_QUERY = "query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, " +
            "\$vodID: ID!, \$isVod: Boolean!, \$playerType: String!) { " +
            "streamPlaybackAccessToken(channelName: \$login, params: {platform: \"web\", playerBackend: \"mediaplayer\", " +
            "playerType: \$playerType}) @include(if: \$isLive) { value signature __typename } " +
            "videoPlaybackAccessToken(id: \$vodID, params: {platform: \"web\", playerBackend: \"mediaplayer\", " +
            "playerType: \$playerType}) @include(if: \$isVod) { value signature __typename } }"
    }
}
