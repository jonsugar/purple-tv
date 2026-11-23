package com.purpletv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.purpletv.PurpleApp
import com.purpletv.R
import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.signIn
import com.purpletv.channel.ChannelActivity
import com.purpletv.home.FavouritePin
import com.purpletv.home.GhostFollow
import com.purpletv.home.GhostFollowStore
import com.purpletv.twitch.FollowApi
import com.purpletv.twitch.FollowKind
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.TwitchGraphQl
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GhostFollowButtonTest {
    private val app = RuntimeEnvironment.getApplication() as PurpleApp
    private val channel = GhostFollow("42", "offline_channel", "Offline channel")
    private val account = "account-a"
    private val calls = AtomicInteger()
    private val followed = AtomicBoolean(true)

    @Test fun offlineChannelToggleSavesLocallyAndLeavesTwitchFollowAndPinUnchanged() {
        val http = stubTwitch()
        val pin = FavouritePin(channel.id, channel.name)
        app.pins.add(account, FollowKind.CHANNEL, pin)
        val intent = Intent(app, ChannelActivity::class.java)
            .putExtra(ChannelActivity.EXTRA_ID, channel.id)
            .putExtra(ChannelActivity.EXTRA_LOGIN, "old_login")
        val controller = Robolectric.buildActivity(ChannelActivity::class.java, intent).setup()
        val activity = controller.get()
        val ghost = activity.findViewById<TextView>(R.id.channel_ghost_follow)
        val follow = activity.findViewById<TextView>(R.id.channel_follow)
        val pinView = activity.findViewById<TextView>(R.id.channel_pin)
        val state = activity.findViewById<TextView>(R.id.channel_state)

        awaitUi { calls.get() == 5 && follow.isSelected && state.text == app.getString(R.string.offline) }
        assertEquals(ghost.id, follow.nextFocusRightId)
        assertEquals(View.VISIBLE, pinView.visibility)
        assertEquals(app.getString(R.string.ghost_follow), ghost.text)
        assertTrue(ghost.performClick())
        assertTrue(ghost.isSelected)
        assertEquals(app.getString(R.string.ghost_unfollow), ghost.text)
        assertEquals(listOf(channel), app.ghostFollows.list(account))
        assertTrue(ghost.performClick())
        assertFalse(ghost.isSelected)
        assertEquals(emptyList<GhostFollow>(), app.ghostFollows.list(account))
        assertTrue(ghost.performClick())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(5, calls.get())
        assertEquals(0L, app.followChanges.revision)
        assertTrue(follow.isSelected)
        assertEquals(listOf(pin), app.pins.list(account, FollowKind.CHANNEL))

        // Only the independent Twitch action removes the pin. The local ghost survives.
        runBlocking { app.follows.unfollow(FollowKind.CHANNEL, channel.id) }
        assertEquals(emptyList<FavouritePin>(), app.pins.list(account, FollowKind.CHANNEL))
        assertEquals(listOf(channel), app.ghostFollows.list(account))
        controller.pause().stop().restart().resume()
        awaitUi { calls.get() == 9 && follow.text == app.getString(R.string.follow) }
        assertEquals(View.GONE, pinView.visibility)
        assertTrue(ghost.isSelected)
        assertEquals(0, http.dispatcher.queuedCallsCount())
        controller.pause().stop().destroy()
    }

    @Test fun playerIconSharesTheLocalStateAndIgnoresAClickAfterAccountSwitch() {
        stubTwitch()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(R.layout.overlay_info)
        val icon = activity.findViewById<ImageView>(R.id.overlay_ghost_follow)
        val button = GhostFollowButton(icon, app.ghostFollows, { app.tokens.userId })
        button.bind(channel.id, channel.login, channel.name)
        assertEquals(app.getString(R.string.ghost_follow), icon.contentDescription)
        assertTrue(icon.performClick())
        assertEquals(app.getString(R.string.ghost_unfollow), icon.contentDescription)

        val restartedStore = GhostFollowStore(app.getSharedPreferences("ghost_follows", Context.MODE_PRIVATE))
        assertEquals(listOf(channel), restartedStore.list(account))
        app.tokens.signIn("test-token", userId = "account-b")
        assertTrue(icon.performClick())
        assertEquals(listOf(channel), restartedStore.list(account))
        assertEquals(emptyList<GhostFollow>(), restartedStore.list("account-b"))
        button.bind(channel.id, channel.login, channel.name)
        assertFalse(icon.isSelected)
        assertTrue(icon.performClick())
        assertTrue(icon.performClick())
        assertEquals(emptyList<GhostFollow>(), restartedStore.list("account-b"))
        assertEquals(0, calls.get())
    }

    private fun stubTwitch(): OkHttpClient {
        app.tokens.signIn("test-token", userId = account)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { calls.incrementAndGet() }
        }).addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.encodedPath) {
                "/helix/users" -> {
                    check(request.url.queryParameter("id") == channel.id)
                    """{"data":[{"id":"42","login":"offline_channel","display_name":"Offline channel"}]}"""
                }
                "/helix/streams" -> {
                    check(request.url.queryParameter("user_login") == channel.login)
                    """{"data":[]}"""
                }
                "/helix/videos", "/helix/clips" -> """{"data":[]}"""
                "/gql" -> {
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    when (JSONObject(buffer.readUtf8()).getString("operationName")) {
                        "ChannelFollowState" -> if (followed.get())
                            """{"data":{"user":{"self":{"follower":{"followedAt":"2026-11-01T00:00:00Z"}}}}}"""
                            else """{"data":{"user":{"self":{"follower":null}}}}"""
                        "UnfollowChannel" -> {
                            followed.set(false)
                            """{"data":{"unfollowUser":{"__typename":"UnfollowUserPayload"}}}"""
                        }
                        else -> error("Unexpected Twitch operation")
                    }
                }
                else -> error("Unexpected Twitch endpoint")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val auth = DeviceAuth(http, "tv-test-client", "", app.tokens)
        app.helix = HelixClient(http, app.tokens, auth)
        app.follows = FollowApi(TwitchGraphQl(http, auth), app.followChanges, app.pins::remove)
        return http
    }

    private fun awaitUi(ready: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            looper.idle()
            if (ready()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        error("Channel page did not settle. Twitch calls: ${calls.get()}")
    }
}
