package com.purpletv.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
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
    private var pinUpdates: Job? = null
    private var pinRevision = app.pins.revisions.value
    private var ghostRevision = app.ghostFollows.revision
    private var homeGhostLoad: Job? = null
    private var homeLoad: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        val avatars = AvatarCache(app.helix)
        home = HomePage(this, app.helix, app.home, avatars, app.snapshot, app.pins, app.streamPrefs, app.ghostFollows)
        browse = BrowsePage(this, scope, app.helix, avatars, app.streamPrefs) { tabId ->
            findViewById<View>(R.id.nav_browse).nextFocusRightId = tabId
        }
        following = FollowingPage(this, scope, app.helix, app.following, avatars, app.pins, app.streamPrefs,
            app.ghostFollows, ::requireLogin) { tabId ->
            findViewById<View>(R.id.nav_following).nextFocusRightId = tabId
        }
        search = SearchPage(this, scope, app.helix, app.snapshot,
            RecentSearches(getSharedPreferences(SEARCH_PREFS, MODE_PRIVATE)), app.streamPrefs)
        profile = ProfilePage(this, app.playerPrefs, app.debugMode, ::logout)
        rail = NavRail(findViewById(R.id.nav_rail), ::showScreen)
        following.bindAccount(app.tokens.userId)
        // From the rail's Cerca, Right goes to the field above the suggestions, not into them.
        findViewById<View>(R.id.nav_search).nextFocusRightId = R.id.search_field
        openScreen(screenFrom(intent.getStringExtra(EXTRA_SCREEN)))
        var previousLanguage = app.streamPrefs.language.value
        scope.launch {
            app.streamPrefs.language.collect { language ->
                if (previousLanguage == language) return@collect
                previousLanguage = language
                home.applyStreamPreferences(app.tokens.userId)
                following.refreshLanguage()
                search.refreshLiveSuggestions()
            }
        }
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
        val followChanged = followRevision != app.followChanges.revision
        if (followChanged) {
            followRevision = app.followChanges.revision
            loadedAtMs = 0
        }
        following.bindAccount(app.tokens.userId)
        home.clearPreviousAccount(app.tokens.userId)
        val pinsChanged = pinRevision != app.pins.revisions.value
        val ghostsChanged = ghostRevision != app.ghostFollows.revision
        ghostRevision = app.ghostFollows.revision
        following.onReturn(pinsChanged, followChanged, ghostsChanged)
        if (pinsChanged) {
            pinRevision = app.pins.revisions.value
            home.applyStreamPreferences(app.tokens.userId)
        }
        if (screen == Screen.FOLLOWING) following.show()
        if (screen == Screen.BROWSE) browse.show()
        when {
            awaitingLogin -> Unit
            app.tokens.accessToken == null -> { loadedAtMs = 0; login() }
            homeIsStale(loadedAtMs, SystemClock.elapsedRealtime()) -> load()
            ghostsChanged -> app.tokens.userId?.let(::refreshHomeGhostFollows)
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
        pinUpdates = scope.launch {
            app.pins.revisions.collect { revision ->
                if (revision == pinRevision) return@collect
                pinRevision = revision
                following.applyPins()
                home.applyStreamPreferences(app.tokens.userId)
            }
        }
    }

    override fun onStop() {
        followUpdates?.cancel()
        pinUpdates?.cancel()
        following.cancelPinMove(restoreFocus = false)
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
        if (screen == Screen.FOLLOWING && following.cancelPinMove()) return
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (screen == Screen.FOLLOWING && following.handlePinMoveKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    fun showScreen(screen: Screen) {
        if (this.screen == Screen.FOLLOWING) following.cancelPinMove(restoreFocus = false)
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

    private fun requireLogin(error: NotLoggedInException) {
        if (!error.clearLogin(app.tokens)) return
        loadedAtMs = 0
        following.bindAccount(null)
        login()
    }

    private fun load() {
        loadedAtMs = SystemClock.elapsedRealtime()
        homeLoad?.cancel()
        val session = app.auth.sessionId
        homeLoad = scope.launch {
            try {
                val user = app.helix.me(session)
                ensureActive()
                if (!app.tokens.updateUserId(session, user.id)) return@launch
                me = user
                following.bindAccount(user.id)
                home.clearPreviousAccount(user.id)
                app.snapshot.meAvatar = user.profileImageUrl
                rail.setAvatar(user.profileImageUrl)
                home.load(user.id, session)
                if (app.auth.sessionId !== session) return@launch
                refreshHomeGhostFollows(user.id)
                search.refreshLiveSuggestions()
            } catch (e: NotLoggedInException) {
                requireLogin(e)
            } catch (e: IOException) {
                loadedAtMs = 0
                home.showError()
            }
        }
    }

    private fun refreshHomeGhostFollows(account: String) {
        homeGhostLoad?.cancel()
        homeGhostLoad = scope.launch { home.refreshGhostLive(account) }
    }

    private fun logout() {
        homeLoad?.cancel()
        homeGhostLoad?.cancel()
        app.tokens.clearLogin()
        following.bindAccount(null)
        home.clearPreviousAccount(null)
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
