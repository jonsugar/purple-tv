package com.purpletv.home

import com.purpletv.auth.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {
    private val prefs = FakePrefs()
    private val recent = RecentSearches(prefs)

    @Test
    fun newestFirstWithoutDuplicates() {
        recent.add("pgl")
        recent.add("yotobi")
        recent.add("PGL")
        assertEquals(listOf("PGL", "yotobi"), recent.list())
    }

    @Test
    fun keepsTheLastEight() {
        (1..10).forEach { recent.add("canale$it") }
        assertEquals(8, recent.list().size)
        assertEquals("canale10", recent.list().first())
    }

    @Test
    fun ignoresBlankAndOneLetterQueries() {
        recent.add("  ")
        recent.add("a")
        assertEquals(emptyList<String>(), recent.list())
    }

    @Test
    fun survivesANewInstance() {
        recent.add("poly")
        assertEquals(listOf("poly"), RecentSearches(prefs).list())
    }
}
