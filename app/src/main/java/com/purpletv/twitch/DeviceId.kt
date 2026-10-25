package com.purpletv.twitch

import android.content.SharedPreferences
import kotlin.random.Random

private const val DEVICE_ID = "device_id"
private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

/**
 * This install's id for Twitch, made once and kept: the official TV app sends one as Device-Id with
 * every GraphQL request, and Twitch's Home rows are tied to it. It identifies nothing but the install.
 */
fun deviceId(prefs: SharedPreferences): String = prefs.getString(DEVICE_ID, null)
    ?: (1..32).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")
        .also { prefs.edit().putString(DEVICE_ID, it).apply() }
