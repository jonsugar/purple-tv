package com.purpletv.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import com.purpletv.PurpleApp
import com.purpletv.R
import com.purpletv.auth.LoginActivity
import com.purpletv.twitch.NotLoggedInException
import com.purpletv.twitch.User
import com.purpletv.ui.AvatarCache
import com.purpletv.ui.NavRail
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
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
    private lateinit var following: FollowingPage
    private lateinit var search: SearchPage
    private lateinit var profile: ProfilePage
    private var screen = Screen.HOME
    private var me: User? = null
    private var loadedAtMs = 0L
    private var awaitingLogin = false
    private var followRevision = app.followChanges.revision
    private var followUpdates: Job? = null
    private var homeLoad: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        val avatars = AvatarCache(app.helix)
        home = HomePage(this, app.helix, app.home, avatars, app.snapshot)
        browse = BrowsePage(this, scope, app.helix, avatars)
        following = FollowingPage(this, scope, app.helix, app.following, avatars, ::requireLogin) { tabId ->
            findViewById<View>(R.id.nav_following).nextFocusRightId = tabId
        }
        search = SearchPage(this, scope, app.helix, app.snapshot, RecentSearches(getSharedPreferences(SEARCH_PREFS, MODE_PRIVATE)))
        profile = ProfilePage(this, app.playerPrefs, app.debugMode, ::logout)
        rail = NavRail(findViewById(R.id.nav_rail), ::showScreen)
        following.bindAccount(app.tokens.userId)
        // From the rail's Cerca, Right goes to the field above the suggestions, not into them.
        findViewById<View>(R.id.nav_search).nextFocusRightId = R.id.search_field
        openScreen(screenFrom(intent.getStringExtra(EXTRA_SCREEN)))
    }

    /** The category page's rail opens a main screen by name. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_SCREEN)) openScreen(screenFrom(intent.getStringExtra(EXTRA_SCREEN)))
    }

    private fun openScreen(screen: Screen) {
        showScreen(screen)
        if (screen != Screen.HOME) rail.focus(screen)
    }

    override fun onStart() {
        super.onStart()
        if (followRevision != app.followChanges.revision) {
            followRevision = app.followChanges.revision
            loadedAtMs = 0
        }
        following.bindAccount(app.tokens.userId)
        if (screen == Screen.FOLLOWING) following.show()
        when {
            awaitingLogin -> Unit
            app.tokens.accessToken == null -> { loadedAtMs = 0; login() }
            homeIsStale(loadedAtMs, SystemClock.elapsedRealtime()) -> load()
        }
        followUpdates = scope.launch {
            app.followChanges.revisions.collect { revision ->
                if (revision == followRevision) return@collect
                followRevision = revision
                loadedAtMs = 0
                if (screen == Screen.FOLLOWING) following.show()
                if (!awaitingLogin && app.tokens.accessToken != null) load()
            }
        }
    }

    override fun onStop() {
        followUpdates?.cancel()
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
        following.bindAccount(null)
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
            Screen.FOLLOWING -> following.view.also { following.show() }
            Screen.SEARCH -> search.view.also { search.show() }
            Screen.PROFILE -> profile.view.also { profile.bind(me) }
        })
    }

    private fun login() {
        if (awaitingLogin) return
        awaitingLogin = true
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
    }

    private fun requireLogin() {
        app.tokens.clearLogin()
        following.bindAccount(null)
        login()
    }

    private fun load() {
        loadedAtMs = SystemClock.elapsedRealtime()
        homeLoad?.cancel()
        homeLoad = scope.launch {
            try {
                val user = app.helix.me()
                ensureActive()
                me = user
                app.tokens.userId = user.id
                following.bindAccount(user.id)
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
        following.bindAccount(null)
        me = null
        loadedAtMs = 0
        showScreen(Screen.HOME)
        login()
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        private const val REQ_LOGIN = 1
        private const val SEARCH_PREFS = "search"
    }
}
