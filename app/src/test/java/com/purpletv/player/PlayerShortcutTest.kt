package com.purpletv.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.purpletv.PurpleApp
import com.purpletv.R
import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.InMemoryTokenStore
import com.purpletv.auth.signIn
import com.purpletv.twitch.FollowApi
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.PlaybackApi
import com.purpletv.twitch.Stream
import com.purpletv.twitch.TwitchGraphQl
import com.purpletv.ui.Nav
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit.SECONDS

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerShortcutTest {
    private val app get() = RuntimeEnvironment.getApplication() as PurpleApp

    @Test fun onlyTheShortcutIsExported() {
        assertTrue(app.packageManager.getActivityInfo(shortcut().component!!, 0).exported)
        val player = Intent(app, PlayerActivity::class.java)
        assertFalse(app.packageManager.getActivityInfo(player.component!!, 0).exported)
    }

    @Test fun missingOrInvalidChannelsDoNotStartThePlayer() {
        val invalid = listOf(shortcut(), shortcut().putExtra(PlayerActivity.EXTRA_CHANNEL, 42)) +
            listOf("", "a/b", " spaced ", "café", "line\n", "a".repeat(26)).map { shortcut(it) }
        for (intent in invalid) assertNull(forwardedIntent(intent))
    }

    @Test fun validChannelsStartTheInternalPlayerWithoutCallerMetadata() {
        for (channel in listOf("A", "Test_Channel_42", "X".repeat(25))) {
            val incoming = shortcut(channel).apply {
                putExtra(PlayerActivity.EXTRA_NAME, "Caller name")
                putExtra(PlayerActivity.EXTRA_TITLE, "Caller title")
                putExtra(PlayerActivity.EXTRA_GAME, "Caller game")
                putExtra(PlayerActivity.EXTRA_VIEWERS, 999)
                putExtra(PlayerActivity.EXTRA_THUMB, "caller thumbnail")
                putExtra(PlayerActivity.EXTRA_AVATAR, "caller avatar")
                putExtra(PlayerActivity.EXTRA_SHORTCUT, false)
                data = Uri.parse("https://example.invalid/")
            }

            val outgoing = checkNotNull(forwardedIntent(incoming))
            assertEquals(PlayerActivity::class.java.name, outgoing.component?.className)
            assertEquals(setOf(PlayerActivity.EXTRA_CHANNEL, PlayerActivity.EXTRA_SHORTCUT), outgoing.extras?.keySet())
            assertEquals(channel.lowercase(), outgoing.getStringExtra(PlayerActivity.EXTRA_CHANNEL))
            assertTrue(outgoing.getBooleanExtra(PlayerActivity.EXTRA_SHORTCUT, false))
            assertNull(outgoing.data)
        }
    }

    @Test fun shortcutLoadsAppMetadataAndUsesTheDefaultChannelsRow() {
        val previousRow = ZapList("Previous card row", listOf(card))
        app.zap = previousRow
        val outgoing = checkNotNull(forwardedIntent(shortcut(card.login)))
        MockWebServer().use { server ->
            configurePlayback(server)
            val player = Robolectric.buildActivity(PlayerActivity::class.java, outgoing).create()
            assertEquals(View.GONE, player.get().findViewById<View>(R.id.overlay_zap).visibility)
            assertSame(previousRow, app.zap)
            assertEquals(card.login, player.get().tuneText(R.id.tune_name))

            player.start()
            try {
                waitForTitle(player.get(), "Resolved title")
                assertEquals("Resolved name", player.get().tuneText(R.id.tune_name))
                assertTrue(player.get().tuneText(R.id.tune_meta).contains("Resolved category"))
            } finally {
                player.stop().destroy()
            }
        }
    }

    @Test fun inAppStreamCardStillSuppliesTuneInImagesAndTheChannelsRow() {
        val caller = Robolectric.buildActivity(Activity::class.java).create()
        val row = ZapList("Card row", listOf(card))
        val avatar = "https://example.invalid/avatar.png"
        Nav.openStream(caller.get(), card.login, card, avatar, row)
        val outgoing = shadowOf(caller.get()).nextStartedActivity
        caller.destroy()

        assertSame(row, app.zap)
        assertEquals(PlayerActivity::class.java.name, outgoing.component?.className)
        assertEquals(card.toTuneInfo(avatar).thumbnail, outgoing.getStringExtra(PlayerActivity.EXTRA_THUMB))
        assertEquals(avatar, outgoing.getStringExtra(PlayerActivity.EXTRA_AVATAR))
        val player = Robolectric.buildActivity(PlayerActivity::class.java, outgoing).create()
        try {
            assertEquals(card.title, player.get().tuneText(R.id.tune_title))
            assertEquals(card.displayName, player.get().tuneText(R.id.tune_name))
            assertEquals(View.VISIBLE, player.get().findViewById<View>(R.id.overlay_zap).visibility)
        } finally {
            player.destroy()
        }
    }

    private fun shortcut(channel: String? = null) = Intent(app, PlayerShortcutActivity::class.java).apply {
        if (channel != null) putExtra(PlayerActivity.EXTRA_CHANNEL, channel)
    }

    private fun forwardedIntent(intent: Intent): Intent? {
        val shortcut = Robolectric.buildActivity(PlayerShortcutActivity::class.java, intent).create()
        try {
            assertTrue(shortcut.get().isFinishing)
            return shadowOf(shortcut.get()).nextStartedActivity
        } finally {
            shortcut.destroy()
        }
    }

    private fun configurePlayback(server: MockWebServer) {
        val tokens = InMemoryTokenStore().apply { signIn("test-token", "test-refresh", "42") }
        val auth = DeviceAuth(app.http, "tv", "", tokens)
        app.tokens = tokens
        app.helix = HelixClient(app.http, tokens, auth, server.url("/helix/").toString())
        app.follows = FollowApi(TwitchGraphQl(app.http, auth, server.url("/follow").toString()), app.followChanges)
        app.playback = PlaybackApi(app.http, auth, server.url("/playback").toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/helix/streams?user_login=${card.login}" -> MockResponse().setBody(
                    """{"data":[{"user_id":"9","user_login":"${card.login}","user_name":"Resolved name",
                        "title":"Resolved title","game_name":"Resolved category","viewer_count":180}]}""",
                )
                "/helix/users?id=9" -> MockResponse().setBody("""{"data":[{"id":"9"}]}""")
                "/follow" -> MockResponse().setBody("""{"data":{"user":{"self":{}}}}""")
                "/playback" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun waitForTitle(player: PlayerActivity, title: String) {
        val deadline = System.nanoTime() + SECONDS.toNanos(5)
        while (player.tuneText(R.id.tune_title) != title && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(title, player.tuneText(R.id.tune_title))
    }

    private fun PlayerActivity.tuneText(id: Int) = findViewById<TextView>(id).text.toString()

    private val card = Stream("9", "example", "Card name", "Card title", "g", "Card category", 120,
        "https://example.invalid/thumb-{width}x{height}.jpg")
}
