package com.purpletv.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsTokenStoreTest {

    @Test
    fun forgetsTheTwitchCookieAnEarlierBuildStored() {
        // An earlier build kept the twitch.tv auth-token cookie under this key; nothing reads it any more.
        val prefs = FakePrefs(mapOf("session_auth_token" to "cookie", "access_token" to "a"))

        PrefsTokenStore(prefs)

        assertFalse(prefs.contains("session_auth_token"))
        assertEquals("a", prefs.getString("access_token", null))
    }

    @Test
    fun replacementLoginRejectsWritesAndClearsFromThePreviousSession() {
        val prefs = FakePrefs(mapOf("access_token" to "old", "refresh_token" to "old-refresh", "user_id" to "42"))
        val tokens = PrefsTokenStore(prefs)
        val previous = tokens.session.id
        assertEquals("old", tokens.accessToken)
        assertEquals("old-refresh", tokens.refreshToken)
        assertEquals("42", tokens.userId)

        assertTrue(tokens.signIn(previous, LoginTokens("new", "new-refresh")))
        assertNull(tokens.userId)
        assertFalse(tokens.updateUserId(previous, "42"))
        assertFalse(tokens.updateTokens(previous, LoginTokens("stale", "stale-refresh")))
        assertFalse(tokens.signIn(previous, LoginTokens("stale-login", "stale-login-refresh")))
        assertNull(tokens.clearLogin(previous))

        assertEquals("new", tokens.accessToken)
        assertEquals("new-refresh", tokens.refreshToken)
        assertFalse(prefs.contains("user_id"))
        assertEquals("new", prefs.getString("access_token", null))
        assertEquals("new-refresh", prefs.getString("refresh_token", null))
    }
}
