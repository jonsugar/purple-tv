package com.purpletv.home

import android.content.SharedPreferences

/**
 * The searches that led somewhere (a result was opened), newest first. Kept as one string, one
 * query per line, in the "search" preferences file.
 */
class RecentSearches(private val prefs: SharedPreferences) {
    fun add(query: String) {
        val q = query.trim()
        if (q.length < MIN_LENGTH) return
        val kept = list().filterNot { it.equals(q, ignoreCase = true) }
        prefs.edit().putString(KEY, (listOf(q) + kept).take(MAX).joinToString("\n")).apply()
    }

    fun list(): List<String> = prefs.getString(KEY, null).orEmpty().split('\n').filter { it.isNotEmpty() }

    private companion object {
        const val KEY = "recent"
        const val MAX = 8
        const val MIN_LENGTH = 2
    }
}
