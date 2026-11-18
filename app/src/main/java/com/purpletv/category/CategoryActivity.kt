package com.purpletv.category

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.purpletv.PurpleApp
import com.purpletv.R
import com.purpletv.home.MainActivity
import com.purpletv.home.Screen
import com.purpletv.home.screenFrom
import com.purpletv.player.ZapList
import com.purpletv.twitch.FollowKind
import com.purpletv.twitch.NotLoggedInException
import com.purpletv.twitch.Stream
import com.purpletv.twitch.collectPages
import com.purpletv.twitch.sized
import com.purpletv.ui.AvatarCache
import com.purpletv.ui.FollowButton
import com.purpletv.ui.PinButton
import com.purpletv.ui.GridAdapter
import com.purpletv.ui.Nav
import com.purpletv.ui.NavRail
import com.purpletv.ui.Pager
import com.purpletv.ui.RowItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.IOException

/** Followed streams first, then the category's most watched streams as the grid scrolls. */
class CategoryActivity : Activity() {
    private val scope = MainScope()
    private val app get() = PurpleApp.instance
    private lateinit var pager: Pager<Stream>
    private lateinit var updateStreams: (List<Stream>, Boolean) -> Unit
    private var followedStreams = emptyList<Stream>()
    private var followRevision: Long? = null
    private var followUpdates: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category)
        val helix = app.helix
        val avatars = AvatarCache(helix)
        val categoryId = intent.getStringExtra(EXTRA_ID).orEmpty()
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

        val grid: RecyclerView = findViewById(R.id.category_grid)
        val status: TextView = findViewById(R.id.category_status)
        lateinit var adapter: GridAdapter
        adapter = GridAdapter(COLUMNS, { item ->
            if (item is RowItem.StreamItem) {
                val zap = ZapList(name, adapter.currentList.filterIsInstance<RowItem.StreamItem>().map { it.stream })
                Nav.openStream(this, item.stream.login, item.stream, adapter.avatars[item.stream.userId], zap)
            }
        }) { pager.loadMore() }
        adapter.attachTo(grid)

        var first = true
        updateStreams = { topStreams, failed ->
            val items = (followedStreams + topStreams).distinctBy { it.userId }
            adapter.submitList(items.map { RowItem.StreamItem(it) })
            status.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            status.setText(if (failed) R.string.row_error else R.string.category_empty)
            if (first && items.isNotEmpty()) {
                first = false
                grid.post {
                    if (!followView.hasFocus() && !pinView.hasFocus()) grid.layoutManager?.findViewByPosition(0)?.requestFocus()
                }
            }
            scope.launch { runCatching { avatars.urls(items.map { it.userId }) }.getOrNull()?.let { adapter.avatars = it } }
        }
        pager = Pager(scope, key = { it.userId }, fetch = { helix.topStreams(gameId = categoryId, cursor = it) },
            onAuthRequired = ::requireLogin, onPage = updateStreams)
    }

    override fun onStart() {
        super.onStart()
        followUpdates = scope.launch {
            app.followChanges.revisions.collectLatest { revision ->
                if (revision == followRevision) return@collectLatest
                val account = app.tokens.userId
                val categoryId = intent.getStringExtra(EXTRA_ID)
                try {
                    val streams = if (account == null) emptyList() else {
                        collectPages(key = Stream::userId) { app.helix.followedStreamsPage(account, it) }
                    }
                    ensureActive()
                    if (app.tokens.userId != account) return@collectLatest
                    followedStreams = streams.filter { it.gameId == categoryId }.sortedByDescending { it.viewers }
                    followRevision = revision
                } catch (_: NotLoggedInException) {
                    requireLogin()
                    return@collectLatest
                } catch (_: IOException) {
                    // The public category streams can still load if the followed list is unavailable.
                    followedStreams = emptyList()
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
