package com.purpletv.twitch

import org.json.JSONArray
import org.json.JSONObject

/** Helix responses to models. Missing or null fields become "" or 0, never an exception. */
object HelixParser {

    fun streams(json: String): Page<Stream> = page(json) {
        Stream(
            userId = it.str("user_id"),
            login = it.str("user_login"),
            displayName = it.str("user_name"),
            title = it.str("title"),
            gameId = it.str("game_id"),
            gameName = it.str("game_name"),
            viewers = it.optInt("viewer_count"),
            thumbnailTemplate = it.str("thumbnail_url"),
            startedAt = it.str("started_at"),
        )
    }

    /** `games/top` and `search/categories` share this shape. */
    fun categories(json: String): Page<Category> = page(json) {
        Category(id = it.str("id"), name = it.str("name"), boxArtTemplate = it.str("box_art_url"))
    }

    fun users(json: String): List<User> = page(json) {
        User(
            id = it.str("id"),
            login = it.str("login"),
            displayName = it.str("display_name"),
            profileImageUrl = it.str("profile_image_url"),
        )
    }.items

    fun channels(json: String): List<ChannelResult> = page(json) {
        ChannelResult(
            login = it.str("broadcaster_login"),
            displayName = it.str("display_name"),
            isLive = it.optBoolean("is_live"),
            gameName = it.str("game_name"),
            title = it.str("title"),
            profileImageUrl = it.str("thumbnail_url"),
        )
    }.items

    private fun <T> page(json: String, item: (JSONObject) -> T): Page<T> {
        val root = JSONObject(json)
        val data = root.optJSONArray("data") ?: JSONArray()
        val items = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(item) }
        val cursor = root.optJSONObject("pagination")?.str("cursor")?.ifEmpty { null }
        return Page(items, cursor)
    }

    /** org.json turns a JSON null into the string "null"; this keeps it empty. */
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)
}
