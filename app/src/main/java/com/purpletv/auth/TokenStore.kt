package com.purpletv.auth

import android.content.SharedPreferences

/** The credentials the app keeps. Tokens are never logged or shown. */
interface TokenStore {
    var accessToken: String?
    var refreshToken: String?
    var userId: String?

    /** Forgets the login. */
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
    }
}
