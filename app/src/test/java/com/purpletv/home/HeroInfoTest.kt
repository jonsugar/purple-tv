package com.purpletv.home

import com.purpletv.twitch.Category
import com.purpletv.twitch.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroInfoTest {
    private val now = java.time.Instant.parse("2026-10-25T16:10:36Z").toEpochMilli()
    private val yotobi = Stream("1", "yotobi", "Yotobi", "La maratona di HORRORMANIA !bingo", "g", "Games + Demos", 3152,
        "https://x/live_user_yotobi-{width}x{height}.jpg", "2026-10-25T13:00:00Z")

    @Test
    fun streamHeroShowsUptimeAndTheLargeThumbnail() {
        val hero = heroFor(yotobi, "a.png", now)
        assertEquals("3:10:36", hero.uptime)
        assertEquals("https://x/live_user_yotobi-1280x720.jpg", hero.backdrop)
        assertEquals(3152, hero.viewers)
        assertTrue(hero.live)
    }

    @Test
    fun streamHeroWithoutAStartTimeHasNoUptime() {
        assertEquals("", heroFor(yotobi.copy(startedAt = ""), null, now).uptime)
    }

    @Test
    fun categoryHeroUsesTheBoxArt() {
        val hero = heroFor(Category("509658", "Just Chatting", "https://b/509658-{width}x{height}.jpg"))
        assertEquals("Just Chatting", hero.title)
        assertEquals("https://b/509658-600x800.jpg", hero.backdrop)
        assertNull(hero.viewers)
        assertFalse(hero.live)
    }
}
