package com.purpletv.home

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.purpletv.R
import com.purpletv.auth.SessionChangedException
import com.purpletv.auth.SessionId
import com.purpletv.player.ZapList
import com.purpletv.twitch.FollowKind
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.HomeApi
import com.purpletv.twitch.NotLoggedInException
import com.purpletv.twitch.Shelf
import com.purpletv.twitch.Stream
import com.purpletv.ui.AvatarCache
import com.purpletv.ui.Nav
import com.purpletv.ui.RowItem
import com.purpletv.ui.RowKind
import com.purpletv.ui.clear
import com.purpletv.ui.viewersText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/**
 * Home like the official TV app, made immersive: the focused card fills the top (the hero), and
 * the rows sit below in one list, followed live channels first, then the rows Twitch's own TV Home
 * has for the user (without those, the most watched streams and categories).
 */
class HomePage(
    private val context: Context,
    private val helix: HelixClient,
    private val homeApi: HomeApi,
    private val avatars: AvatarCache,
    private val snapshot: HomeSnapshot,
    private val pins: PinStore,
    private val streamPrefs: StreamPreferences,
    private val ghostFollows: GhostFollowStore,
) {
    val view: View = LayoutInflater.from(context).inflate(R.layout.page_home, null)

    private val rows: RecyclerView = view.findViewById(R.id.home_rows)
    // Only onCardFocused scrolls the rows: the list's own focus-following scroll would fight it.
    private val rowsLayout = object : LinearLayoutManager(context) {
        override fun requestChildRectangleOnScreen(
            parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
        ) = false
    }
    private val adapter = HomeRowsAdapter(context, ::onCardFocused, ::open)
    private val hero = Hero()
    private var focusedRow = -1
    private var onFirstCard = true
    private var sourceRows = emptyList<HomeRow>()
    private var rowsAccount: String? = null
    private var ghostLive = emptyList<Stream>()
    private var ghostAvatars = emptyMap<String, String>()

    init {
        rows.layoutManager = rowsLayout
        rows.adapter = adapter
        rows.itemAnimator = null
        adapter.submit(skeletonRows())
    }

    fun stop() = hero.cancel()

    /** Loads the rows; only a lost login escapes as [NotLoggedInException]. */
    suspend fun load(userId: String, session: SessionId) = coroutineScope {
        val followedStreams = async { runCatching { helix.followedStreams(userId, session) } }
        val shelves = async { runCatching { loadRecommendations(session) } }
        val followedResult = followedStreams.await()
        (followedResult.exceptionOrNull() as? NotLoggedInException)?.let { throw it }
        val recommendations = shelves.await()
        (recommendations.exceptionOrNull() as? NotLoggedInException)?.let { throw it }
        if (helix.sessionId !== session) throw SessionChangedException()
        val below = recommendations
            .onFailure { Log.w(TAG, "home rows unavailable: ${it.message}") }
            .getOrDefault(emptyList())
        below.forEach {
            Log.i(TAG, "home row ${it.rowName} \"${it.title}\" streams=${it.streams.size} categories=${it.categories.size}")
        }
        val lower = if (below.isNotEmpty()) below.map(::shelfRow) else helixRows(session)
        coroutineContext.ensureActive()
        if (helix.sessionId !== session) throw SessionChangedException()
        val followed = HomeRow(
            context.getString(R.string.row_followed), RowKind.STREAMS,
            followedResult.map { list -> list.map { RowItem.StreamItem(it) } }, emptyText = R.string.followed_empty,
        )
        sourceRows = listOf(followed) + lower
        rowsAccount = userId
        applyStreamPreferences(userId)
        adapter.warmUp(rows)
        focusedRow = -1
        rows.scrollToPosition(0)
        focusFirst()

        // Twitch's rows carry their avatars; the followed row and the fallback ask Helix for them.
        val bareIds = (listOf(followed) + lower).filter { it.avatars.isEmpty() }
            .flatMap { row -> row.items.getOrDefault(emptyList()).mapNotNull { (it as? RowItem.StreamItem)?.stream?.userId } }
        val avatarResult = runCatching { avatars.urls(bareIds, session) }
        (avatarResult.exceptionOrNull() as? NotLoggedInException)?.let { throw it }
        val urls = avatarResult.getOrDefault(emptyMap())
        coroutineContext.ensureActive()
        if (helix.sessionId !== session) throw SessionChangedException()
        if (urls.isNotEmpty()) {
            sourceRows = sourceRows.map { if (it.avatars.isEmpty()) it.copy(avatars = urls) else it }
            adapter.addAvatars(urls)
        }
        hero.refresh()

        snapshot.followedLive = followedResult.getOrDefault(emptyList())
        snapshot.avatars = lower.fold(urls) { all, row -> all + row.avatars }
        snapshot.recommendedCategories = below.firstOrNull { it.categories.isNotEmpty() }?.categories
            ?: lower.firstNotNullOfOrNull { row ->
                row.items.getOrNull()?.filterIsInstance<RowItem.CategoryItem>()?.map { it.category }?.ifEmpty { null }
            }
            ?: emptyList()
    }

    fun showError() {
        sourceRows = emptyList()
        rowsAccount = null
        adapter.submit(listOf(HomeRow(context.getString(R.string.row_followed), RowKind.STREAMS, Result.failure(RuntimeException()))))
    }

    /** The local edit is already saved. This only refreshes live metadata on return to Home. */
    suspend fun refreshGhostLive(account: String) {
        if (rowsAccount != account) return
        applyStreamPreferences(account)
        try {
            val streams = helix.streamsByUserIds(ghostFollows.ids(account))
            currentCoroutineContext().ensureActive()
            if (rowsAccount != account) return
            ghostLive = streams
            applyStreamPreferences(account)
            val urls = avatars.urls(streams.map(Stream::userId))
            currentCoroutineContext().ensureActive()
            if (rowsAccount != account) return
            ghostAvatars = urls
            applyStreamPreferences(account)
        } catch (e: IOException) {
            Log.w(TAG, "could not refresh live ghost follows", e)
        }
    }

    fun applyStreamPreferences(account: String?) {
        if (rowsAccount != account || sourceRows.isEmpty()) return
        val language = streamPrefs.language.value
        val matching = filterLiveRows(sourceRows, language)
        val pinnedRows = withChannelPins(matching, pins.list(account, FollowKind.CHANNEL))
        val ghostIds = ghostFollows.ids(account).toSet()
        val followed = sourceRows.first().items.getOrNull()?.filterIsInstance<RowItem.StreamItem>()?.map { it.stream }
        val ghosts = if (followed == null) emptyList() else ghostOnlyStreams(ghostLive, ghostIds, followed, language)
        val visible = withGhostFollows(pinnedRows, ghosts, ghostIds, context.getString(R.string.ghost_follows_live), ghostAvatars)
        if (visible != adapter.rows) {
            hero.clearMissingCard(visible)
            adapter.submit(visible)
        }
    }

    fun clearPreviousAccount(account: String?) {
        if (rowsAccount == null || rowsAccount == account) return
        rowsAccount = null
        sourceRows = emptyList()
        ghostLive = emptyList()
        ghostAvatars = emptyMap()
        hero.cancel()
        adapter.submit(skeletonRows())
    }

    /** Whether Home is at its start: the first card of its top row focused last. */
    fun isAtTop(): Boolean = homeAtTop(topRow(adapter.rows), focusedRow, onFirstCard)

    /** Brings the top row to the start, where focusing it puts it anyway. */
    fun scrollToTop() {
        val top = topRow(adapter.rows).coerceAtLeast(0)
        rowsLayout.scrollToPositionWithOffset(top, 0)
        focusedRow = top
    }

    /** Focuses the first card of the top row. */
    fun focusFirst() {
        val index = topRow(adapter.rows)
        if (index >= 0) adapter.focusFirstCard(index)
    }

    private fun onCardFocused(row: Int, item: RowItem) {
        onFirstCard = adapter.rows.getOrNull(row)?.items?.getOrNull()?.firstOrNull() == item
        if (row >= 0 && row != focusedRow) {
            focusedRow = row
            // The focused row slides up under the hero.
            val scroller = object : LinearSmoothScroller(context) {
                override fun getVerticalSnapPreference() = SNAP_TO_START
            }
            scroller.targetPosition = row
            rowsLayout.startSmoothScroll(scroller)
        }
        hero.schedule(item)
    }

    private fun open(item: RowItem) {
        when (item) {
            is RowItem.StreamItem -> Nav.openStream(context, item.stream.login, item.stream, avatarOf(item.stream.userId), zapListFor(item))
            is RowItem.CategoryItem -> Nav.openCategory(context, item.category)
            is RowItem.ChannelItem -> Nav.openStream(context, item.channel.login)
            RowItem.Skeleton -> Unit
        }
    }

    private fun avatarOf(userId: String): String? = adapter.rows.firstNotNullOfOrNull { it.avatars[userId] }

    /** The streams of the card's own row, for the player's Channels strip. */
    private fun zapListFor(item: RowItem): ZapList? {
        val row = adapter.rows.firstOrNull { it.items.getOrNull()?.contains(item) == true } ?: return null
        return ZapList(row.title, row.items.getOrNull().orEmpty().filterIsInstance<RowItem.StreamItem>().map { it.stream })
    }

    private fun skeletonRows(): List<HomeRow> = listOf(
        HomeRow(context.getString(R.string.row_followed), RowKind.STREAMS, Result.success(List(SKELETON_CARDS) { RowItem.Skeleton })),
        HomeRow("", RowKind.STREAMS, Result.success(List(SKELETON_CARDS) { RowItem.Skeleton })),
    )

    private fun shelfRow(shelf: Shelf): HomeRow =
        if (shelf.streams.isNotEmpty()) HomeRow(shelf.title, RowKind.STREAMS, Result.success(shelf.streams.map { RowItem.StreamItem(it) }), shelf.avatars)
        else HomeRow(shelf.title, RowKind.CATEGORIES, Result.success(shelf.categories.map { RowItem.CategoryItem(it) }))

    private suspend fun loadRecommendations(session: SessionId): List<Shelf> {
        val shelves = rowsBelowFollowed(homeApi.shelves(session))
        val ids = shelves.flatMap { shelf -> shelf.streams.map(Stream::userId) }.filter(String::isNotEmpty)
        val metadata = helix.streamsByUserIds(ids, session).associateBy(Stream::userId)
        return shelves.map { shelf ->
            shelf.copy(streams = shelf.streams.map { it.copy(language = metadata[it.userId]?.language.orEmpty()) })
        }
    }

    /** The rows Home had before Twitch's own: the most watched streams and categories. */
    private suspend fun helixRows(session: SessionId): List<HomeRow> = coroutineScope {
        val topStreams = async { runCatching { helix.topStreams(session = session).items } }
        val topCategories = async { runCatching { helix.topCategories(session = session).items } }
        listOf(topStreams.await(), topCategories.await())
            .firstNotNullOfOrNull { it.exceptionOrNull() as? NotLoggedInException }?.let { throw it }
        listOf(
            HomeRow(context.getString(R.string.row_top), RowKind.STREAMS, topStreams.await().map { list -> list.map { RowItem.StreamItem(it) } }),
            HomeRow(context.getString(R.string.row_categories), RowKind.CATEGORIES, topCategories.await().map { list -> list.map { RowItem.CategoryItem(it) } }),
        )
    }

    /** The top of Home: what the focused card is, large, over its own picture. */
    private inner class Hero {
        private val a: ImageView = view.findViewById(R.id.hero_backdrop_a)
        private val b: ImageView = view.findViewById(R.id.hero_backdrop_b)
        private val live: View = view.findViewById(R.id.hero_live)
        private val uptime: TextView = view.findViewById(R.id.hero_uptime)
        private val title: TextView = view.findViewById(R.id.hero_title)
        private val meta: View = view.findViewById(R.id.hero_meta)
        private val avatar: ImageView = view.findViewById(R.id.hero_avatar)
        private val name: TextView = view.findViewById(R.id.hero_name)
        private val game: TextView = view.findViewById(R.id.hero_game)
        private val viewers: TextView = view.findViewById(R.id.hero_viewers)
        private val block: View = view.findViewById(R.id.hero_block)
        private val handler = Handler(Looper.getMainLooper())
        private var pending: Runnable? = null
        private var front = a
        private var backdrop: String? = null
        private var shown: RowItem? = null

        init {
            avatar.clipToOutline = true
        }

        /** Shows [item] once the focus has rested on it, so passing cards load nothing. */
        fun schedule(item: RowItem) {
            cancel()
            pending = Runnable { show(item) }.also { handler.postDelayed(it, SETTLE_MS) }
        }

        fun cancel() {
            pending?.let(handler::removeCallbacks)
            pending = null
        }

        /** Shows the current card again, for avatars that arrived after it. */
        fun refresh() {
            shown?.let(::show)
        }

        fun clearMissingCard(rows: List<HomeRow>) {
            cancel()
            val item = shown ?: return
            if (rows.none { item in it.items.getOrNull().orEmpty() }) {
                shown = null
                block.visibility = View.INVISIBLE
                a.animate().cancel()
                b.animate().cancel()
                a.visibility = View.INVISIBLE
                b.visibility = View.INVISIBLE
                a.clear()
                b.clear()
                backdrop = null
            }
        }

        private fun show(item: RowItem) {
            val info = when (item) {
                is RowItem.StreamItem -> heroFor(item.stream, avatarOf(item.stream.userId), System.currentTimeMillis())
                is RowItem.CategoryItem -> heroFor(item.category)
                else -> return
            }
            shown = item
            block.visibility = View.VISIBLE
            Log.d(TAG, "hero ${info.name.ifEmpty { info.title }}")
            title.text = info.title
            live.visibility = if (info.live) View.VISIBLE else View.GONE
            meta.visibility = if (info.live) View.VISIBLE else View.GONE
            uptime.text = when {
                !info.live -> context.getString(R.string.hero_category)
                info.uptime.isEmpty() -> context.getString(R.string.hero_live)
                else -> context.getString(R.string.hero_live_for, info.uptime)
            }
            name.text = info.name
            game.text = info.game
            viewers.text = info.viewers?.let { context.viewersText(it) }.orEmpty()
            if (info.avatar.isNullOrEmpty()) avatar.clear()
            else avatar.load(info.avatar) { transformations(CircleCropTransformation()) }
            crossfadeTo(info.backdrop)
        }

        private fun crossfadeTo(url: String) {
            if (url == backdrop) return
            backdrop = url
            val next = if (front === a) b else a
            val old = front
            next.animate().cancel()
            next.alpha = 0f
            next.load(url) {
                crossfade(false) // the two views fade into each other themselves
                listener(onSuccess = { _, _ ->
                    if (backdrop == url) {
                        next.visibility = View.VISIBLE
                        next.animate().alpha(1f).setDuration(FADE_MS).start()
                        // A view at alpha 0 is still drawn: the covered one leaves the GPU's work.
                        old.animate().alpha(0f).setDuration(FADE_MS)
                            .withEndAction { if (old !== front) old.visibility = View.INVISIBLE }.start()
                        front = next
                    }
                })
            }
        }
    }

    private companion object {
        const val SETTLE_MS = 250L
        const val FADE_MS = 250L
        const val SKELETON_CARDS = 4
        const val TAG = "PurpleTV"
    }
}
