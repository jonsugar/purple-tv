package com.purpletv.twitch

import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.InMemoryTokenStore
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

class FollowApiTest {
    private val server = MockWebServer()
    private val changes = FollowChanges()
    private lateinit var api: FollowApi

    @Before fun setup() {
        server.start()
        val http = OkHttpClient()
        val tokens = InMemoryTokenStore().apply { accessToken = "old"; refreshToken = "refresh" }
        val auth = DeviceAuth(http, "tv-client", "user_read user:read:follows", tokens, server.url("/oauth2/").toString())
        api = FollowApi(TwitchGraphQl(http, auth, server.url("/gql").toString()), changes)
    }

    @After fun teardown() { server.shutdown() }

    @Test fun readsExistingAndMissingChannelFollowWithoutChangingAnything() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"user":{"self":{"follower":{"followedAt":"2026-11-01T00:00:00Z"}}}}}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"user":{"self":{"follower":null}}}}"""))
        assertTrue(api.isFollowing(FollowKind.CHANNEL, "42"))
        assertFalse(api.isFollowing(FollowKind.CHANNEL, "43"))
        val query = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("42", query.getJSONObject("variables").getString("id"))
        assertEquals(0L, changes.revision)
    }

    @Test fun categoryStatusIncludesOfflineFollows() = runTest {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"data":{"currentUser":{"followedGames":{"nodes":[{"id":"deadlock"}]}}}}""")) }
        assertTrue(api.isFollowing(FollowKind.CATEGORY, "deadlock"))
        assertFalse(api.isFollowing(FollowKind.CATEGORY, "other"))
        assertTrue(JSONObject(server.takeRequest().body.readUtf8()).getString("query").contains("type:ALL"))
    }

    @Test fun channelFollowWritesTheChosenTargetAndInvalidatesListsAfterConfirmation() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"followUser":{"follow":{"followedAt":"2026-11-17T00:00:00Z"}}}}"""))
        api.follow(FollowKind.CHANNEL, "42")
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals("OAuth old", request.getHeader("Authorization"))
        assertEquals("tv-client", request.getHeader("Client-ID"))
        assertEquals("FollowChannel", body.getString("operationName"))
        assertEquals("42", body.getJSONObject("variables").getJSONObject("input").getString("targetID"))
        assertEquals(1L, changes.revision)
    }

    @Test fun categoryFollowWritesTheChosenTargetAndInvalidatesListsAfterConfirmation() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"followGame":{"game":{"id":"deadlock"}}}}"""))
        api.follow(FollowKind.CATEGORY, "deadlock")
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("FollowCategory", body.getString("operationName"))
        assertEquals("deadlock", body.getJSONObject("variables").getJSONObject("input").getString("gameID"))
        assertEquals(1L, changes.revision)
    }

    @Test fun failedMutationDoesNotReportASuccessfulFollow() = runTest {
        server.enqueue(MockResponse().setBody("""{"errors":[{"message":"Follow refused"}]}"""))
        try { api.follow(FollowKind.CHANNEL, "42"); fail("Expected follow failure") } catch (_: IOException) { }
        assertEquals(0L, changes.revision)
    }

    @Test fun missingConfirmationAndWrongCategoryDoNotInvalidateLists() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"followUser":{"follow":null}}}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"followGame":{"game":{"id":"wrong"}}}}"""))
        try { api.follow(FollowKind.CHANNEL, "42"); fail("Expected failure") } catch (_: IOException) { }
        try { api.follow(FollowKind.CATEGORY, "deadlock"); fail("Expected failure") } catch (_: IOException) { }
        assertEquals(0L, changes.revision)
    }

    @Test fun expiredTokenRefreshesOnceBeforeRepeatingTheSameFollow() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"access_token":"new","refresh_token":"new-refresh"}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"followGame":{"game":{"id":"deadlock"}}}}"""))
        api.follow(FollowKind.CATEGORY, "deadlock")
        val first = server.takeRequest()
        assertEquals("/oauth2/token", server.takeRequest().requestUrl!!.encodedPath)
        val retry = server.takeRequest()
        assertEquals(first.body.readUtf8(), retry.body.readUtf8())
        assertEquals("OAuth new", retry.getHeader("Authorization"))
        assertEquals(1L, changes.revision)
    }

    @Test fun anonymousStatusIsNotMistakenForNotFollowing() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"user":{"self":null}}}"""))
        try { api.isFollowing(FollowKind.CHANNEL, "42"); fail("Expected sign-in") } catch (_: NotLoggedInException) { }
    }
}
