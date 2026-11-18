package com.purpletv.home

import android.content.SharedPreferences
import com.purpletv.twitch.FollowedChannel
import com.purpletv.twitch.Stream
import com.purpletv.twitch.StreamLanguage
import com.purpletv.twitch.sortedChannels
import org.json.JSONArray
import org.json.JSONObject

/** A channel ID and enough information to open its page before its profile loads. */
data class GhostFollow(val id: String, val login: String, val name: String) {
    fun channel() = FollowedChannel(id, login, name, followedAt = "")
}

class GhostFollowStore(private val prefs: SharedPreferences) {
    var revision = 0L
        private set

    fun list(account: String?): List<GhostFollow> {
        if (account.isNullOrBlank()) return emptyList()
        val entries = JSONArray(prefs.getString(account, "[]"))
        return (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            GhostFollow(entry.getString("id"), entry.getString("login"), entry.getString("name"))
        }
    }

    fun ids(account: String?): List<String> = list(account).map(GhostFollow::id)

    /** Toggle one local entry. Other accounts, Twitch follows and pins are independent. */
    fun toggle(account: String, channel: GhostFollow): Boolean {
        require(account.isNotBlank() && channel.id.isNotBlank() && channel.login.isNotBlank())
        val entries = list(account).toMutableList()
        val added = !entries.removeAll { it.id == channel.id }
        if (added) entries.add(channel)
        val json = JSONArray()
        entries.forEach { json.put(JSONObject().put("id", it.id).put("login", it.login).put("name", it.name)) }
        prefs.edit().putString(account, json.toString()).apply()
        revision++
        return added
    }
}

internal fun ghostOnlyStreams(
    streams: List<Stream>, ghostIds: Collection<String>, followed: List<Stream>, language: StreamLanguage,
): List<Stream> {
    val eligible = ghostIds.toHashSet() - followed.map(Stream::userId).toSet()
    return language.filter(streams)
        .filter { it.userId in eligible }
        .distinctBy(Stream::userId)
        .sortedByDescending(Stream::viewers)
}

internal fun ghostOnlyChannels(
    channels: List<FollowedChannel>, followed: List<FollowedChannel>, streams: List<Stream>,
): List<FollowedChannel> {
    val followedIds = followed.mapTo(hashSetOf(), FollowedChannel::id)
    val liveById = streams.associateBy(Stream::userId)
    return sortedChannels(channels.filterNot { it.id in followedIds }.map { it.copy(live = liveById[it.id]) })
}
