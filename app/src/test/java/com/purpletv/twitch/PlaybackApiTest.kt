package com.purpletv.twitch

import com.purpletv.auth.InMemoryTokenStore
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class PlaybackApiTest {
    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore()
    private lateinit var api: PlaybackApi

    @Before
    fun setUp() {
        server.start()
        api = PlaybackApi(OkHttpClient(), tokens, server.url("/gql").toString(), server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueToken() {
        server.enqueue(MockResponse().setBody(
            """{"data":{"streamPlaybackAccessToken":{"value":"{\"subscriber\":false}","signature":"abc"}}}"""))
    }

    @Test
    fun sendsWebClientIdAndNoAuthorizationWhenLoggedOut() = runTest {
        enqueueToken()
        server.enqueue(MockResponse().setBody("#EXTM3U"))

        val url = api.liveStreamUrl("pgl").toHttpUrl()

        val gql = server.takeRequest()
        assertEquals("kimne78kx3ncx6brgo4mv6wki5h1ko", gql.getHeader("Client-ID"))
        assertNull(gql.getHeader("Authorization"))
        assertTrue(gql.body.readUtf8().contains("\"playerType\":\"site\""))
        assertEquals("/api/channel/hls/pgl.m3u8", url.encodedPath)
        assertEquals("abc", url.queryParameter("sig"))
        assertEquals("true", url.queryParameter("allow_source"))
        assertEquals("avc1", url.queryParameter("supported_codecs"))
    }

    @Test
    fun sendsOAuthHeaderWhenSessionTokenSet() = runTest {
        tokens.sessionAuthToken = "secret"
        enqueueToken()
        server.enqueue(MockResponse().setBody("#EXTM3U"))

        api.liveStreamUrl("pgl")

        assertEquals("OAuth secret", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun lowercasesAndTrimsLogin() = runTest {
        enqueueToken()
        server.enqueue(MockResponse().setBody("#EXTM3U"))

        api.liveStreamUrl("  PGL ")

        server.takeRequest()
        assertEquals("/api/channel/hls/pgl.m3u8", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun throwsOfflineWhenUsherAnswers404() = runTest {
        enqueueToken()
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            api.liveStreamUrl("pgl")
            fail("expected StreamOfflineException")
        } catch (e: StreamOfflineException) {
            // expected
        }
    }

    @Test
    fun throwsWhenTokenMissing() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"streamPlaybackAccessToken":null}}"""))

        try {
            api.liveStreamUrl("nobody")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e !is StreamOfflineException)
        }
    }
}
