package com.purpletv.twitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HelixParserTest {

    @Test
    fun parsesStreams() {
        val json = """{"data":[
            {"id":"s1","user_id":"1","user_login":"pgl","user_name":"PGL","game_id":"32399","game_name":"Counter-Strike",
             "type":"live","title":"Major","viewer_count":21463,"thumbnail_url":"https://x/pgl-{width}x{height}.jpg"},
            {"id":"s2","user_id":"2","user_login":"giulia","user_name":"Giulia","game_id":"509658","game_name":"Just Chatting",
             "type":"live","title":"Ciao","viewer_count":950,"thumbnail_url":"https://x/giulia-{width}x{height}.jpg"}
            ],"pagination":{"cursor":"c1"}}"""

        val page = HelixParser.streams(json)

        assertEquals(listOf(
            Stream("1", "pgl", "PGL", "Major", "32399", "Counter-Strike", 21463, "https://x/pgl-{width}x{height}.jpg"),
            Stream("2", "giulia", "Giulia", "Ciao", "509658", "Just Chatting", 950, "https://x/giulia-{width}x{height}.jpg"),
        ), page.items)
        assertEquals("c1", page.cursor)
    }

    @Test
    fun toleratesMissingFields() {
        val json = """{"data":[{"user_id":"3","user_login":"quiet","user_name":"Quiet","game_id":"",
            "game_name":null,"title":"","viewer_count":0,"thumbnail_url":""}]}"""

        val page = HelixParser.streams(json)

        val stream = page.items.single()
        assertEquals("", stream.gameName)
        assertEquals("", stream.title)
        assertEquals("", stream.thumbnailTemplate)
        assertEquals(0, stream.viewers)
        assertNull(page.cursor)
    }

    @Test
    fun parsesCategoriesUsersChannels() {
        val categories = HelixParser.categories(
            """{"data":[{"id":"509658","name":"Just Chatting","box_art_url":"https://x/509658-{width}x{height}.jpg"}],"pagination":{}}""")
        val users = HelixParser.users(
            """{"data":[{"id":"1","login":"pgl","display_name":"PGL","profile_image_url":"https://x/pgl.png"}]}""")
        val channels = HelixParser.channels(
            """{"data":[{"broadcaster_login":"pgl","display_name":"PGL","is_live":true,"game_name":"Counter-Strike",
                "title":"Major","thumbnail_url":"https://x/pgl.png"}]}""")

        assertEquals(Page(listOf(Category("509658", "Just Chatting", "https://x/509658-{width}x{height}.jpg")), null), categories)
        assertEquals(listOf(User("1", "pgl", "PGL", "https://x/pgl.png")), users)
        assertEquals(listOf(ChannelResult("pgl", "PGL", true, "Counter-Strike", "Major", "https://x/pgl.png")), channels)
    }
}
