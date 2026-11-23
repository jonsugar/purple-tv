package com.purpletv.auth

import com.purpletv.net.TwitchResponseException
import com.purpletv.net.decodeTwitchObject
import com.purpletv.net.requireResponseString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        val verificationUri = json.requireResponseString("verification_uri")
        if (verificationUri.toHttpUrlOrNull() == null) throw TwitchResponseException()
        return DeviceCode(
            deviceCode = json.requireResponseString("device_code"),
            userCode = json.requireResponseString("user_code"),
            verificationUri = verificationUri,
            expiresInSec = json.requirePositiveInt("expires_in"),
            intervalSec = json.requirePositiveInt("interval"),
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
                store(reply.requireOk())
                return
            }
            when (reply.errorMessage()) {
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
        store(reply.requireOk(), refreshToken)
        return true
    }

    private fun store(json: JSONObject, previousRefreshToken: String? = null) {
        val accessToken = json.requireResponseString("access_token")
        // The access token is sent in HTTP headers.
        if (accessToken.any { it !in '!'..'~' }) throw TwitchResponseException()
        val refreshToken = if (!json.has("refresh_token") && previousRefreshToken != null) {
            previousRefreshToken
        } else {
            json.requireResponseString("refresh_token")
        }
        tokens.accessToken = accessToken
        tokens.refreshToken = refreshToken
    }

    private fun JSONObject.requirePositiveInt(key: String): Int =
        (opt(key) as? Int)?.takeIf { it > 0 } ?: throw TwitchResponseException()

    private class Reply(val code: Int, val body: String) {
        val ok get() = code in 200..299
        fun requireOk(): JSONObject = if (ok) decodeTwitchObject(body) else throw IOException("Twitch answered HTTP $code")

        fun errorMessage(): String? = try {
            decodeTwitchObject(body).opt("message") as? String
        } catch (_: TwitchResponseException) {
            null
        }
    }

    private suspend fun post(path: String, form: FormBody): Reply = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base.resolve(path)!!).post(form).build()
        http.newCall(request).execute().use { response ->
            Reply(response.code, response.body?.string().orEmpty())
        }
    }

    private companion object {
        const val SLOW_DOWN_SEC = 5
    }
}
