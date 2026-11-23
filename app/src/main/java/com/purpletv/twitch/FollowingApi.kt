package com.purpletv.twitch

import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.SessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Authenticated Twitch GraphQL operations, sharing the existing token-refresh policy. */
class TwitchGraphQl(
    private val http: OkHttpClient,
    private val auth: DeviceAuth,
    private val url: String = "https://gql.twitch.tv/gql",
) {
    val sessionId: SessionId get() = auth.sessionId

    internal fun credentials(session: SessionId) = auth.credentials(session)

    internal fun runIfCurrent(session: SessionId, action: () -> Unit) = auth.runIfCurrent(session, action)

    internal inline fun <T> sessionRequest(session: SessionId, request: () -> T): T =
        auth.sessionRequest(session, request)

    internal suspend fun <T> execute(session: SessionId, call: Call, read: (Response) -> T): T =
        auth.execute(session, call, read)

    suspend fun mutate(name: String, mutation: String, variables: JSONObject, session: SessionId = sessionId): JSONObject =
        query(name, mutation, variables, session)

    suspend fun query(
        name: String,
        query: String,
        variables: JSONObject = JSONObject(),
        session: SessionId = sessionId,
    ): JSONObject = sessionRequest(session) {
        withContext(Dispatchers.IO) {
            val credentials = auth.credentials(session)
            val first = ask(session, name, query, variables, credentials.requireAccessToken())
            if (first != null) return@withContext first
            val renewed = auth.refresh(credentials) ?: throw NotLoggedInException(session)
            ask(session, name, query, variables, renewed) ?: throw NotLoggedInException(session)
        }
    }

    private suspend fun ask(session: SessionId, name: String, query: String, variables: JSONObject, token: String): JSONObject? {
        val body = JSONObject().put("operationName", name).put("query", query).put("variables", variables)
        val request = Request.Builder().url(url).header("Client-ID", auth.clientId)
            .header("Authorization", "OAuth $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return execute(session, http.newCall(request)) { response ->
            if (response.code == 401) return@execute null
            if (!response.isSuccessful) throw IOException("Twitch answered HTTP ${response.code}")
            try {
                val root = JSONObject(response.body?.string().orEmpty())
                val errors = root.optJSONArray("errors")
                if (errors != null && errors.length() > 0) {
                    if ((0 until errors.length()).any {
                        errors.optJSONObject(it)?.optJSONObject("extensions")?.optString("code") == "Unauthenticated"
                    }) return@execute null
                    throw IOException("Twitch could not load this content")
                }
                root.optJSONObject("data") ?: throw IOException("Missing Twitch data")
            } catch (e: JSONException) {
                throw IOException("Unexpected Twitch response", e)
            }
        }
    }
}

class FollowingApi(private val gql: TwitchGraphQl) {
    suspend fun liveCategories(session: SessionId = gql.sessionId): List<Category> =
        parse(gql.query("FollowingCategories", QUERY, session = session), session)

    companion object {
        // This connection has nodes and no cursor; the server enforces a maximum of 100.
        private const val QUERY = "query FollowingCategories { currentUser { followedGames(first:100,type:LIVE) { " +
            "nodes { id displayName boxArtURL viewersCount } } } }"

        fun parse(data: JSONObject, session: SessionId? = null): List<Category> {
            val user = data.optJSONObject("currentUser") ?: throw NotLoggedInException(session)
            val nodes = user.optJSONObject("followedGames")?.optJSONArray("nodes") ?: throw IOException("Missing followed categories")
            return (0 until nodes.length()).mapNotNull { i ->
                nodes.optJSONObject(i)?.let { Category(it.optString("id"), it.optString("displayName"), it.optString("boxArtURL"), it.optInt("viewersCount")) }
            }.filter { it.id.isNotEmpty() && (it.viewers ?: 0) > 0 }.distinctBy { it.id }.sortedByDescending { it.viewers }
        }
    }
}
