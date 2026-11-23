package com.purpletv.net

import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.InMemoryTokenStore
import com.purpletv.auth.signIn
import com.purpletv.twitch.NotLoggedInException
import com.purpletv.twitch.TwitchGraphQl
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DebugModeTest {
    @Test
    fun freshModeIsOffAndCannotBlockNetworkingUntilEnabled() {
        val mode = DebugMode()
        assertEquals(DebugMode.State(), mode.state.value)
        mode.setNetworkingDisabled(true)
        assertEquals(DebugMode.State(), mode.state.value)
        mode.setEnabled(true)
        assertEquals(DebugMode.State(enabled = true), mode.state.value)
        mode.setNetworkingDisabled(true)
        assertEquals(DebugMode.State(enabled = true, networkingDisabled = true), mode.state.value)
        mode.setEnabled(false)
        assertEquals(DebugMode.State(), mode.state.value)
        mode.setEnabled(true)
        assertEquals(DebugMode.State(enabled = true), mode.state.value)
    }

    @Test
    fun apiAndAuthFailBeforeNetworkAndTurningOffDebugRestoresRequests() = runTest {
        MockWebServer().use { server ->
            val mode = DebugMode().apply { setEnabled(true) }
            val http = Http.client(mode)
            val tokens = InMemoryTokenStore().apply { signIn("saved", "refresh", "42") }
            val auth = DeviceAuth(http, "tv", "", tokens, server.url("/oauth2/").toString())
            val gql = TwitchGraphQl(http, auth, server.url("/gql").toString())
            val query = "{ ok }"

            server.enqueue(MockResponse().setBody("""{"data":{"ok":true}}"""))
            assertTrue(gql.query("Test", query).getBoolean("ok"))
            mode.setNetworkingDisabled(true)

            assertConnectionFailure { gql.query("Test", query) }
            assertConnectionFailure { auth.start() }
            assertConnectionFailure { auth.refresh(tokens.session) }
            assertEquals(1, server.requestCount)
            assertEquals("saved", tokens.accessToken)
            assertEquals("refresh", tokens.refreshToken)
            assertEquals("42", tokens.userId)

            mode.setEnabled(false)
            mode.setEnabled(true)
            server.enqueue(MockResponse().setBody("""{"data":{"ok":true}}"""))
            assertTrue(gql.query("Test", query).getBoolean("ok"))
            assertEquals(2, server.requestCount)
        }
    }

    private suspend fun assertConnectionFailure(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected a connection failure")
        } catch (error: IOException) {
            assertFalse(error is NotLoggedInException)
        }
    }
}
