package com.purpletv.net

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException

internal class TwitchResponseException : IOException("Unexpected Twitch response")

internal fun decodeTwitchObject(body: String): JSONObject {
    try {
        val json = JSONTokener(body)
        val root = json.nextValue() as? JSONObject ?: throw TwitchResponseException()
        if (json.nextClean() != '\u0000') throw TwitchResponseException()
        return root
    } catch (_: JSONException) {
        // Parser diagnostics can contain the original response, including credentials.
        throw TwitchResponseException()
    }
}

internal fun JSONObject.requireResponseString(key: String): String =
    (opt(key) as? String)?.takeIf { it.isNotBlank() } ?: throw TwitchResponseException()
