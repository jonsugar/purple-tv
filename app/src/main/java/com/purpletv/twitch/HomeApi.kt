package com.purpletv.twitch

import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.SessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.random.Random

/** A Home row as Twitch's TV app gets it: localized title, Twitch's name for the row, and its cards. */
data class Shelf(
    val title: String,
    val rowName: String,
    val streams: List<Stream>,
    val categories: List<Category>,
    /** Broadcaster id to avatar URL, for the stream cards. */
    val avatars: Map<String, String>,
)

/**
 * The rows of Twitch's own TV Home, personalised for the user. Like [PlaybackApi] this asks the
 * undocumented GraphQL API the way the official Android TV app does (its Homepage_Query, platform
 * androidtv_web_tv, 25 cards a row), with the user's login.
 */
class HomeApi(
    private val http: OkHttpClient,
    private val auth: DeviceAuth,
    private val gqlUrl: String = "https://gql.twitch.tv/gql",
    private val language: String = Locale.getDefault().toLanguageTag(),
    private val deviceId: String = "",
) {
    suspend fun shelves(session: SessionId = auth.sessionId): List<Shelf> = auth.sessionRequest(session) {
        withContext(Dispatchers.IO) {
            val credentials = auth.credentials(session)
            val text = ask(session, credentials.requireAccessToken()) ?: run {
                val renewed = auth.refresh(credentials) ?: throw IOException("Home rows refused")
                ask(session, renewed) ?: throw IOException("Home rows refused")
            }
            try {
                parse(text)
            } catch (e: JSONException) {
                throw IOException("unexpected Home rows answer", e)
            }
        }
    }

    /** The answer, or null on a 401. */
    private suspend fun ask(session: SessionId, oauth: String): String? {
        val body = JSONObject()
            .put("operationName", "Homepage_Query")
            .put("query", QUERY)
            .put("variables", JSONObject()
                .put("itemsPerRow", ITEMS_PER_ROW)
                .put("platform", PLATFORM)
                .put("requestID", requestId()))
        val request = Request.Builder()
            .url(gqlUrl)
            .header("Client-ID", auth.clientId)
            .header("Authorization", "OAuth $oauth")
            .header("Accept-Language", language)
            .apply { if (deviceId.isNotEmpty()) header("Device-Id", deviceId) }
            .post(body.toString().toRequestBody(JSON))
            .build()
        return auth.execute(session, http.newCall(request)) { response ->
            if (response.code == 401) return@execute null
            if (!response.isSuccessful) throw IOException("GQL answered HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private fun parse(text: String): List<Shelf> {
        val root = JSONObject(text)
        val edges = root.optJSONObject("data")?.optJSONObject("shelves")?.optJSONArray("edges")
            ?: throw IOException("no Home rows in the answer: " +
                objects(root.optJSONArray("errors")).joinToString("; ") { it.optString("message") })
        return objects(edges).mapNotNull { it.optJSONObject("node")?.let(::shelf) }
    }

    private fun shelf(node: JSONObject): Shelf? {
        val streams = mutableListOf<Stream>()
        val categories = mutableListOf<Category>()
        val avatars = mutableMapOf<String, String>()
        for (edge in objects(node.optJSONObject("content")?.optJSONArray("edges"))) {
            val item = edge.optJSONObject("node") ?: continue
            when (item.optString("__typename")) {
                "Stream" -> {
                    val broadcaster = item.optJSONObject("broadcaster") ?: continue
                    val game = item.optJSONObject("game")
                    streams += Stream(
                        userId = broadcaster.str("id"),
                        login = broadcaster.str("login"),
                        displayName = broadcaster.str("displayName"),
                        title = broadcaster.optJSONObject("broadcastSettings")?.str("title").orEmpty(),
                        gameId = game?.str("id").orEmpty(),
                        gameName = game?.str("displayName").orEmpty(),
                        viewers = item.optInt("viewersCount"),
                        thumbnailTemplate = item.str("previewImageURL"),
                    )
                    broadcaster.str("profileImageURL").ifEmpty { null }?.let { avatars[broadcaster.str("id")] = it }
                }
                "Game" -> categories += Category(item.str("id"), item.str("displayName"), item.str("boxArtURL"))
            }
        }
        if (streams.isEmpty() && categories.isEmpty()) return null
        return Shelf(title(node.optJSONObject("title")), node.optJSONObject("trackingInfo")?.str("rowName").orEmpty(),
            streams, categories, avatars)
    }

    /**
     * The title as the official app shows it: its pieces joined ("Canali consigliati su " + League of
     * Legends; the text pieces carry their spaces), or Twitch's fallback when a piece has no text of its
     * own (a collection such as "Team FPS" arrives as an id only).
     */
    private fun title(title: JSONObject?): String {
        val fallback = title?.str("fallbackLocalizedTitle").orEmpty()
        val pieces = objects(title?.optJSONArray("localizedTitleTokens")).mapNotNull { it.optJSONObject("node") }
        val text = pieces.map {
            when (it.optString("__typename")) {
                "TextToken" -> it.str("text")
                "Game" -> it.str("displayName")
                else -> return fallback
            }
        }.joinToString("").replace(SPACES, " ").trim()
        return text.ifEmpty { fallback }
    }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

    private fun requestId(): String = (1..REQUEST_ID_LENGTH).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")

    private companion object {
        val JSON = "application/json".toMediaType()
        val SPACES = Regex("\\s+")
        const val ITEMS_PER_ROW = 25
        const val PLATFORM = "androidtv_web_tv"
        const val REQUEST_ID_LENGTH = 16
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        // The official TV app's query (tv.twitch.android.app 13, web app "celadon"), whitespace folded.
        const val QUERY =
            "query Homepage_Query(\$itemsPerRow:Int! \$platform:String! \$requestID:String!){" +
            "shelves(itemsPerRow:\$itemsPerRow,platform:\$platform,requestID:\$requestID){edges{node{id title{" +
            "fallbackLocalizedTitle localizedTitleTokens{node{__typename ... on Game{__typename displayName name " +
            "id}... on TextToken{__typename text location}... on BrowsableCollection{id}... on Tag{id}... on " +
            "User{id}}}}trackingInfo{rowName}content{edges{trackingID node{__typename " +
            "__isShelfContent:__typename ... on Stream{id previewImageURL broadcaster{id displayName " +
            "broadcastSettings{title id}}height width isDualFormat " +
            "verticalPreviewImageURL:previewImageURL(height:640,width:360)game{displayName boxArtURL id}" +
            "...FocusableStreamCard_stream}... on Game{...FocusableCategoryCard_category id streams(first:1){" +
            "edges{node{id previewImageURL broadcaster{id displayName broadcastSettings{title id}}height width " +
            "isDualFormat verticalPreviewImageURL:previewImageURL(height:640,width:360)game{displayName " +
            "boxArtURL id}}}}}... on Clip{id}... on StoryPreview{id}... on Tag{id}... on User{id}... on Video{id}" +
            "}}}}}}}fragment FocusableCategoryCard_category on Game{id name displayName viewersCount boxArtURL}" +
            "fragment FocusableStreamCard_stream on Stream{broadcaster{displayName login broadcastSettings{title " +
            "id}profileImageURL(width:50)id}game{displayName name id}height id isDualFormat previewImageURL type " +
            "verticalPreviewImageURL:previewImageURL(height:640,width:360)viewersCount width}"
    }
}
