package com.purpletv.home

import android.content.SharedPreferences
import com.purpletv.twitch.FollowKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class FavouritePin(val id: String, val name: String)

/** Local to this TV, with an independent order for each account and item type. */
class PinStore(private val prefs: SharedPreferences) {
    private val updates = MutableStateFlow(0L)
    val revisions = updates.asStateFlow()

    fun list(account: String?, kind: FollowKind): List<FavouritePin> {
        if (account.isNullOrBlank()) return emptyList()
        val entries = JSONArray(prefs.getString(key(account, kind), "[]"))
        return (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            FavouritePin(entry.getString("id"), entry.getString("name"))
        }
    }

    fun add(account: String, kind: FollowKind, pin: FavouritePin) = edit(account, kind) { pins ->
        if (pins.none { it.id == pin.id }) pins.add(pin)
    }

    fun remove(account: String?, kind: FollowKind, id: String) = edit(account, kind) { pins ->
        pins.removeAll { it.id == id }
    }

    fun move(account: String, kind: FollowKind, id: String, direction: Int) = edit(account, kind) { pins ->
        pins.movePin(id, direction)
    }

    @Synchronized
    private fun edit(account: String?, kind: FollowKind, change: (MutableList<FavouritePin>) -> Unit) {
        if (account.isNullOrBlank()) return
        val before = list(account, kind)
        val after = before.toMutableList().apply(change)
        if (before == after) return
        val entries = JSONArray()
        after.forEach { entries.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs.edit().putString(key(account, kind), entries.toString()).apply()
        updates.value++
    }

    private fun key(account: String, kind: FollowKind) = "${kind.name}:$account"
}

internal fun MutableList<FavouritePin>.movePin(id: String, offset: Int) {
    val index = indexOfFirst { it.id == id }
    val destination = index + offset
    if (index >= 0 && destination in indices && destination != index) add(destination, removeAt(index))
}

/** Project the saved order onto eligible items. Keep the original order of everything else. */
internal fun <T> splitByPins(
    items: List<T>, pins: List<FavouritePin>, idOf: (T) -> String,
): Pair<List<T>, List<T>> {
    val remaining = items.distinctBy(idOf).associateByTo(linkedMapOf(), idOf)
    val pinned = pins.mapNotNull { remaining.remove(it.id) }
    return pinned to remaining.values.toList()
}
