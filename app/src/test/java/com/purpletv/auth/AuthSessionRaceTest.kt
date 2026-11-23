package com.purpletv.auth

import com.purpletv.twitch.FollowApi
import com.purpletv.twitch.FollowChanges
import com.purpletv.twitch.FollowKind
import com.purpletv.twitch.FollowStatus
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.HomeApi
import com.purpletv.twitch.NotLoggedInException
import com.purpletv.twitch.PlaybackApi
import com.purpletv.twitch.TwitchGraphQl
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AuthSessionRaceTest {
    private val prefs = FakePrefs()
    private val tokens = PrefsTokenStore(prefs).apply { signIn("access-a", "refresh-a", "42") }
    private val http = OkHttpClient()
    private val responses = ConcurrentLinkedQueue<() -> MockResponse>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                responses.poll()?.invoke() ?: response(500)
        }
        start()
    }
    private val auth = DeviceAuth(http, "tv", "", tokens, server.url("/oauth2/").toString())
    private val helix = HelixClient(http, tokens, auth, server.url("/helix/").toString())
    private val gql = TwitchGraphQl(http, auth, server.url("/gql").toString())
    private val changes = FollowChanges()
    private val unpinned = mutableListOf<String>()
    private val follows = FollowApi(gql, changes) { _, _, id -> unpinned += id }

    @After fun teardown() = server.shutdown()

    @Test fun refreshFinishingAfterSignOutLeavesTheStoredLoginCleared() = runTest {
        val held = hold(response(body = RENEWED_TOKENS))
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { auth.refresh(tokens.session) }
        assertEquals("/oauth2/token", nextRequest().path)
        tokens.clearLogin()
        held.release()

        refresh.assertSessionChanged()
        assertEquals(emptyMap<String, Any?>(), prefs.all)
        assertEquals(1, server.requestCount)
    }

    @Test fun refreshFinishingAfterAnotherSignInKeepsTheNewCredentials() = runTest {
        val held = hold(response(body = RENEWED_TOKENS))
        val previous = tokens.session
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { auth.refresh(previous) }
        assertEquals("/oauth2/token", nextRequest().path)
        held.awaitArrival()
        respond(response(body = DEVICE_CODE))
        respond(response(body = """{"access_token":"access-b","refresh_token":"refresh-b"}"""))
        auth.poll(auth.start())
        assertTrue(tokens.updateUserId(tokens.session.id, "43"))
        held.release()

        refresh.assertSessionChanged()
        assertNotEquals(previous.id, tokens.session.id)
        assertStoredLogin("access-b", "refresh-b", "43")
        assertEquals(3, server.requestCount)
    }

    @Test fun concurrent401sShareOneRefreshWhenTwitchKeepsTheSameAccessToken() = runTest {
        val firstReply = hold(response(401))
        val secondReply = hold(response(401))
        respond(response(body = """{"access_token":"access-a","refresh_token":"renewed-refresh"}"""))
        repeat(2) {
            respond(response(body = """{"data":[{"id":"42","login":"owner","display_name":"Owner"}]}"""))
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { helix.me() }
        assertEquals("Bearer access-a", nextRequest().getHeader("Authorization"))
        firstReply.awaitArrival()
        val second = async(start = CoroutineStart.UNDISPATCHED) { helix.me() }
        assertEquals("Bearer access-a", nextRequest().getHeader("Authorization"))
        secondReply.awaitArrival()
        firstReply.release()
        secondReply.release()

        assertEquals("42", first.await().id)
        assertEquals("42", second.await().id)
        assertEquals("/oauth2/token", nextRequest().path)
        repeat(2) { assertEquals("/helix/users", nextRequest().path) }
        assertEquals(5, server.requestCount)
        assertStoredLogin("access-a", "renewed-refresh", "42")
    }

    @Test fun lateHelix401DoesNotRefreshEvenAfterSigningInAsTheSameUserWithTheSameToken() = runTest {
        val held = hold(response(401))
        val profile = async(start = CoroutineStart.UNDISPATCHED) { helix.me() }
        assertEquals("Bearer access-a", nextRequest().getHeader("Authorization"))
        val previous = tokens.session.id
        tokens.signIn("access-a", "refresh-a", "42")
        held.release()

        profile.assertSessionChanged()
        assertNotEquals(previous, tokens.session.id)
        assertStoredLogin("access-a", "refresh-a", "42")
        assertEquals(1, server.requestCount)
    }

    @Test fun aFailedHelixRetryCannotClearTheReplacementLogin() = runTest {
        respond(response(401))
        respond(response(body = RENEWED_TOKENS))
        val held = hold(response(401))
        val profile = async(start = CoroutineStart.UNDISPATCHED) { helix.me() }
        assertEquals("Bearer access-a", nextRequest().getHeader("Authorization"))
        assertEquals("/oauth2/token", nextRequest().path)
        assertEquals("Bearer renewed", nextRequest().getHeader("Authorization"))
        tokens.signIn("access-b", "refresh-b", "43")
        held.release()

        profile.assertSessionChanged()
        assertStoredLogin("access-b", "refresh-b", "43")
        assertEquals(3, server.requestCount)
    }

    @Test fun aGraphQlAuthFailureHandledAfterSignInCannotClearTheReplacementLogin() = runTest {
        respond(response(401))
        respond(response(400))
        val failure = try {
            follows.status(FollowKind.CHANNEL, "target")
            error("Expected authentication failure")
        } catch (e: NotLoggedInException) {
            e
        }
        assertEquals(tokens.session.id, failure.session)
        tokens.signIn("access-b", "refresh-b", "43")

        assertFalse(failure.clearLogin(tokens))
        assertStoredLogin("access-b", "refresh-b", "43")
        assertEquals(2, server.requestCount)
    }

    @Test fun lateFollow401SendsNoRefreshOrRetry() = runTest {
        val held = hold(response(401))
        val follow = async(start = CoroutineStart.UNDISPATCHED) {
            follows.follow(FollowKind.CATEGORY, "target")
        }
        assertEquals("OAuth access-a", nextRequest().getHeader("Authorization"))
        tokens.signIn("access-b", "refresh-b", "43")
        held.release()

        follow.assertSessionChanged()
        assertEquals(0L, changes.revision)
        assertEquals(1, server.requestCount)
    }

    @Test fun lateFollowSuccessDoesNotInvalidateLists() = runTest {
        val held = hold(response(body = """{"data":{"followGame":{"game":{"id":"target"}}}}"""))
        val follow = async(start = CoroutineStart.UNDISPATCHED) {
            follows.follow(FollowKind.CATEGORY, "target")
        }
        assertEquals("OAuth access-a", nextRequest().getHeader("Authorization"))
        tokens.clearLogin()
        held.release()

        follow.assertSessionChanged()
        assertEquals(0L, changes.revision)
        assertEquals(1, server.requestCount)
    }

    @Test fun lateUnfollowSuccessSendsNoStatusCheckAndPublishesNoChange() = runTest {
        val held = hold(response(body = UNFOLLOWED))
        val unfollow = async(start = CoroutineStart.UNDISPATCHED) {
            follows.unfollow(FollowKind.CHANNEL, "target")
        }
        assertEquals("OAuth access-a", nextRequest().getHeader("Authorization"))
        tokens.signIn("access-b", "refresh-b", "42")
        held.release()

        unfollow.assertSessionChanged()
        assertTrue(unpinned.isEmpty())
        assertEquals(0L, changes.revision)
        assertEquals(1, server.requestCount)
    }

    @Test fun signOutDuringStatusCallConstructionPreventsDispatch() = runTest {
        val constructingStatusCall = CountDownLatch(1)
        val resumeConstruction = CountDownLatch(1)
        val calls = AtomicInteger()
        val pausedHttp = http.newBuilder().eventListenerFactory {
            if (calls.incrementAndGet() == 2) {
                constructingStatusCall.countDown()
                check(resumeConstruction.await(5, SECONDS)) { "Status call construction did not resume" }
            }
            EventListener.NONE
        }.build()
        val pausedGql = TwitchGraphQl(pausedHttp, auth, server.url("/gql").toString())
        val pausedFollows = FollowApi(pausedGql, changes) { _, _, id -> unpinned += id }
        respond(response(body = UNFOLLOWED))
        respond(response(body = """{"data":{"user":{"self":{"follower":null}}}}"""))

        val unfollow = async { pausedFollows.unfollow(FollowKind.CHANNEL, "target") }
        try {
            assertEquals("OAuth access-a", nextRequest().getHeader("Authorization"))
            withContext(Dispatchers.IO) { assertTrue(constructingStatusCall.await(5, SECONDS)) }
            tokens.clearLogin()
        } finally {
            resumeConstruction.countDown()
        }

        unfollow.assertSessionChanged()
        assertEquals(1, server.requestCount)
        assertTrue(unpinned.isEmpty())
        assertEquals(0L, changes.revision)
    }

    @Test fun replacementLoginCancelsAnOldCallWaitingInTheHttpQueue() = runTest {
        val callStarted = CountDownLatch(1)
        val submitted = AtomicReference<Call>()
        val limitedHttp = http.newBuilder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 1 })
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    if (call.request().url.encodedPath == "/gql") {
                        submitted.set(call)
                        callStarted.countDown()
                    }
                }
            })
            .build()
        val limitedGql = TwitchGraphQl(limitedHttp, auth, server.url("/gql").toString())
        val held = hold(response(body = "busy"))
        val occupyingCall = limitedHttp.newCall(Request.Builder().url(server.url("/occupy")).build())
        occupyingCall.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit
            override fun onResponse(call: Call, response: Response) { response.close() }
        })

        val oldQuery = async { limitedGql.query("Test", "{ ok }") }
        try {
            assertEquals("/occupy", nextRequest().path)
            withContext(Dispatchers.IO) { assertTrue(callStarted.await(5, SECONDS)) }
            tokens.signIn("access-b", "refresh-b", "43")
            assertTrue(submitted.get().isCanceled())
        } finally {
            held.release()
        }

        oldQuery.assertSessionChanged()
        assertEquals(1, server.requestCount)
        assertStoredLogin("access-b", "refresh-b", "43")
    }

    @Test fun aLateUnfollowRetryDoesNotPublishDuringTheNewSession() = runTest {
        respond(response(body = UNFOLLOWED))
        respond(response(503))
        val held = hold(response(body = """{"data":{"user":{"self":{"follower":null}}}}"""))
        val unfollow = async(start = CoroutineStart.UNDISPATCHED) {
            follows.unfollow(FollowKind.CHANNEL, "target")
        }
        assertEquals("OAuth access-a", nextRequest().getHeader("Authorization"))
        repeat(2) {
            val verification = nextRequest()
            assertEquals("OAuth access-a", verification.getHeader("Authorization"))
            assertTrue(verification.body.readUtf8().contains("ChannelFollowState"))
        }
        tokens.signIn("access-b", "refresh-b", "42")
        held.release()

        unfollow.assertSessionChanged()
        assertTrue(unpinned.isEmpty())
        assertEquals(0L, changes.revision)
        assertEquals(3, server.requestCount)
        respond(response(body = """{"data":{"user":{"self":{"follower":null}}}}"""))
        assertEquals(FollowStatus.NOT_FOLLOWING, follows.status(FollowKind.CHANNEL, "target"))
        assertTrue(unpinned.isEmpty())
        assertEquals(0L, changes.revision)
    }

    @Test fun lateHome401UsesNoCredentialsFromTheNewSession() = runTest {
        val home = HomeApi(http, auth, server.url("/gql").toString())
        val held = hold(response(401))
        val rows = async(start = CoroutineStart.UNDISPATCHED) { home.shelves() }
        assertEquals("OAuth access-a", nextRequest().getHeader("Authorization"))
        tokens.signIn("access-b", "refresh-b", "43")
        held.release()

        rows.assertSessionChanged()
        assertEquals(1, server.requestCount)
    }

    @Test fun lateLive401StopsWhenItsSessionEnds() = runTest {
        val playback = PlaybackApi(http, auth, server.url("/gql").toString(), server.url("/").toString())
        val held = hold(response(401))
        val url = async(start = CoroutineStart.UNDISPATCHED) { playback.liveStreamUrl("channel") }
        assertEquals("OAuth access-a", nextRequest().getHeader("Authorization"))
        tokens.signIn("access-b", "refresh-b", "43")
        held.release()

        url.assertSessionChanged()
        assertEquals(1, server.requestCount)
    }

    private fun assertStoredLogin(access: String, refresh: String, userId: String) {
        val reloaded = PrefsTokenStore(prefs)
        assertEquals(access, reloaded.accessToken)
        assertEquals(refresh, reloaded.refreshToken)
        assertEquals(userId, reloaded.userId)
        assertEquals(access, tokens.accessToken)
        assertEquals(refresh, tokens.refreshToken)
        assertEquals(userId, tokens.userId)
    }

    private fun respond(reply: MockResponse) { responses.add { reply } }

    private fun hold(reply: MockResponse): HeldResponse =
        HeldResponse(reply).also { responses.add(it::dispatch) }

    private class HeldResponse(private val reply: MockResponse) {
        private val arrived = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun dispatch(): MockResponse {
            arrived.countDown()
            return if (released.await(10, SECONDS)) reply else MockResponse().setResponseCode(500)
        }

        fun awaitArrival() = check(arrived.await(5, SECONDS)) { "The server did not begin the held response" }

        fun release() = released.countDown()
    }

    private suspend fun nextRequest(): RecordedRequest = withContext(Dispatchers.IO) {
        checkNotNull(server.takeRequest(5, SECONDS)) { "Expected request never reached the test server" }
    }

    private suspend fun Deferred<*>.assertSessionChanged() {
        try {
            await()
            fail("Expected the old session's work to be discarded")
        } catch (_: SessionChangedException) {
        }
    }

    private fun response(code: Int = 200, body: String = "") = MockResponse().setResponseCode(code).setBody(body)

    private companion object {
        const val DEVICE_CODE =
            """{"device_code":"d","user_code":"ABCD","verification_uri":"https://example.test/activate","expires_in":60,"interval":1}"""
        const val RENEWED_TOKENS = """{"access_token":"renewed","refresh_token":"renewed-refresh"}"""
        const val UNFOLLOWED = """{"data":{"unfollowUser":{"__typename":"UnfollowUserPayload"}}}"""
    }
}
