package com.purpletv.twitch

import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.InMemoryTokenStore
import com.purpletv.auth.signIn
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class FollowingApiTest {
    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore()
    private lateinit var gql: TwitchGraphQl

    @Before fun setup() {
        server.start()
        tokens.signIn("old", "r")
        val auth = DeviceAuth(OkHttpClient(), "client", "user:read:follows", tokens, server.url("/oauth2/").toString())
        gql = TwitchGraphQl(OkHttpClient(), auth, server.url("/gql").toString())
    }
    @After fun teardown() { server.shutdown() }

    @Test fun followedCategoriesAreLiveDeduplicatedAndMostWatchedFirst() {
        val data = JSONObject("""{"currentUser":{"followedGames":{"nodes":[
            {"id":"1","displayName":"One","viewersCount":5},
            {"id":"2","displayName":"Two","viewersCount":500},
            {"id":"3","displayName":"Offline","viewersCount":0},
            {"id":"1","displayName":"One","viewersCount":5}]}}}""")
        assertEquals(listOf("2", "1"), FollowingApi.parse(data).map { it.id })
    }

    @Test fun refreshesOnceAndSendsTheReplacementToken() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"access_token":"new","refresh_token":"r2"}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"currentUser":{"followedGames":{"nodes":[]}}}}"""))
        assertTrue(FollowingApi(gql).liveCategories().isEmpty())
        assertEquals("OAuth old", server.takeRequest().getHeader("Authorization"))
        assertEquals("/oauth2/token", server.takeRequest().requestUrl!!.encodedPath)
        val retry = server.takeRequest()
        assertEquals("OAuth new", retry.getHeader("Authorization"))
        val body = JSONObject(retry.body.readUtf8())
        assertTrue(body.getString("query").contains("type:LIVE"))
        assertTrue(body.getString("query").contains("first:100,"))
        assertEquals("client", retry.getHeader("Client-ID"))
    }

    @Test fun rejectedRefreshRequiresLogin() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(400).setBody("{}"))
        try { gql.query("Test", "query Test { currentUser { id } }"); fail("Expected login") }
        catch (_: NotLoggedInException) { assertEquals(2, server.requestCount) }
    }

    @Test fun errorsAreNotReportedAsAnEmptyFollowingList() = runTest {
        server.enqueue(MockResponse().setBody("""{"errors":[{"message":"Unknown field"}],"data":{"currentUser":null}}"""))
        try { FollowingApi(gql).liveCategories(); fail("Expected IOException") } catch (_: IOException) { }
    }

    @Test(expected = NotLoggedInException::class) fun missingCurrentUserRequiresLogin() {
        FollowingApi.parse(JSONObject("""{"currentUser":null}"""))
    }
}
