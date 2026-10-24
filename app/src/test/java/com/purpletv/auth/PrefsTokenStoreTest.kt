package com.purpletv.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
