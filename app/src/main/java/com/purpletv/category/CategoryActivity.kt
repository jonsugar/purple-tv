package com.purpletv.category

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.load
import com.purpletv.PurpleApp
import com.purpletv.R
import com.purpletv.home.MainActivity
import com.purpletv.home.Screen
import com.purpletv.home.homeIsStale
import com.purpletv.home.screenFrom
import com.purpletv.player.ZapList
import com.purpletv.twitch.FollowKind
import com.purpletv.twitch.NotLoggedInException
import com.purpletv.twitch.Stream
import com.purpletv.twitch.sized
import com.purpletv.ui.AvatarCache
import com.purpletv.ui.ContentGrid
import com.purpletv.ui.FollowButton
import com.purpletv.ui.PinButton
import com.purpletv.ui.GridAdapter
import com.purpletv.ui.Nav
import com.purpletv.ui.NavRail
import com.purpletv.ui.Pager
import com.purpletv.ui.RowItem
import com.purpletv.ui.emptyMessage
import com.purpletv.ui.showStreamLanguage
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.IOException

/** Pinned, Twitch-followed and ghost-followed streams appear before the public list. */
class CategoryActivity : Activity() {
    private val scope = MainScope()
    private val app get() = PurpleApp.instance
    private lateinit var pager: Pager<Stream>
    private lateinit var results: ContentGrid
    private lateinit var adapter: GridAdapter
    private lateinit var languageControl: TextView
    private lateinit var updateStreams: (List<Stream>, Boolean) -> Unit
    private val categoryId get() = intent.getStringExtra(EXTRA_ID).orEmpty()
    private var language = app.streamPrefs.language.value
    private var account: String? = null
    private var followedStreams = emptyList<Stream>()
    private var followRevision: Long? = null
    private var followedLoadFailed = false
    private var ghostStreams = emptyList<Stream>()
    private var ghostRevision: Long? = null
    private var ghostLoadedAt = 0L
    private var ghostLoadFailed = false
    private var followUpdates: Job? = null
    private var avatarLoad: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category)
        val helix = app.helix
        val avatars = AvatarCache(helix)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val followView = findViewById<View>(R.id.category_follow)
        val pinView = findViewById<TextView>(R.id.category_pin)
        FollowButton(followView, scope, app.follows, { app.tokens.userId }, ::requireLogin,
            pinButton = PinButton(pinView, followView, app.pins) { app.tokens.userId })
            .bind(FollowKind.CATEGORY, categoryId, name)
        findViewById<TextView>(R.id.category_name).text = name
        findViewById<View>(R.id.category_art_frame).clipToOutline = true
        intent.getStringExtra(EXTRA_ART)?.takeIf { it.isNotEmpty() }?.let {
            findViewById<ImageView>(R.id.category_art).load(sized(it, ART_W, ART_H))
            // A tiny copy stretched over the top reads as a blur, which Android 9 cannot draw.
            findViewById<ImageView>(R.id.category_backdrop).load(sized(it, BLUR_W, BLUR_H))
        }
        // The rail as on the main screens; picking an item goes back to them.
        val rail = NavRail(findViewById(R.id.nav_rail)) { screen ->
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SCREEN, screen.name)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
        }
        rail.select(intent.getStringExtra(EXTRA_ORIGIN)?.let(::screenFrom) ?: Screen.BROWSE)
        PurpleApp.instance.snapshot.meAvatar?.let(rail::setAvatar)

        languageControl = findViewById(R.id.category_language)
        languageControl.showStreamLanguage(language)
        languageControl.setOnClickListener {
            app.streamPrefs.toggleLanguage()
            refreshLanguage()
        }
        results = ContentGrid(findViewById(R.id.category_results), R.string.category_empty, { languageControl }) {
            if (followedLoadFailed || ghostLoadFailed) observeFollowChanges()
            if (pager.hasFailed) pager.loadMore()
        }
        val grid = results.grid
        adapter = GridAdapter(COLUMNS, { item ->
            if (item is RowItem.StreamItem) {
                val zap = ZapList(name, adapter.currentList.filterIsInstance<RowItem.StreamItem>().map { it.stream })
                Nav.openStream(this, item.stream.login, item.stream, adapter.avatars[item.stream.userId], zap)
            }
        }) { if (pager.hasLoaded && !pager.hasFailed) pager.loadMore() }
        adapter.attachTo(grid)
        results.loading()

        var first = true
        updateStreams = { topStreams, failed ->
            val items = orderCategoryStreams(topStreams, followedStreams, categoryId,
                app.pins.list(account, FollowKind.CHANNEL), language, ghostStreams, app.ghostFollows.ids(account).toSet())
            results.show(items.size, failed || followedLoadFailed || ghostLoadFailed, language.emptyMessage(R.string.category_empty))
            adapter.submitList(items.map { RowItem.StreamItem(it) }) {
                if (first && items.isNotEmpty()) {
                    first = false
                    grid.post {
                        if (!followView.hasFocus() && !pinView.hasFocus() && !languageControl.hasFocus()) {
                            grid.layoutManager?.findViewByPosition(0)?.requestFocus()
                        }
                    }
                }
            }
            avatarLoad?.cancel()
            avatarLoad = scope.launch {
                runCatching { avatars.urls(items.map { it.userId }) }.getOrNull()?.let { adapter.avatars = it }
            }
        }
        pager = Pager(scope, key = Stream::userId, fetch = { cursor ->
            helix.topStreams(gameId = categoryId, cursor = cursor, language = language)
        }, onAuthRequired = ::requireLogin, onPage = updateStreams)
    }

    private fun refreshLanguage() {
        val selection = app.streamPrefs.language.value
        if (language == selection) return
        language = selection
        languageControl.showStreamLanguage(selection)
        resetStreams()
        if (followRevision != null || followedLoadFailed) pager.loadMore()
    }

    private fun resetStreams() {
        pager.reset()
        avatarLoad?.cancel()
        adapter.submitList(null)
        results.grid.scrollToPosition(0)
        results.loading()
    }

    override fun onStart() {
        super.onStart()
        if (account != app.tokens.userId) {
            account = app.tokens.userId
            followedStreams = emptyList()
            followRevision = null
            followedLoadFailed = false
            ghostStreams = emptyList()
            ghostRevision = null
            ghostLoadedAt = 0L
            ghostLoadFailed = false
            resetStreams()
        }
        refreshLanguage()
        observeFollowChanges()
    }

    private fun observeFollowChanges() {
        followUpdates?.cancel()
        followUpdates = scope.launch {
            app.followChanges.revisions.collectLatest { revision ->
                val owner = account
                if (revision != followRevision) {
                    try {
                        val streams = if (owner == null) emptyList() else app.helix.followedStreams(owner)
                        ensureActive()
                        if (app.tokens.userId != owner) return@collectLatest
                        followedStreams = streams
                        followRevision = revision
                        followedLoadFailed = false
                    } catch (_: NotLoggedInException) {
                        requireLogin()
                        return@collectLatest
                    } catch (_: IOException) {
                        // Keep the public list usable and let the viewer retry the followed feed.
                        followedLoadFailed = true
                    }
                }
                val localRevision = app.ghostFollows.revision
                if (localRevision != ghostRevision || homeIsStale(ghostLoadedAt, SystemClock.elapsedRealtime())) {
                    try {
                        val streams = app.helix.streamsByUserIds(app.ghostFollows.ids(owner))
                        ensureActive()
                        if (app.tokens.userId != owner) return@collectLatest
                        ghostStreams = streams
                        ghostRevision = localRevision
                        ghostLoadedAt = SystemClock.elapsedRealtime()
                        ghostLoadFailed = false
                    } catch (_: NotLoggedInException) {
                        requireLogin()
                        return@collectLatest
                    } catch (_: IOException) {
                        ghostLoadFailed = true
                    }
                }
                if (pager.hasLoaded) updateStreams(pager.currentItems, pager.hasFailed)
                else pager.loadMore()
            }
        }
    }

    override fun onStop() {
        followUpdates?.cancel()
        super.onStop()
    }

    private fun requireLogin() {
        app.tokens.clearLogin()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "category_id"
        const val EXTRA_NAME = "category_name"
        const val EXTRA_ART = "category_art"
        const val EXTRA_ORIGIN = "category_origin"
        private const val COLUMNS = 4
        private const val ART_W = 285
        private const val ART_H = 380
        private const val BLUR_W = 24
        private const val BLUR_H = 32
    }
}
