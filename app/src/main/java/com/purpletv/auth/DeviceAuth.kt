package com.purpletv.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSec: Int,
    val intervalSec: Int,
)

class DeviceCodeExpiredException : IOException("device code expired")

/**
 * Twitch's Device Code Flow for a public client: the TV shows a code, the user confirms it at
 * twitch.tv/activate, and the TV receives the tokens into [tokens]. No client secret exists.
 */
class DeviceAuth(
    private val http: OkHttpClient,
    val clientId: String,
    private val scopes: String,
    private val tokens: TokenStore,
    baseUrl: String = "https://id.twitch.tv/oauth2/",
) {
    private val base = baseUrl.toHttpUrl()
    private val refreshing = Mutex()

    val accessToken: String? get() = tokens.accessToken

    suspend fun start(): DeviceCode {
        val json = post("device", FormBody.Builder()
            .add("client_id", clientId)
            .add("scopes", scopes)
            .build()).requireOk()
        return DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            expiresInSec = json.getInt("expires_in"),
            intervalSec = json.getInt("interval"),
        )
    }

    /** Waits until the user confirms the code, then stores the tokens. */
    suspend fun poll(code: DeviceCode) {
        var intervalSec = code.intervalSec
        while (true) {
            delay(intervalSec * 1_000L)
            val reply = post("token", FormBody.Builder()
                .add("client_id", clientId)
                .add("scopes", scopes)
                .add("device_code", code.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build())
            if (reply.ok) {
                store(reply.json)
                return
            }
            when (reply.json.optString("message")) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalSec += SLOW_DOWN_SEC
                "expired_token", "invalid device code" -> throw DeviceCodeExpiredException()
                else -> throw IOException("device login failed: HTTP ${reply.code}")
            }
        }
    }

    /**
     * Swaps the refresh token for new tokens; false when Twitch no longer accepts it. [failed] is
     * the access token that got the 401: Twitch takes each refresh token once, so calls that fail
     * together refresh one at a time, and a call whose token was already replaced uses the new one.
     */
    suspend fun refresh(failed: String?): Boolean = refreshing.withLock {
        val current = tokens.accessToken
        if (current != null && current != failed) return true
        val refreshToken = tokens.refreshToken ?: return false
        val reply = post("token", FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build())
        if (reply.code == 400 || reply.code == 401) return false
        store(reply.requireOk())
        return true
    }

    private fun store(json: JSONObject) {
        tokens.accessToken = json.getString("access_token")
        tokens.refreshToken = json.optString("refresh_token").ifEmpty { tokens.refreshToken }
    }

    private class Reply(val code: Int, val json: JSONObject) {
        val ok get() = code in 200..299
        fun requireOk(): JSONObject = if (ok) json else throw IOException("Twitch answered HTTP $code")
    }

    private suspend fun post(path: String, form: FormBody): Reply = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base.resolve(path)!!).post(form).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            Reply(response.code, runCatching { JSONObject(text) }.getOrDefault(JSONObject()))
        }
    }

    private companion object {
        const val SLOW_DOWN_SEC = 5
    }
}
