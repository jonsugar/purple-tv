package com.purpletv.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.FrameLayout
import com.purpletv.PurpleApp
import com.purpletv.R
import com.purpletv.auth.LoginActivity
import com.purpletv.twitch.NotLoggedInException
import com.purpletv.twitch.User
import com.purpletv.ui.AvatarCache
import com.purpletv.ui.NavRail
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/** The launcher entry: navigation rail on the left, the selected page on the right. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private val app get() = PurpleApp.instance
    private lateinit var content: FrameLayout
    private lateinit var rail: NavRail
    private lateinit var home: HomePage
    private lateinit var browse: BrowsePage
    private lateinit var search: SearchPage
    private lateinit var profile: ProfilePage
    private var screen = Screen.HOME
    private var me: User? = null
    private var loadedAtMs = 0L
    private var awaitingLogin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        val avatars = AvatarCache(app.helix)
        home = HomePage(this, app.helix, app.home, avatars, app.snapshot)
        browse = BrowsePage(this, scope, app.helix, avatars)
        search = SearchPage(this, scope, app.helix)
        profile = ProfilePage(this, app.playerPrefs, ::logout)
        rail = NavRail(findViewById(R.id.nav_rail), ::showScreen)
        showScreen(Screen.HOME)
    }

    override fun onStart() {
        super.onStart()
        when {
            awaitingLogin -> Unit
            app.tokens.accessToken == null -> { loadedAtMs = 0; login() }
            homeIsStale(loadedAtMs, SystemClock.elapsedRealtime()) -> load()
        }
    }

    override fun onStop() {
        home.stop()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Activity result API of the platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_LOGIN) return
        awaitingLogin = false
        // A new login, maybe another account: the rows and the profile load again.
        if (resultCode == RESULT_OK) load() else finish()
    }

    @Deprecated("Back handling of the platform Activity")
    override fun onBackPressed() {
        when (backAction(screen, home.isAtTop())) {
            BackAction.GO_HOME -> {
                showScreen(Screen.HOME)
                home.scrollToTop()
                home.focusFirst()
            }
            BackAction.HOME_TOP -> {
                home.scrollToTop()
                home.focusFirst()
            }
            BackAction.EXIT -> super.onBackPressed()
        }
    }

    fun showScreen(screen: Screen) {
        this.screen = screen
        rail.select(screen)
        content.removeAllViews()
        content.addView(when (screen) {
            Screen.HOME -> home.view
            Screen.BROWSE -> browse.view.also { browse.show() }
            Screen.SEARCH -> search.view
            Screen.PROFILE -> profile.view.also { profile.bind(me) }
        })
    }

    private fun login() {
        awaitingLogin = true
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
    }

    private fun load() {
        loadedAtMs = SystemClock.elapsedRealtime()
        scope.launch {
            try {
                val user = app.helix.me()
                me = user
                app.tokens.userId = user.id
                app.snapshot.meAvatar = user.profileImageUrl
                rail.setAvatar(user.profileImageUrl)
                home.load(user.id)
            } catch (e: NotLoggedInException) {
                loadedAtMs = 0
                login()
            } catch (e: IOException) {
                loadedAtMs = 0
                home.showError()
            }
        }
    }

    private fun logout() {
        app.tokens.clearLogin()
        me = null
        loadedAtMs = 0
        showScreen(Screen.HOME)
        login()
    }

    private companion object {
        const val REQ_LOGIN = 1
    }
}
