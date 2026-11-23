package com.purpletv.auth

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import okhttp3.Call

/** Process-local identity. Every sign-in and sign-out starts a new one. */
class SessionId internal constructor() {
    internal val calls = mutableSetOf<Call>() // Guarded by the token store lock.
    internal val refreshing = Mutex()
    internal var refusedRefresh: LoginTokens? = null // Guarded by refreshing.
}

class SessionChangedException : CancellationException("Twitch session changed")

class LoginTokens(val accessToken: String?, val refreshToken: String?)

class LoginSession(val id: SessionId, val tokens: LoginTokens, val userId: String?) {
    val accessToken get() = tokens.accessToken
    val refreshToken get() = tokens.refreshToken
}

/** The credentials the app keeps. Tokens are never logged or shown. */
interface TokenStore {
    val session: LoginSession
    val accessToken get() = session.accessToken
    val refreshToken get() = session.refreshToken
    val userId get() = session.userId

    fun signIn(expected: SessionId, credentials: LoginTokens): Boolean
    fun updateTokens(expected: SessionId, credentials: LoginTokens): Boolean
    fun updateUserId(expected: SessionId, userId: String): Boolean

    /** Forgets the login and returns the new signed-out identity, or null for an obsolete caller. */
    fun clearLogin(expected: SessionId? = null): SessionId?

    /** Applies local side effects while this session is still current. */
    fun runIfCurrent(expected: SessionId, action: () -> Unit): Boolean
}

class PrefsTokenStore(private val prefs: SharedPreferences) : TokenStore {
    private val lock = Any()
    private var current = LoginSession(
        SessionId(), LoginTokens(prefs.getString(ACCESS, null), prefs.getString(REFRESH, null)),
        prefs.getString(USER_ID, null),
    )

    init {
        // An earlier build kept the twitch.tv auth-token cookie here; no build reads it now.
        if (prefs.contains(OLD_SESSION_COOKIE)) prefs.edit().remove(OLD_SESSION_COOKIE).apply()
    }

    override val session: LoginSession
        get() = synchronized(lock) { current }

    override fun signIn(expected: SessionId, credentials: LoginTokens): Boolean =
        runIfCurrent(expected) { save(LoginSession(SessionId(), credentials, null)) }

    override fun updateTokens(expected: SessionId, credentials: LoginTokens): Boolean =
        runIfCurrent(expected) { save(LoginSession(expected, credentials, current.userId)) }

    override fun updateUserId(expected: SessionId, userId: String): Boolean =
        runIfCurrent(expected) { save(LoginSession(expected, current.tokens, userId)) }

    override fun clearLogin(expected: SessionId?): SessionId? = synchronized(lock) {
        if (expected != null) {
            if (current.id !== expected) return null
            if (current.accessToken == null && current.refreshToken == null && current.userId == null) return expected
        }
        val next = LoginSession(SessionId(), LoginTokens(null, null), null)
        save(next)
        next.id
    }

    override fun runIfCurrent(expected: SessionId, action: () -> Unit): Boolean = synchronized(lock) {
        if (current.id !== expected) return false
        action()
        true
    }

    private fun save(next: LoginSession) {
        val previous = current.id
        current = next
        if (previous !== next.id) {
            previous.calls.forEach(Call::cancel)
            previous.calls.clear()
        }
        prefs.edit().apply {
            putOrRemove(ACCESS, next.accessToken)
            putOrRemove(REFRESH, next.refreshToken)
            putOrRemove(USER_ID, next.userId)
        }.apply()
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private companion object {
        const val ACCESS = "access_token"
        const val REFRESH = "refresh_token"
        const val USER_ID = "user_id"
        const val OLD_SESSION_COOKIE = "session_auth_token"
    }
}
