package com.purpletv.twitch

import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.InMemoryTokenStore
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RecordedPlaybackApiTest {
    private val server = MockWebServer()
    private lateinit var api: RecordedPlaybackApi
    @Before fun setup() {
        server.start()
        val tokens = InMemoryTokenStore().apply { accessToken = "signed-in" }
        val http = OkHttpClient()
        val auth = DeviceAuth(http, "client", "user:read:follows", tokens)
        api = RecordedPlaybackApi(http, TwitchGraphQl(http, auth, server.url("/gql").toString()), server.url("/").toString())
    }
    @After fun teardown() { server.shutdown() }

    @Test fun resolvesAndChecksTheSignedVodPlaylist() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"videoPlaybackAccessToken":{"value":"token value","signature":"signature"}}}"""))
        server.enqueue(MockResponse().setBody("#EXTM3U"))
        val url = api.vodUrl("42").toHttpUrl()
        val request = server.takeRequest()
        assertEquals("OAuth signed-in", request.getHeader("Authorization"))
        assertEquals("42", JSONObject(request.body.readUtf8()).getJSONObject("variables").getString("id"))
        assertEquals("/vod/42.m3u8", url.encodedPath)
        assertEquals("token value", url.queryParameter("nauth"))
        assertEquals("signature", url.queryParameter("nauthsig"))
        assertEquals(url, server.takeRequest().requestUrl)
    }

    @Test fun accessDeniedIsUnavailableRatherThanOfflineOrAnAnonymousRetry() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"videoPlaybackAccessToken":{"value":"t","signature":"s"}}}"""))
        server.enqueue(MockResponse().setResponseCode(403))
        try { api.vodUrl("42"); fail("Expected unavailable") }
        catch (_: RecordingUnavailableException) { assertEquals(2, server.requestCount) }
    }

    @Test fun clipQualitiesUseSignedUrlsAndIgnoreUnavailableSources() {
        val data = JSONObject("""{"clip":{"playbackAccessToken":{"value":"token + value","signature":"sig"},"videoQualities":[
            {"quality":"720","frameRate":60,"sourceURL":"https://example.com/clip.mp4?existing=1"},
            {"quality":"1080","frameRate":60,"sourceURL":"https://example.com/full.mp4"},
            {"quality":"480","frameRate":30,"sourceURL":""}]}}""")
        val clips = RecordedPlaybackApi.parseClip(data)
        assertEquals(listOf(1080, 720), clips.map { it.height })
        val url = clips[1].url.toHttpUrl()
        assertEquals("token + value", url.queryParameter("token"))
        assertEquals("sig", url.queryParameter("sig"))
        assertEquals("1", url.queryParameter("existing"))
    }

    @Test(expected = RecordingUnavailableException::class) fun deletedClipIsUnavailable() {
        RecordedPlaybackApi.parseClip(JSONObject("""{"clip":null}"""))
    }
}
