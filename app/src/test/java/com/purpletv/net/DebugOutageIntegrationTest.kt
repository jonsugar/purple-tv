package com.purpletv.net

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import com.purpletv.PurpleApp
import com.purpletv.R
import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.InMemoryTokenStore
import com.purpletv.auth.signIn
import com.purpletv.home.FavouritePin
import com.purpletv.twitch.FollowApi
import com.purpletv.twitch.FollowChanges
import com.purpletv.twitch.FollowKind
import com.purpletv.twitch.TwitchGraphQl
import com.purpletv.ui.FollowButton
import com.purpletv.ui.PinButton
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugOutageIntegrationTest {
    @Test
    fun uncertainChannelUnfollowCanBeCheckedAgainWithoutAnotherMutation() = checkChannelUnfollow(
        finalStatus = """{"data":{"user":{"self":{"follower":null}}}}""",
        expectedLabel = R.string.follow,
        expectedRevision = 1L,
    )

    @Test
    fun channelStillFollowedAfterRecheckKeepsItsPin() = checkChannelUnfollow(
        finalStatus = channelFollowed,
        expectedLabel = R.string.following_state,
        expectedRevision = 0L,
    )

    @Test
    fun unfollowFailsAfterFollowingWasReadAndSameControlCanRetry() {
        MockWebServer().use { server ->
            val app = RuntimeEnvironment.getApplication() as PurpleApp
            val tokens = InMemoryTokenStore().apply { signIn("saved", "refresh", "42") }
            val auth = DeviceAuth(app.http, "tv", "", tokens, server.url("/oauth2/").toString())
            val changes = FollowChanges()
            val api = FollowApi(TwitchGraphQl(app.http, auth, server.url("/gql").toString()), changes, app.pins::remove)
            val scope = MainScope()
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val button = TextView(activity)
            val pin = TextView(activity)
            activity.setContentView(LinearLayout(activity).apply { addView(button); addView(pin) })
            try {
                server.enqueue(MockResponse().setBody("""{"data":{"currentUser":{"followedGames":{"nodes":[{"id":"deadlock"}]}}}}"""))
                FollowButton(button, scope, api, tokens, { fail("Outage must not ask for sign-in") },
                    pinButton = PinButton(pin, button, app.pins) { tokens.userId })
                    .bind(FollowKind.CATEGORY, "deadlock", "Deadlock")
                waitUntil { button.text == app.getString(R.string.following_state) }
                assertEquals(app.getString(R.string.pin), pin.text)
                assertTrue(pin.performClick())
                assertEquals(app.getString(R.string.unpin), pin.text)
                assertTrue(pin.performClick())
                assertTrue(app.pins.list(tokens.userId, FollowKind.CATEGORY).isEmpty())
                assertEquals(app.getString(R.string.following_state), button.text)
                assertTrue(pin.performClick())
                assertTrue(button.performClick())
                val confirmation = ShadowAlertDialog.getLatestAlertDialog()

                app.debugMode.setEnabled(true)
                app.debugMode.setNetworkingDisabled(true)
                confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitUntil { ShadowToast.getTextOfLatestToast() == app.getString(R.string.unfollow_error) && button.isClickable }
                assertEquals(app.getString(R.string.following_state), button.text)
                assertTrue(button.isSelected)
                assertEquals(0L, changes.revision)
                assertEquals(1, server.requestCount)
                assertEquals("saved", tokens.accessToken)
                assertEquals(listOf("deadlock"), app.pins.list(tokens.userId, FollowKind.CATEGORY).map { it.id })

                app.debugMode.setNetworkingDisabled(false)
                server.enqueue(MockResponse().setBody("""{"data":{"unfollowGame":{"game":{"id":"deadlock"}}}}"""))
                assertTrue(button.performClick())
                ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitUntil { button.text == app.getString(R.string.follow) }
                assertEquals(android.view.View.GONE, pin.visibility)
                assertTrue(app.pins.list(tokens.userId, FollowKind.CATEGORY).isEmpty())
                assertEquals(1L, changes.revision)
                assertEquals("CategoryFollowState", JSONObject(server.takeRequest(1, SECONDS)!!.body.readUtf8()).getString("operationName"))
                assertEquals("UnfollowCategory", JSONObject(server.takeRequest(1, SECONDS)!!.body.readUtf8()).getString("operationName"))
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun coilCannotFetchAnUncachedRemoteImageUntilNetworkingResumes() {
        MockWebServer().use { server ->
            val app = RuntimeEnvironment.getApplication() as PurpleApp
            val loader = app.newImageLoader()
            val scope = MainScope()
            try {
                val request = ImageRequest.Builder(app).data(server.url("/banner.png").toString())
                    .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
                var result: ImageResult? = null
                app.debugMode.setEnabled(true)
                app.debugMode.setNetworkingDisabled(true)
                scope.launch { result = loader.execute(request) }
                waitUntil { result != null }
                assertTrue((result as ErrorResult).throwable is IOException)
                assertEquals(0, server.requestCount)

                app.debugMode.setNetworkingDisabled(false)
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png")
                    .setBody(Buffer().write(File("src/main/res/drawable-nodpi/banner.png").readBytes())))
                result = null
                scope.launch { result = loader.execute(request) }
                waitUntil { result != null }
                assertTrue("Image result: ${(result as? ErrorResult)?.throwable}", result is SuccessResult)
                assertEquals(1, server.requestCount)
            } finally {
                scope.cancel()
                loader.shutdown()
            }
        }
    }

    @Test
    @OptIn(UnstableApi::class)
    fun mediaRequestsAreBlockedAndAnOpenMediaBodyIsInterrupted() {
        MockWebServer().use { server ->
            val mode = DebugMode().apply { setEnabled(true) }
            val liveFactory = Http.mediaFactory(mode)
            val recordedFactory = Http.mediaFactory(mode).setUserAgent("PurpleTV")
            val live = DataSpec(Uri.parse(server.url("/live/index.m3u8").toString()))
            val recorded = DataSpec(Uri.parse(server.url("/recorded/clip.mp4").toString()))

            mode.setNetworkingDisabled(true)
            for ((factory, spec) in listOf(liveFactory to live, recordedFactory to recorded)) {
                val source = factory.createDataSource()
                try {
                    assertThrows(IOException::class.java) { source.open(spec) }
                } finally {
                    source.close()
                }
            }
            assertEquals(0, server.requestCount)

            mode.setNetworkingDisabled(false)
            server.enqueue(MockResponse().setBody("video data").setBodyDelay(4, SECONDS))
            val source = recordedFactory.createDataSource()
            val reader = Executors.newSingleThreadExecutor()
            try {
                source.open(recorded)
                assertEquals("/recorded/clip.mp4", server.takeRequest(1, SECONDS)?.path)
                val read = reader.submit { assertThrows(IOException::class.java) { source.read(ByteArray(16), 0, 16) } }
                mode.setNetworkingDisabled(true)
                read.get(2, SECONDS)
            } finally {
                reader.shutdownNow()
                source.close()
            }

            mode.setEnabled(false)
            server.enqueue(MockResponse().setBody("#EXTM3U"))
            val restored = liveFactory.createDataSource()
            try {
                assertEquals(7L, restored.open(live))
                assertEquals("/live/index.m3u8", server.takeRequest(1, SECONDS)?.path)
            } finally {
                restored.close()
            }
        }
    }

    private fun checkChannelUnfollow(finalStatus: String, expectedLabel: Int, expectedRevision: Long) {
        MockWebServer().use { server ->
            val app = RuntimeEnvironment.getApplication() as PurpleApp
            val tokens = InMemoryTokenStore().apply { signIn("saved", "refresh", "42") }
            val auth = DeviceAuth(app.http, "tv", "", tokens, server.url("/oauth2/").toString())
            val changes = FollowChanges()
            val api = FollowApi(TwitchGraphQl(app.http, auth, server.url("/gql").toString()), changes, app.pins::remove)
            val scope = MainScope()
            val activity = Robolectric.buildActivity(Activity::class.java).setup()
            val button = TextView(activity.get()).apply { isFocusableInTouchMode = true }
            val pin = TextView(activity.get())
            activity.get().setContentView(LinearLayout(activity.get()).apply { addView(button); addView(pin) })
            val target = FavouritePin("84", "Channel")
            try {
                app.pins.add("42", FollowKind.CHANNEL, target)
                server.enqueue(MockResponse().setBody(channelFollowed))
                FollowButton(button, scope, api, tokens, { fail("Unexpected login request") },
                    pinButton = PinButton(pin, button, app.pins) { tokens.userId })
                    .bind(FollowKind.CHANNEL, target.id, target.name)
                waitUntil { button.text == app.getString(R.string.following_state) }
                assertEquals(app.getString(R.string.unpin), pin.text)

                assertTrue(button.performClick())
                val confirmation = ShadowAlertDialog.getLatestAlertDialog()
                waitUntil { confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).hasFocus() }
                server.enqueue(MockResponse().setBody("""{"data":{"unfollowUser":{"__typename":"UnfollowUserPayload"}}}"""))
                repeat(2) { server.enqueue(MockResponse().setResponseCode(503)) }
                confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitUntil { button.text == app.getString(R.string.follow_retry) }
                assertEquals(app.getString(R.string.unfollow_unverified), ShadowToast.getTextOfLatestToast())
                assertTrue(button.isClickable)
                assertFalse(button.isSelected)
                assertEquals(listOf(target), app.pins.list("42", FollowKind.CHANNEL))
                assertEquals(0L, changes.revision)
                assertEquals(4, server.requestCount)

                server.enqueue(MockResponse().setBody(finalStatus))
                assertTrue(button.performClick())
                waitUntil { button.text == app.getString(expectedLabel) }
                assertEquals(expectedRevision, changes.revision)
                if (expectedLabel == R.string.follow) {
                    assertTrue(app.pins.list("42", FollowKind.CHANNEL).isEmpty())
                    assertEquals(View.GONE, pin.visibility)
                } else {
                    assertEquals(listOf(target), app.pins.list("42", FollowKind.CHANNEL))
                    assertEquals(app.getString(R.string.unpin), pin.text)
                    assertEquals(app.getString(R.string.unfollow_still_following), ShadowToast.getTextOfLatestToast())
                }
                assertEquals(5, server.requestCount)
                val operations = listOf("ChannelFollowState", "UnfollowChannel", "ChannelFollowState",
                    "ChannelFollowState", "ChannelFollowState")
                for (operation in operations) {
                    val request = server.takeRequest(1, SECONDS)!!
                    assertEquals("OAuth saved", request.getHeader("Authorization"))
                    assertEquals(operation, JSONObject(request.body.readUtf8()).getString("operationName"))
                }
            } finally {
                scope.cancel()
                activity.pause().stop().destroy()
            }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the main thread to show the result")
    }

    private val channelFollowed = """{"data":{"user":{"self":{"follower":{"followedAt":"2026-11-01T00:00:00Z"}}}}}"""
}
