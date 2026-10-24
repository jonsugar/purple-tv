package com.purpletv.twitch

import com.purpletv.auth.DeviceAuth
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
        val auth = DeviceAuth(OkHttpClient(), TV_CLIENT_ID, "user:read:follows", tokens, server.url("/oauth2/").toString())
        api = PlaybackApi(OkHttpClient(), auth, server.url("/gql").toString(), server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueToken() {
        server.enqueue(MockResponse().setBody(
            """{"data":{"streamPlaybackAccessToken":{"value":"{\"subscriber\":false}","signature":"abc"}}}"""))
    }

    /** The shape of the TV app's StreamPlayer_AccessToken answer. */
    private fun enqueueSignedInToken() {
        server.enqueue(MockResponse().setBody(
            """{"data":{"user":{"stream":{"playbackAccessToken":{"value":"{\"subscriber\":true}","signature":"tv"}}}}}"""))
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
    fun sendsTheLoginTokenWithItsOwnClientId() = runTest {
        tokens.accessToken = "secret"
        enqueueSignedInToken()
        server.enqueue(MockResponse().setBody("#EXTM3U"))

        val url = api.liveStreamUrl("pgl").toHttpUrl()

        val gql = server.takeRequest()
        assertEquals("OAuth secret", gql.getHeader("Authorization"))
        assertEquals(TV_CLIENT_ID, gql.getHeader("Client-ID"))
        assertEquals("tv", url.queryParameter("sig"))
    }

    @Test
    fun asksLikeTheOfficialTvAppWhenSignedIn() = runTest {
        tokens.accessToken = "secret"
        enqueueSignedInToken()
        server.enqueue(MockResponse().setBody("#EXTM3U"))

        api.liveStreamUrl("pgl")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"operationName\":\"StreamPlayer_AccessToken\""))
        assertTrue(body.contains("\"platform\":\"androidtv_web_tv\""))
        assertTrue(body.contains("\"playerType\":\"pulsar\""))
        assertTrue(body.contains("\"login\":\"pgl\""))
    }

    @Test
    fun signedInOfflineChannelIsOffline() = runTest {
        tokens.accessToken = "secret"
        server.enqueue(MockResponse().setBody("""{"data":{"user":{"stream":null}}}"""))

        try {
            api.liveStreamUrl("pgl")
            fail("expected StreamOfflineException")
        } catch (e: StreamOfflineException) {
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun refreshesAnExpiredTokenOnce() = runTest {
        tokens.accessToken = "old"
        tokens.refreshToken = "r1"
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"access_token":"new","refresh_token":"r2"}"""))
        enqueueSignedInToken()
        server.enqueue(MockResponse().setBody("#EXTM3U"))

        api.liveStreamUrl("pgl")

        assertEquals("OAuth old", server.takeRequest().getHeader("Authorization"))
        assertEquals("/oauth2/token", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("OAuth new", server.takeRequest().getHeader("Authorization"))
        assertEquals("r2", tokens.refreshToken)
    }

    @Test
    fun playsAnonymouslyWhenTwitchRefusesTheToken() = runTest {
        tokens.accessToken = "old"
        tokens.refreshToken = "r1"
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"status":400,"message":"Invalid refresh token"}"""))
        enqueueToken()
        server.enqueue(MockResponse().setBody("#EXTM3U"))

        api.liveStreamUrl("pgl")

        server.takeRequest()
        server.takeRequest()
        val anonymous = server.takeRequest()
        assertNull(anonymous.getHeader("Authorization"))
        assertEquals("kimne78kx3ncx6brgo4mv6wki5h1ko", anonymous.getHeader("Client-ID"))
        // Signing out stays with Helix, which owns the login; playback just goes on without it.
        assertEquals("old", tokens.accessToken)
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

    private companion object {
        const val TV_CLIENT_ID = "tvclient"
    }
}
