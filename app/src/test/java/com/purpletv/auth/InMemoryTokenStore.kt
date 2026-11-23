package com.purpletv.auth

class InMemoryTokenStore : TokenStore by PrefsTokenStore(FakePrefs())

fun TokenStore.signIn(accessToken: String, refreshToken: String? = null, userId: String? = null) {
    check(signIn(session.id, LoginTokens(accessToken, refreshToken)))
    if (userId != null) check(updateUserId(session.id, userId))
}
