package com.purpletv.player

import android.util.LruCache
import com.purpletv.auth.SessionId
import com.purpletv.auth.TokenStore
import java.util.UUID

/** Single-use handoffs keep potentially long, paged rows out of the Intent. */
class PlayerLaunchRows(private val tokens: TokenStore) {
    internal data class Launch(val session: SessionId, val row: ZapList)

    private val pending = LruCache<String, Launch>(8)

    fun put(session: SessionId, row: ZapList): String {
        val key = UUID.randomUUID().toString()
        tokens.runIfCurrent(session) { pending.put(key, Launch(session, row)) }
        return key
    }

    internal fun take(key: String?): Launch? =
        key?.let(pending::remove)?.takeIf { it.session === tokens.session.id }

    internal fun clearPreviousSession() {
        val session = tokens.session.id
        tokens.runIfCurrent(session) {
            pending.snapshot().forEach { (key, launch) ->
                if (launch.session !== session) pending.remove(key)
            }
        }
    }
}
