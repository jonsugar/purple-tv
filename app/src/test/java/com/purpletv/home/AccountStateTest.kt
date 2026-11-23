package com.purpletv.home

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import com.purpletv.PurpleApp
import com.purpletv.R
import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.signIn
import com.purpletv.category.CategoryActivity
import com.purpletv.channel.ChannelActivity
import com.purpletv.player.PlayerActivity
import com.purpletv.twitch.FollowApi
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.HomeApi
import com.purpletv.twitch.PlaybackApi
import com.purpletv.twitch.TwitchGraphQl
import com.purpletv.ui.RowAdapter
import com.purpletv.ui.RowItem
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountStateTest {
    @Test fun switchingAccountsWhileHomeRefreshesClearsSearchPlaybackAndProfileViews() {
        val app = RuntimeEnvironment.getApplication() as PurpleApp
        val requests = HomeRequests()
        MockWebServer().use { server ->
            server.dispatcher = requests
            app.tokens.signIn("token-a", userId = "a")
            val sessionA = app.tokens.session.id
            app.auth = DeviceAuth(app.http, "tv", "", app.tokens)
            app.helix = HelixClient(app.http, app.tokens, app.auth, server.url("/helix/").toString())
            app.home = HomeApi(app.http, app.auth, server.url("/home").toString())
            app.playback = PlaybackApi(app.http, app.auth, server.url("/playback").toString())
            app.follows = FollowApi(TwitchGraphQl(app.http, app.auth, server.url("/follow").toString()), app.followChanges)

            try {
                Robolectric.buildActivity(MainActivity::class.java).setup().use { main ->
                    val home = main.get()
                    home.showScreen(Screen.SEARCH)
                    waitUntil {
                        home.row(R.id.search_followed).itemCount == 1 &&
                            home.suggestedCategories() == listOf("For A")
                    }
                    val snapshotA = app.snapshot.current
                    assertEquals("follow_a", snapshotA.followedLive.single().login)
                    assertEquals("https://example.invalid/follow.png", snapshotA.avatars["9"])

                    val playerIntent = Intent(app, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_CHANNEL, "offline")
                    Robolectric.buildActivity(PlayerActivity::class.java, playerIntent).setup().use { player ->
                        val playback = player.get()
                        waitUntil { playback.row(R.id.offline_row).itemCount == 1 }
                        assertEquals(View.VISIBLE, playback.findViewById<View>(R.id.overlay_zap).visibility)

                        Robolectric.buildActivity(CategoryActivity::class.java).create().use { category ->
                            Robolectric.buildActivity(ChannelActivity::class.java).create().use { channel ->
                                home.showScreen(Screen.PROFILE)
                                assertEquals("Account A", home.findViewById<TextView>(R.id.profile_name).text.toString())
                                val avatars = listOf(home, category.get(), channel.get()).map {
                                    it.findViewById<ImageView>(R.id.nav_avatar)
                                } + home.findViewById<ImageView>(R.id.profile_avatar)
                                // Simulate Coil having completed the account avatar load.
                                avatars.forEach { it.dispose(); it.setImageDrawable(ColorDrawable(Color.RED)) }
                                home.showScreen(Screen.SEARCH)
                                app.followChanges.changed()
                                waitUntil { requests.oldHomePending.count == 0L }

                                app.tokens.signIn("token-b", userId = "b")
                                assertEquals(HomeSnapshot.Content(), app.snapshot.current)
                                waitUntil {
                                    requests.newProfilePending.count == 0L &&
                                        home.suggestedCategories() == listOf("Public category")
                                }
                                assertEquals(View.GONE, home.findViewById<View>(R.id.search_followed).visibility)
                                assertEquals(0, home.row(R.id.search_followed).itemCount)
                                assertEquals(View.GONE, playback.findViewById<View>(R.id.overlay_zap).visibility)
                                assertEquals(0, playback.row(R.id.offline_row).itemCount)
                                avatars.forEach { assertNull(it.drawable) }
                                assertEquals(View.GONE, playback.findViewById<View>(R.id.offline_row).visibility)

                                requests.releaseOldHome.countDown()
                                waitUntil { requests.oldHomeAnswered.count == 0L }
                                assertFalse(app.snapshot.publishRows(sessionA, snapshotA.followedLive,
                                    snapshotA.avatars, snapshotA.recommendedCategories))
                                requests.releaseNewProfile.countDown()
                                home.showScreen(Screen.HOME)
                                waitUntil {
                                    val adapter = home.findViewById<RecyclerView>(R.id.home_rows).adapter as HomeRowsAdapter
                                    adapter.rows.singleOrNull()?.items?.isFailure == true
                                }
                                assertEquals(HomeSnapshot.Content(), app.snapshot.current)
                                home.showScreen(Screen.PROFILE)
                                assertEquals("", home.findViewById<TextView>(R.id.profile_name).text.toString())
                                assertNull(home.findViewById<ImageView>(R.id.profile_avatar).drawable)

                                Robolectric.buildActivity(PlayerActivity::class.java, playerIntent).create().use { newPlayer ->
                                    assertEquals(View.GONE, newPlayer.get().findViewById<View>(R.id.overlay_zap).visibility)
                                }
                            }
                        }
                    }
                }
            } finally {
                requests.releaseOldHome.countDown()
                requests.releaseNewProfile.countDown()
            }
        }
    }

    private fun Activity.row(id: Int): RowAdapter = findViewById<View>(id)
        .findViewById<RecyclerView>(R.id.row_list).adapter as RowAdapter

    private fun Activity.suggestedCategories() = row(R.id.search_suggest).currentList
        .filterIsInstance<RowItem.CategoryItem>().map { it.category.name }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertTrue("Timed out waiting for the account state to update", condition())
    }

    private class HomeRequests : Dispatcher() {
        val oldHomePending = CountDownLatch(1)
        val releaseOldHome = CountDownLatch(1)
        val oldHomeAnswered = CountDownLatch(1)
        val newProfilePending = CountDownLatch(1)
        val releaseNewProfile = CountDownLatch(1)
        private val homeCalls = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
            "/home" -> {
                if (homeCalls.incrementAndGet() > 1) {
                    oldHomePending.countDown()
                    releaseOldHome.await(10, SECONDS)
                    oldHomeAnswered.countDown()
                }
                json("""{"data":{"shelves":{"edges":[{"node":{"content":{"edges":[
                    {"node":{"__typename":"Game","id":"game-a","displayName":"For A"}}
                ]}}}]}}}""")
            }
            "/helix/users" -> when {
                request.requestUrl?.queryParameter("id") == "9" ->
                    json("""{"data":[{"id":"9","profile_image_url":"https://example.invalid/follow.png"}]}""")
                request.requestUrl?.queryParameter("login") != null -> json("""{"data":[]}""")
                request.getHeader("Authorization") == "Bearer token-a" ->
                    json("""{"data":[{"id":"a","login":"a","display_name":"Account A",
                        "profile_image_url":"https://example.invalid/profile.png"}]}""")
                else -> {
                    newProfilePending.countDown()
                    releaseNewProfile.await(10, SECONDS)
                    MockResponse().setResponseCode(503)
                }
            }
            "/helix/streams/followed" ->
                json("""{"data":[{"user_id":"9","user_login":"follow_a","user_name":"Follow A","language":"en"}]}""")
            "/helix/games/top" -> json("""{"data":[{"id":"public","name":"Public category"}]}""")
            "/helix/streams" -> json("""{"data":[]}""")
            "/playback" -> json("""{"data":{"user":{"stream":null}}}""")
            else -> MockResponse().setResponseCode(404)
        }

        private fun json(body: String) = MockResponse().setBody(body)
    }
}
