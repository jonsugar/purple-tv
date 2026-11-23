package com.purpletv.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.widget.TextView
import com.purpletv.R
import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.InMemoryTokenStore
import com.purpletv.auth.signIn
import com.purpletv.twitch.FollowApi
import com.purpletv.twitch.FollowChanges
import com.purpletv.twitch.FollowKind
import com.purpletv.twitch.TwitchGraphQl
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.TimeUnit.SECONDS

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FollowButtonSessionTest {
    @Test
    fun aConfirmationOpenedBeforeSameAccountSignInCannotUnfollow() {
        MockWebServer().use { server ->
            val tokens = InMemoryTokenStore().apply { signIn("old", userId = "42") }
            val http = OkHttpClient()
            val auth = DeviceAuth(http, "tv", "", tokens, server.url("/oauth2/").toString())
            val changes = FollowChanges()
            val api = FollowApi(TwitchGraphQl(http, auth, server.url("/gql").toString()), changes)
            val activity = Robolectric.buildActivity(Activity::class.java).setup()
            val view = TextView(activity.get())
            activity.get().setContentView(view)
            val scope = MainScope()
            try {
                server.enqueue(MockResponse().setBody("""{"data":{"currentUser":{"followedGames":{"nodes":[{"id":"target"}]}}}}"""))
                val control = FollowButton(view, scope, api, tokens, { fail("Unexpected login request") })
                control.bind(FollowKind.CATEGORY, "target", "Target")
                val following = activity.get().getString(R.string.following_state)
                val deadline = System.nanoTime() + SECONDS.toNanos(5)
                while (view.text != following && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.yield()
                }
                assertEquals(following, view.text)
                assertTrue(view.performClick())
                val confirmation = ShadowAlertDialog.getLatestAlertDialog()

                tokens.signIn("new", userId = "42")
                confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()

                assertTrue(view.isClickable)
                assertEquals(1, server.requestCount)
                assertEquals(0L, changes.revision)
                control.clear()
            } finally {
                scope.cancel()
                activity.pause().stop().destroy()
            }
        }
    }
}
