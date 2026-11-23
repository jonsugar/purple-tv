package com.purpletv.twitch

import com.purpletv.auth.SessionId
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class RecordingUnavailableException : IOException("Recording unavailable")

data class ClipRendition(val url: String, val height: Int, val frameRate: Float)

/** Resolves recorded media on demand, preserving Twitch's signed-in access checks. */
class RecordedPlaybackApi(
    private val http: OkHttpClient,
    private val gql: TwitchGraphQl,
    private val usherBase: String = "https://usher.ttvnw.net/",
) {
    suspend fun vodUrl(id: String, session: SessionId = gql.sessionId): String = gql.sessionRequest(session) {
        val data = gql.query("RecordedAccessToken", VOD_QUERY, JSONObject().put("id", id), session)
        val token = data.optJSONObject("videoPlaybackAccessToken") ?: throw RecordingUnavailableException()
        val value = token.optString("value").takeIf { it.isNotEmpty() && it != "null" } ?: throw RecordingUnavailableException()
        val signature = token.optString("signature").takeIf { it.isNotEmpty() && it != "null" } ?: throw RecordingUnavailableException()
        val url = usherBase.toHttpUrl().newBuilder().addPathSegments("vod/$id.m3u8")
            .addQueryParameter("nauth", value)
            .addQueryParameter("nauthsig", signature)
            .addQueryParameter("allow_source", "true").addQueryParameter("allow_audio_only", "true")
            .addQueryParameter("player_backend", "mediaplayer").addQueryParameter("supported_codecs", "avc1").build()
        val request = Request.Builder().url(url).build()
        gql.execute(session, http.newCall(request)) {
            if (it.code == 403 || it.code == 404) throw RecordingUnavailableException()
            if (!it.isSuccessful) throw IOException("Recording playlist HTTP ${it.code}")
        }
        url.toString()
    }

    suspend fun clipRenditions(id: String): List<ClipRendition> {
        val data = gql.query("RecordedClip", CLIP_QUERY, JSONObject().put("slug", id))
        return parseClip(data)
    }

    companion object {
        private const val PARAMS = "{platform:\"androidtv_web_tv\",playerBackend:\"mediaplayer\",playerType:\"pulsar\"}"
        private const val VOD_QUERY = "query RecordedAccessToken(\$id:ID!) { videoPlaybackAccessToken(id:\$id,params:" + PARAMS + ") { value signature } }"
        private const val CLIP_QUERY = "query RecordedClip(\$slug:ID!) { clip(slug:\$slug) { playbackAccessToken(params:" + PARAMS +
            ") { value signature } videoQualities { quality frameRate sourceURL } } }"

        fun parseClip(data: JSONObject): List<ClipRendition> {
            val clip = data.optJSONObject("clip") ?: throw RecordingUnavailableException()
            val token = clip.optJSONObject("playbackAccessToken") ?: throw RecordingUnavailableException()
            val value = token.optString("value").takeIf { it.isNotEmpty() && it != "null" } ?: throw RecordingUnavailableException()
            val signature = token.optString("signature").takeIf { it.isNotEmpty() && it != "null" } ?: throw RecordingUnavailableException()
            val qualities = clip.optJSONArray("videoQualities") ?: throw RecordingUnavailableException()
            return (0 until qualities.length()).mapNotNull { i ->
                val quality = qualities.optJSONObject(i) ?: return@mapNotNull null
                val source = quality.optString("sourceURL").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                val height = quality.optString("quality").toIntOrNull() ?: return@mapNotNull null
                val url = source.toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("sig", signature)
                    ?.addQueryParameter("token", value)?.build()?.toString() ?: return@mapNotNull null
                ClipRendition(url, height, quality.optDouble("frameRate", 0.0).toFloat())
            }.distinctBy { it.height to it.frameRate }.sortedWith(compareByDescending<ClipRendition> { it.height }.thenByDescending { it.frameRate })
                .ifEmpty { throw RecordingUnavailableException() }
        }
    }
}
