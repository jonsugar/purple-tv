package com.purpletv.auth

import com.purpletv.net.TwitchResponseException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class DeviceAuthTest {
    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore()
    private lateinit var auth: DeviceAuth

    private val code = DeviceCode("d", "ABCD1234", "https://www.twitch.tv/activate", 1800, 5)
    private val startBody =
        """{"device_code":"d","user_code":"ABCD1234","verification_uri":"https://www.twitch.tv/activate","expires_in":1800,"interval":5}"""

    @Before
    fun setUp() {
        server.start()
        auth = DeviceAuth(OkHttpClient(), CLIENT_ID, "user:read:follows", tokens, server.url("/").toString())
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
        server.enqueue(MockResponse().setBody(startBody))

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
    fun startRejectsInvalidSuccessfulResponses() = runTest {
        val invalidFields = listOf(
            "device_code" to JSONObject.NULL,
            "user_code" to " ",
            "verification_uri" to "invalid-uri",
            "expires_in" to 0,
            "interval" to "5",
        ).map { (key, value) -> JSONObject(startBody).put(key, value).toString() }

        for (body in listOf("", """{"sensitive":"value"""", "[]", "{}", "$startBody{}") + invalidFields) {
            server.enqueue(MockResponse().setBody(body))
            expectResponseError { auth.start() }
        }
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
    fun pollRejectsIncompleteTokensWithoutChangingCredentials() = runTest {
        tokens.accessToken = "old-access"
        tokens.refreshToken = "old-refresh"
        tokens.userId = "42"

        val invalidBodies = listOf(
            "",
            """{"access_token":""",
            """{"access_token":"new-access"}""",
            """{"access_token":null,"refresh_token":"new-refresh"}""",
            """{"access_token":"new-access","refresh_token":false}""",
            """{"access_token":"new-access","refresh_token":" "}""",
            """{"access_token":"new\naccess","refresh_token":"new-refresh"}""",
        )
        for (body in invalidBodies) {
            server.enqueue(MockResponse().setBody(body))
            expectResponseError { auth.poll(code) }
            assertEquals("old-access", tokens.accessToken)
            assertEquals("old-refresh", tokens.refreshToken)
            assertEquals("42", tokens.userId)
        }
    }

    @Test
    fun refreshStoresNewTokens() = runTest {
        tokens.refreshToken = "r"
        server.enqueue(MockResponse().setBody("""{"access_token":"a2","refresh_token":"r2"}"""))

        assertTrue(auth.refresh("a"))

        val form = server.takeRequest().form()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("r", form["refresh_token"])
        assertEquals(CLIENT_ID, form["client_id"])
        assertFalse(form.containsKey("client_secret"))
        assertEquals("a2", tokens.accessToken)
        assertEquals("r2", tokens.refreshToken)
    }

    @Test
    fun refreshKeepsTheExistingRefreshTokenWhenOmitted() = runTest {
        tokens.accessToken = "a"
        tokens.refreshToken = "r"
        server.enqueue(MockResponse().setBody("""{"access_token":"a2"}"""))

        assertTrue(auth.refresh("a"))
        assertEquals("a2", tokens.accessToken)
        assertEquals("r", tokens.refreshToken)
    }

    @Test
    fun refreshRejectsInvalidTokensWithoutChangingCredentials() = runTest {
        tokens.accessToken = "a"
        tokens.refreshToken = "r"
        tokens.userId = "42"

        for (body in listOf("""{"refresh_token":"r2"}""", """{"access_token":"a2","refresh_token":null}""")) {
            server.enqueue(MockResponse().setBody(body))
            expectResponseError { auth.refresh("a") }
            assertEquals("a", tokens.accessToken)
            assertEquals("r", tokens.refreshToken)
            assertEquals("42", tokens.userId)
        }
    }

    @Test
    fun refreshFailsOn400() = runTest {
        tokens.refreshToken = "r"
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"status":400,"message":"Invalid refresh token"}"""))

        assertFalse(auth.refresh("a"))
    }

    @Test
    fun parallel401sRefreshOnce() = runTest {
        // Twitch takes each refresh token once: a second refresh with r1 would get a 400.
        tokens.accessToken = "a1"
        tokens.refreshToken = "r1"
        server.enqueue(MockResponse().setHeadersDelay(300, TimeUnit.MILLISECONDS)
            .setBody("""{"access_token":"a2","refresh_token":"r2"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"status":400,"message":"Invalid refresh token"}"""))

        val first = async { auth.refresh("a1") }
        val second = async { auth.refresh("a1") }

        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, server.requestCount)
        assertEquals("a2", tokens.accessToken)
        assertEquals("r2", tokens.refreshToken)
    }

    private fun currentTime(scope: TestScope) = scope.currentTime

    private suspend fun expectResponseError(call: suspend () -> Unit) {
        try {
            call()
            fail("expected TwitchResponseException")
        } catch (e: TwitchResponseException) {
            assertEquals("Unexpected Twitch response", e.message)
            assertNull(e.cause)
        }
    }

    private companion object {
        const val CLIENT_ID = "your-twitch-client-id"
    }
}
