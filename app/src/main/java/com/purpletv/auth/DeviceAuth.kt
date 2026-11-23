package com.purpletv.auth

import com.purpletv.net.TwitchResponseException
import com.purpletv.net.decodeTwitchObject
import com.purpletv.net.requireResponseString
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resumeWithException

data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSec: Int,
    val intervalSec: Int,
    internal val session: SessionId,
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
    val sessionId: SessionId get() = tokens.session.id

    internal fun credentials(session: SessionId): LoginSession =
        tokens.session.takeIf { it.id === session } ?: throw SessionChangedException()

    internal fun requireCurrent(session: SessionId) {
        credentials(session)
    }

    internal fun runIfCurrent(session: SessionId, action: () -> Unit) {
        if (!tokens.runIfCurrent(session, action)) throw SessionChangedException()
    }

    /** Checks the session on both sides of a request, including when the request fails. */
    internal inline fun <T> sessionRequest(session: SessionId, request: () -> T): T {
        requireCurrent(session)
        try {
            return request()
        } finally {
            requireCurrent(session)
        }
    }

    /** Submits the call only while its session is current. The response is consumed off the caller thread. */
    internal suspend fun <T> execute(session: SessionId, call: Call, read: (Response) -> T): T {
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                val callback = object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resumeWith(runCatching { response.use(read) })
                    }
                }
                runIfCurrent(session) {
                    if (continuation.isActive) {
                        session.calls.add(call)
                        call.enqueue(callback)
                    }
                }
            }
        } finally {
            runIfCurrent(session) { session.calls.remove(call) }
        }
    }

    suspend fun start(): DeviceCode {
        val session = sessionId
        val json = post(session, "device", FormBody.Builder()
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
            session = session,
        )
    }

    /** Waits until the user confirms the code, then stores the tokens. */
    suspend fun poll(code: DeviceCode) {
        var intervalSec = code.intervalSec
        while (true) {
            delay(intervalSec * 1_000L)
            val reply = post(code.session, "token", FormBody.Builder()
                .add("client_id", clientId)
                .add("scopes", scopes)
                .add("device_code", code.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build())
            if (reply.ok) {
                if (!tokens.signIn(code.session, decodeTokens(reply.requireOk()))) throw SessionChangedException()
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

    /** Returns the renewed access token, or null when Twitch refuses the refresh. */
    suspend fun refresh(failed: LoginSession): String? = failed.id.refreshing.withLock {
        val current = credentials(failed.id)
        // Requests that used the same credential pair share its refresh result.
        if (current.tokens !== failed.tokens) return current.accessToken
        if (failed.id.refusedRefresh === current.tokens) return null
        val refreshToken = current.refreshToken ?: return null
        val reply = post(failed.id, "token", FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build())
        if (reply.code == 400 || reply.code == 401) {
            failed.id.refusedRefresh = current.tokens
            return null
        }
        val renewed = decodeTokens(reply.requireOk(), refreshToken)
        if (!tokens.updateTokens(failed.id, renewed)) throw SessionChangedException()
        return renewed.accessToken
    }

    private fun decodeTokens(json: JSONObject, previousRefreshToken: String? = null): LoginTokens {
        val accessToken = json.requireResponseString("access_token")
        // The access token is sent in HTTP headers.
        if (accessToken.any { it !in '!'..'~' }) throw TwitchResponseException()
        val refreshToken = if (!json.has("refresh_token") && previousRefreshToken != null) {
            previousRefreshToken
        } else {
            json.requireResponseString("refresh_token")
        }
        return LoginTokens(accessToken, refreshToken)
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

    private suspend fun post(session: SessionId, path: String, form: FormBody): Reply {
        val request = Request.Builder().url(base.resolve(path)!!).post(form).build()
        return execute(session, http.newCall(request)) { response ->
            Reply(response.code, response.body?.string().orEmpty())
        }
    }

    private companion object {
        const val SLOW_DOWN_SEC = 5
    }
}
