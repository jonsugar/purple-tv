package com.purpletv.player

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.purpletv.PurpleApp
import com.purpletv.R
import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.signIn
import com.purpletv.twitch.RecordedPlaybackApi
import com.purpletv.twitch.TwitchGraphQl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordedPlayerErrorTest {
    @Test fun forbiddenPlaybackIsUnavailable() = checkPlaybackError(403, R.string.recording_unavailable)

    @Test fun missingPlaybackIsUnavailable() = checkPlaybackError(404, R.string.recording_unavailable)

    @Test fun rateLimitedPlaybackIsRetryable() = checkPlaybackError(429, R.string.recording_error)

    @Test fun serverFailureDuringPlaybackIsRetryable() = checkPlaybackError(503, R.string.recording_error)

    @Test fun playbackFailureWithoutAResponseIsRetryable() = checkPlaybackError(null, R.string.recording_error)

    private fun checkPlaybackError(status: Int?, messageId: Int) {
        MockWebServer().use { server ->
            val app = RuntimeEnvironment.getApplication() as PurpleApp
            app.tokens.signIn("test-token", userId = "42")
            val auth = DeviceAuth(app.http, "tv", "", app.tokens)
            val gql = TwitchGraphQl(app.http, auth, server.url("/graphql").toString())
            app.recordedPlayback = RecordedPlaybackApi(app.http, gql, server.url("/").toString())
            val preflights = AtomicInteger()
            val mediaRequests = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/graphql" -> MockResponse().setBody(
                        """{"data":{"videoPlaybackAccessToken":{"value":"test-value","signature":"test-signature"}}}""",
                    )
                    request.requestUrl?.encodedPath != "/vod/123.m3u8" -> MockResponse().setResponseCode(404)
                    request.getHeader("User-Agent") != "PurpleTV" -> {
                        preflights.incrementAndGet()
                        MockResponse().setBody("#EXTM3U")
                    }
                    else -> {
                        mediaRequests.incrementAndGet()
                        if (status == null) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        else MockResponse().setResponseCode(status)
                    }
                }
            }
            val intent = Intent(app, RecordedPlayerActivity::class.java)
                .putExtra(RecordedPlayerActivity.EXTRA_ID, "123")
            Robolectric.buildActivity(RecordedPlayerActivity::class.java, intent).setup().use { controller ->
                val activity = controller.get()
                val message = activity.findViewById<View>(R.id.recorded_message)
                val retry = activity.findViewById<View>(R.id.recorded_retry)

                waitUntil("Playback error for HTTP $status after a successful preflight") { message.visibility == View.VISIBLE }
                assertEquals(1, preflights.get())
                assertTrue(mediaRequests.get() > 0)
                assertEquals(activity.getString(messageId), activity.findViewById<TextView>(R.id.recorded_message_text).text)
                assertTrue(retry.hasFocus())

                assertTrue(retry.performClick())
                assertEquals(View.GONE, message.visibility)
                waitUntil("A second preflight after Retry") { preflights.get() == 2 }
            }
        }
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(10)
        }
        assertTrue(description, condition())
    }
}
