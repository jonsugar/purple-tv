package com.purpletv.twitch

import com.purpletv.auth.DeviceAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
    suspend fun mutate(name: String, mutation: String, variables: JSONObject): JSONObject = query(name, mutation, variables)

    suspend fun query(name: String, query: String, variables: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val token = auth.accessToken ?: throw NotLoggedInException()
        var reply = ask(name, query, variables, token)
        if (reply == null) {
            if (!auth.refresh(failed = token)) throw NotLoggedInException()
            reply = ask(name, query, variables, auth.accessToken ?: throw NotLoggedInException())
        }
        reply ?: throw NotLoggedInException()
    }

    private fun ask(name: String, query: String, variables: JSONObject, token: String): JSONObject? {
        val body = JSONObject().put("operationName", name).put("query", query).put("variables", variables)
        val request = Request.Builder().url(url).header("Client-ID", auth.clientId)
            .header("Authorization", "OAuth $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 401) return null
            if (!response.isSuccessful) throw IOException("Twitch answered HTTP ${response.code}")
            try {
                val root = JSONObject(response.body?.string().orEmpty())
                if (root.optJSONArray("errors")?.length()?.let { it > 0 } == true) throw IOException("Twitch could not load this content")
                root.optJSONObject("data") ?: throw IOException("Missing Twitch data")
            } catch (e: JSONException) {
                throw IOException("Unexpected Twitch response", e)
            }
        }
    }
}

class FollowingApi(private val gql: TwitchGraphQl) {
    suspend fun liveCategories(): List<Category> = parse(gql.query("FollowingCategories", QUERY))

    companion object {
        // This connection has nodes and no cursor; the server enforces a maximum of 100.
        private const val QUERY = "query FollowingCategories { currentUser { followedGames(first:100,type:LIVE) { " +
            "nodes { id displayName boxArtURL viewersCount } } } }"

        fun parse(data: JSONObject): List<Category> {
            val user = data.optJSONObject("currentUser") ?: throw NotLoggedInException()
            val nodes = user.optJSONObject("followedGames")?.optJSONArray("nodes") ?: throw IOException("Missing followed categories")
            return (0 until nodes.length()).mapNotNull { i ->
                nodes.optJSONObject(i)?.let { Category(it.optString("id"), it.optString("displayName"), it.optString("boxArtURL"), it.optInt("viewersCount")) }
            }.filter { it.id.isNotEmpty() && (it.viewers ?: 0) > 0 }.distinctBy { it.id }.sortedByDescending { it.viewers }
        }
    }
}
