package com.purpletv.auth

class InMemoryTokenStore : TokenStore {
    override var accessToken: String? = null
    override var refreshToken: String? = null
    override var userId: String? = null
    override var sessionAuthToken: String? = null

    override fun clearLogin() {
        accessToken = null
        refreshToken = null
        userId = null
    }
}
