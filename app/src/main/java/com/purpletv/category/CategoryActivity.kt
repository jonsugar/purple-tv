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
import com.purpletv.twitch.sized
import com.purpletv.twitch.FollowKind
import com.purpletv.ui.FollowButton
import com.purpletv.ui.AvatarCache
import com.purpletv.ui.GridAdapter
import com.purpletv.ui.Nav
import com.purpletv.ui.NavRail
import com.purpletv.ui.Pager
import com.purpletv.ui.RowItem
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The live streams of one category, most watched first, paged as the grid scrolls. */
class CategoryActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category)
        val helix = PurpleApp.instance.helix
        val avatars = AvatarCache(helix)
        val categoryId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val app = PurpleApp.instance
        FollowButton(findViewById(R.id.category_follow), scope, app.follows, { app.tokens.userId }, {
            app.tokens.clearLogin()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }).bind(FollowKind.CATEGORY, categoryId)
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
        lateinit var pager: Pager<com.purpletv.twitch.Stream>
        lateinit var adapter: GridAdapter
        adapter = GridAdapter(COLUMNS, { item ->
            if (item is RowItem.StreamItem) {
                val zap = ZapList(name, adapter.currentList.filterIsInstance<RowItem.StreamItem>().map { it.stream })
                Nav.openStream(this, item.stream.login, item.stream, adapter.avatars[item.stream.userId], zap)
            }
        }) { pager.loadMore() }
        adapter.attachTo(grid)

        var first = true
        pager = Pager(scope, key = { it.userId }, fetch = { helix.topStreams(gameId = categoryId, cursor = it) }) { items, failed ->
            adapter.submitList(items.map { RowItem.StreamItem(it) })
            status.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            status.setText(if (failed) R.string.row_error else R.string.category_empty)
            if (first && items.isNotEmpty()) {
                first = false
                grid.post { grid.layoutManager?.findViewByPosition(0)?.requestFocus() }
            }
            scope.launch { runCatching { avatars.urls(items.map { it.userId }) }.getOrNull()?.let { adapter.avatars = it } }
        }
        pager.loadMore()
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
