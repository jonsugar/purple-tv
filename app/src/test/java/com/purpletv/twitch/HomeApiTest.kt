package com.purpletv.twitch

import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.InMemoryTokenStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class HomeApiTest {
    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore()
    private lateinit var api: HomeApi

    @Before
    fun setUp() {
        server.start()
        tokens.accessToken = "a"
        tokens.refreshToken = "r1"
        val auth = DeviceAuth(OkHttpClient(), TV_CLIENT_ID, "user_read user:read:follows", tokens, server.url("/oauth2/").toString())
        api = HomeApi(OkHttpClient(), auth, server.url("/gql").toString(), language = "it-IT", deviceId = "d3v1c3")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun stream(id: String, login: String) = """
        {"__typename":"Stream","id":"s$id","previewImageURL":"https://static-cdn.jtvnw.net/previews-ttv/live_user_$login-{width}x{height}.jpg",
         "viewersCount":1234,"type":"live",
         "broadcaster":{"id":"$id","login":"$login","displayName":"${login.uppercase()}",
           "profileImageURL":"https://static-cdn.jtvnw.net/jtv_user_pictures/$login-50x50.png",
           "broadcastSettings":{"id":"$id","title":"Titolo di $login"}},
         "game":{"id":"g1","name":"just chatting","displayName":"Just Chatting","boxArtURL":"x"}}"""

    private val game = """
        {"__typename":"Game","id":"509658","name":"just chatting","displayName":"Just Chatting","viewersCount":300000,
         "boxArtURL":"https://static-cdn.jtvnw.net/ttv-boxart/509658-{width}x{height}.jpg","streams":{"edges":[]}}"""

    private fun shelf(rowName: String, fallback: String, tokens: String, vararg nodes: String) = """
        {"node":{"id":"$rowName-id","title":{"fallbackLocalizedTitle":"$fallback","localizedTitleTokens":[$tokens]},
          "trackingInfo":{"rowName":"$rowName"},
          "content":{"edges":[${nodes.joinToString(",") { """{"trackingID":"t","node":$it}""" }}]}}}"""

    private fun answer(vararg shelves: String) =
        MockResponse().setBody("""{"data":{"shelves":{"edges":[${shelves.joinToString(",")}]}}}""")

    @Test
    fun asksLikeTheOfficialTvApp() = runTest {
        server.enqueue(answer())

        api.shelves()

        val request = server.takeRequest()
        assertEquals(TV_CLIENT_ID, request.getHeader("Client-ID"))
        assertEquals("OAuth a", request.getHeader("Authorization"))
        assertEquals("it-IT", request.getHeader("Accept-Language"))
        assertEquals("d3v1c3", request.getHeader("Device-Id"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("Homepage_Query", body.getString("operationName"))
        assertTrue(body.getString("query").startsWith("query Homepage_Query("))
        val variables = body.getJSONObject("variables")
        assertEquals(25, variables.getInt("itemsPerRow"))
        assertEquals("androidtv_web_tv", variables.getString("platform"))
        assertTrue(variables.getString("requestID").matches(Regex("[A-Za-z0-9]{16}")))
    }

    @Test
    fun readsStreamAndCategoryShelvesInOrder() = runTest {
        server.enqueue(answer(
            shelf("RecommendedStreams", "Canali live che potrebbero piacerti", "", stream("1", "pgl"), stream("2", "yucali")),
            shelf("RecommendedCategories", "Categorie che potrebbero piacerti", "", game),
        ))

        val shelves = api.shelves()

        assertEquals(listOf("Canali live che potrebbero piacerti", "Categorie che potrebbero piacerti"), shelves.map { it.title })
        assertEquals("RecommendedStreams", shelves[0].rowName)
        assertEquals(
            Stream("1", "pgl", "PGL", "Titolo di pgl", "g1", "Just Chatting", 1234,
                "https://static-cdn.jtvnw.net/previews-ttv/live_user_pgl-{width}x{height}.jpg"),
            shelves[0].streams[0],
        )
        assertEquals("yucali", shelves[0].streams[1].login)
        assertEquals("https://static-cdn.jtvnw.net/jtv_user_pictures/pgl-50x50.png", shelves[0].avatars["1"])
        assertEquals(
            listOf(Category("509658", "Just Chatting", "https://static-cdn.jtvnw.net/ttv-boxart/509658-{width}x{height}.jpg")),
            shelves[1].categories,
        )
    }

    @Test
    fun titlesARowFromItsPiecesLikeTheOfficialApp() = runTest {
        // As Twitch sends it: the text pieces carry their own spaces.
        val pieces = """{"node":{"__typename":"TextToken","text":"Canali consigliati su ","location":"NO_LINK"}},
            {"node":{"__typename":"Game","displayName":"League of Legends","name":"League of Legends","id":"21779"}}"""
        server.enqueue(answer(shelf("RecommendedBecauseYouWatchX", "Canali live consigliati", pieces, stream("1", "pgl"))))

        assertEquals("Canali consigliati su League of Legends", api.shelves().single().title)
    }

    @Test
    fun keepsTheFallbackTitleForAPieceWithoutText() = runTest {
        val collection = """{"node":{"__typename":"BrowsableCollection","id":"collection-online-shooters-fps-and-battle-royale-games"}}"""
        server.enqueue(answer(shelf("online-shooters-fps-and-battle-royale-games", "Team FPS", collection, stream("1", "pgl"))))

        assertEquals("Team FPS", api.shelves().single().title)
    }

    @Test
    fun leavesOutShelvesWithNothingItCanShow() = runTest {
        server.enqueue(answer(
            shelf("Clips", "Clip", "", """{"__typename":"Clip","id":"c"}"""),
            shelf("RecommendedStreams", "Canali", "", """{"__typename":"Video","id":"v"}""", stream("1", "pgl")),
        ))

        val shelves = api.shelves()

        assertEquals(listOf("RecommendedStreams"), shelves.map { it.rowName })
        assertEquals(1, shelves.single().streams.size)
    }

    @Test
    fun refreshesTheLoginOnceOn401() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"access_token":"b","refresh_token":"r2"}"""))
        server.enqueue(answer())

        api.shelves()

        assertEquals("OAuth a", server.takeRequest().getHeader("Authorization"))
        assertEquals("/oauth2/token", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("OAuth b", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun anAnswerWithoutShelvesIsAnIOException() = runTest {
        for (body in listOf("""{"errors":[{"message":"service timeout"}]}""", "<html>Bad gateway</html>")) {
            server.enqueue(MockResponse().setBody(body))
            try {
                api.shelves()
                fail("expected IOException for $body")
            } catch (e: IOException) {
                // Home falls back to its Helix rows.
            }
        }
    }

    @Test
    fun saysWhatTwitchObjected() = runTest {
        server.enqueue(MockResponse().setBody("""{"errors":[{"message":"service timeout","path":["shelves"]}],"data":null}"""))
        try {
            api.shelves()
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("service timeout"))
        }
    }

    private companion object {
        const val TV_CLIENT_ID = "ue6666qo983tsx6so1t0vnawi233wa"
    }
}
