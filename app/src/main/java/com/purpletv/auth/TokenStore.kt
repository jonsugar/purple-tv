package com.purpletv.auth

import android.content.SharedPreferences

/** The credentials the app keeps. The twitch.tv session token is never logged or shown. */
interface TokenStore {
    var accessToken: String?
    var refreshToken: String?
    var userId: String?
    var sessionAuthToken: String?

    /** Forgets the Helix login; the twitch.tv session token stays. */
    fun clearLogin()
}

class PrefsTokenStore(private val prefs: SharedPreferences) : TokenStore {
    override var accessToken: String?
        get() = prefs.getString(ACCESS, null)
        set(value) = put(ACCESS, value)
    override var refreshToken: String?
        get() = prefs.getString(REFRESH, null)
        set(value) = put(REFRESH, value)
    override var userId: String?
        get() = prefs.getString(USER_ID, null)
        set(value) = put(USER_ID, value)
    override var sessionAuthToken: String?
        get() = prefs.getString(SESSION, null)
        set(value) = put(SESSION, value)

    override fun clearLogin() {
        prefs.edit().remove(ACCESS).remove(REFRESH).remove(USER_ID).apply()
    }

    private fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    private companion object {
        const val ACCESS = "access_token"
        const val REFRESH = "refresh_token"
        const val USER_ID = "user_id"
        const val SESSION = "session_auth_token"
    }
}
