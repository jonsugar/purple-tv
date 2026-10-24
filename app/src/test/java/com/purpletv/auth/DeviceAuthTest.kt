package com.purpletv.auth

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class DeviceAuthTest {
    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore()
    private lateinit var auth: DeviceAuth

    private val code = DeviceCode("d", "ABCD1234", "https://www.twitch.tv/activate", 1800, 5)

    @Before
    fun setUp() {
        server.start()
        auth = DeviceAuth(OkHttpClient(), CLIENT_ID, tokens, server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun RecordedRequest.form(): Map<String, String> = body.readUtf8().split("&").associate {
        val (k, v) = it.split("=", limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private fun pending(message: String) =
        MockResponse().setResponseCode(400).setBody("""{"status":400,"message":"$message"}""")

    @Test
    fun startPostsClientIdAndScope() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"device_code":"d","user_code":"ABCD1234","verification_uri":"https://www.twitch.tv/activate","expires_in":1800,"interval":5}"""))

        val result = auth.start()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/device", request.requestUrl!!.encodedPath)
        val form = request.form()
        assertEquals(CLIENT_ID, form["client_id"])
        assertEquals("user:read:follows", form["scopes"])
        assertEquals(code, result)
    }

    @Test
    fun pollWaitsWhilePendingThenStoresTokens() = runTest {
        server.enqueue(pending("authorization_pending"))
        server.enqueue(pending("authorization_pending"))
        server.enqueue(MockResponse().setBody("""{"access_token":"a","refresh_token":"r"}"""))

        auth.poll(code)

        assertEquals(3, server.requestCount)
        repeat(3) {
            val form = server.takeRequest().form()
            assertEquals("urn:ietf:params:oauth:grant-type:device_code", form["grant_type"])
            assertEquals("d", form["device_code"])
        }
        assertEquals("a", tokens.accessToken)
        assertEquals("r", tokens.refreshToken)
    }

    @Test
    fun pollSlowsDownOnSlowDown() = runTest {
        server.enqueue(pending("slow_down"))
        server.enqueue(MockResponse().setBody("""{"access_token":"a","refresh_token":"r"}"""))

        auth.poll(code)

        // One interval before the first request, interval + 5 s before the second.
        assertEquals(5_000L + 10_000L, currentTime(this))
    }

    @Test
    fun pollThrowsWhenExpired() = runTest {
        server.enqueue(pending("expired_token"))

        try {
            auth.poll(code)
            fail("expected DeviceCodeExpiredException")
        } catch (e: DeviceCodeExpiredException) {
            assertNull(tokens.accessToken)
        }
    }

    @Test
    fun refreshStoresNewTokens() = runTest {
        tokens.refreshToken = "r"
        server.enqueue(MockResponse().setBody("""{"access_token":"a2","refresh_token":"r2"}"""))

        assertTrue(auth.refresh())

        val form = server.takeRequest().form()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("r", form["refresh_token"])
        assertEquals(CLIENT_ID, form["client_id"])
        assertFalse(form.containsKey("client_secret"))
        assertEquals("a2", tokens.accessToken)
        assertEquals("r2", tokens.refreshToken)
    }

    @Test
    fun refreshFailsOn400() = runTest {
        tokens.refreshToken = "r"
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"status":400,"message":"Invalid refresh token"}"""))

        assertFalse(auth.refresh())
    }

    private fun currentTime(scope: TestScope) = scope.currentTime

    private companion object {
        const val CLIENT_ID = "your-twitch-client-id"
    }
}
