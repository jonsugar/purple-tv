package com.purpletv.twitch

import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.InMemoryTokenStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HelixClientTest {
    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore()
    private lateinit var helix: HelixClient

    private val oneStream = """{"data":[{"user_id":"1","user_login":"pgl","user_name":"PGL","game_id":"32399",
        "game_name":"Counter-Strike","title":"Major","viewer_count":5,"thumbnail_url":"t"}]}"""

    @Before
    fun setUp() {
        server.start()
        tokens.accessToken = "a"
        tokens.refreshToken = "r"
        val http = OkHttpClient()
        val auth = DeviceAuth(http, CLIENT_ID, tokens, server.url("/oauth2/").toString())
        helix = HelixClient(http, tokens, auth, server.url("/helix/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendsClientIdAndBearer() = runTest {
        server.enqueue(MockResponse().setBody(oneStream))

        val streams = helix.followedStreams("42")

        val request = server.takeRequest()
        assertEquals(CLIENT_ID, request.getHeader("Client-Id"))
        assertEquals("Bearer a", request.getHeader("Authorization"))
        val url = request.requestUrl!!
        assertEquals("/helix/streams/followed", url.encodedPath)
        assertEquals("42", url.queryParameter("user_id"))
        assertEquals("100", url.queryParameter("first"))
        assertEquals("pgl", streams.single().login)
    }

    @Test
    fun usersBatchesIds() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        helix.users(listOf("1", "2"))
        val small = server.takeRequest().requestUrl!!
        assertEquals("/helix/users", small.encodedPath)
        assertEquals(listOf("1", "2"), small.queryParameterValues("id"))

        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        helix.users((1..150).map { it.toString() })
        assertEquals(100, server.takeRequest().requestUrl!!.queryParameterValues("id").size)
        assertEquals(50, server.takeRequest().requestUrl!!.queryParameterValues("id").size)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun refreshesOnceOn401ThenRetries() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"access_token":"a2","refresh_token":"r2"}"""))
        server.enqueue(MockResponse().setBody(oneStream))

        val streams = helix.followedStreams("42")

        assertEquals("pgl", streams.single().login)
        assertEquals(3, server.requestCount)
        server.takeRequest()
        assertEquals("/oauth2/token", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("Bearer a2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun throwsNotLoggedInWhenRefreshFails() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"status":400,"message":"Invalid refresh token"}"""))

        try {
            helix.followedStreams("42")
            fail("expected NotLoggedInException")
        } catch (e: NotLoggedInException) {
            assertNull(tokens.accessToken)
        }
    }

    private companion object {
        const val CLIENT_ID = "your-twitch-client-id"
    }
}
