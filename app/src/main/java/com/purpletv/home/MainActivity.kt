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
import com.purpletv.auth.SessionId
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
    private var displayedSession: SessionId? = null
    private var displayedProfile: User? = null
    private var started = false
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
        // From the rail's Cerca, Right goes to the field above the suggestions, not into them.
        findViewById<View>(R.id.nav_search).nextFocusRightId = R.id.search_field
        openScreen(screenFrom(intent.getStringExtra(EXTRA_SCREEN)))
        scope.launch {
            app.snapshot.updates.collect {
                val changed = syncSession()
                bindProfile(app.snapshot.current.profile)
                search.refreshSuggestions()
                if (changed && started) loadOrLogin()
            }
        }
        var previousLanguage = app.streamPrefs.language.value
        scope.launch {
            app.streamPrefs.language.collect { language ->
                if (previousLanguage == language) return@collect
                previousLanguage = language
                home.applyStreamPreferences(app.tokens.userId)
                following.refreshLanguage()
                search.refreshSuggestions()
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
        started = true
        syncSession()
        val followChanged = followRevision != app.followChanges.revision
        if (followChanged) {
            followRevision = app.followChanges.revision
            loadedAtMs = 0
        }
        following.bindAccount(app.tokens.userId)
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
        if (awaitingLogin || app.tokens.accessToken == null ||
            homeIsStale(loadedAtMs, SystemClock.elapsedRealtime())) loadOrLogin()
        else if (ghostsChanged) app.tokens.userId?.let(::refreshHomeGhostFollows)
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
        started = false
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
        // A new login, maybe another account: the rows and the profile load again.
        if (resultCode == RESULT_OK) loadOrLogin() else finish()
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
        val changed = syncSession()
        if (this.screen == Screen.FOLLOWING) following.cancelPinMove(restoreFocus = false)
        this.screen = screen
        rail.select(screen)
        content.removeAllViews()
        content.addView(when (screen) {
            Screen.HOME -> home.view
            Screen.BROWSE -> browse.view.also { browse.show() }
            Screen.FOLLOWING -> following.view.also { following.show() }
            Screen.SEARCH -> search.view.also { search.show() }
            Screen.PROFILE -> profile.view.also { profile.bind(app.snapshot.current.profile) }
        })
        if (changed && started) loadOrLogin()
    }

    private fun syncSession(): Boolean {
        val session = app.tokens.session
        if (displayedSession === session.id) return false
        displayedSession = session.id
        homeLoad?.cancel()
        homeGhostLoad?.cancel()
        loadedAtMs = 0
        home.clearPreviousSession(session.id)
        following.bindAccount(session.userId, session.id)
        search.onSessionChanged()
        bindProfile(app.snapshot.current.profile)
        return true
    }

    private fun bindProfile(user: User?) {
        if (displayedProfile == user) return
        displayedProfile = user
        profile.bind(user)
        rail.setAvatar(user?.profileImageUrl)
    }

    private fun loadOrLogin() {
        syncSession()
        if (awaitingLogin) return
        if (app.tokens.accessToken == null) login() else load()
    }

    private fun login() {
        if (awaitingLogin) return
        awaitingLogin = true
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
    }

    private fun requireLogin(error: NotLoggedInException) {
        if (!error.clearLogin(app.tokens)) return
        loadOrLogin()
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
                if (!app.snapshot.publishProfile(session, user)) return@launch
                following.bindAccount(user.id, session)
                home.load(user.id, session)
                if (app.auth.sessionId !== session) return@launch
                refreshHomeGhostFollows(user.id)
            } catch (e: NotLoggedInException) {
                requireLogin(e)
            } catch (e: IOException) {
                if (app.auth.sessionId !== session) return@launch
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
        app.tokens.clearLogin()
        syncSession()
        showScreen(Screen.HOME)
        loadOrLogin()
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        private const val REQ_LOGIN = 1
        private const val SEARCH_PREFS = "search"
    }
}
