package com.purpletv.home

import android.content.Context
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.purpletv.R
import com.purpletv.PurpleApp
import com.purpletv.player.ZapList
import com.purpletv.twitch.FollowedChannel
import com.purpletv.twitch.FollowingApi
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.NotLoggedInException
import com.purpletv.twitch.Stream
import com.purpletv.twitch.Category
import com.purpletv.twitch.collectPages
import com.purpletv.ui.AvatarCache
import com.purpletv.ui.ContentGrid
import com.purpletv.ui.FollowedChannelsAdapter
import com.purpletv.ui.GridAdapter
import com.purpletv.ui.Nav
import com.purpletv.ui.Pager
import com.purpletv.ui.RowItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException

/** Three independent grids retain their layout managers, scroll position and loaded cards. */
class FollowingPage(
    private val context: Context,
    private val scope: CoroutineScope,
    private val helix: HelixClient,
    private val api: FollowingApi,
    private val avatars: AvatarCache,
    private val onAuthRequired: () -> Unit,
    private val onTabSelected: (Int) -> Unit = {},
) {
    val view: View = LayoutInflater.from(context).inflate(R.layout.page_following, null)
    private val body: FrameLayout = view.findViewById(R.id.following_body)
    private val tabs = listOf(R.id.following_live, R.id.following_categories, R.id.following_channels).map { view.findViewById<TextView>(it) }
    private var selected = 0
    private var account: String? = null
    private var channelJob: Job? = null
    private var profileJob: Job? = null
    private var liveStatusJob: Job? = null
    private var channels = emptyList<FollowedChannel>()
    private val profileIds = mutableSetOf<String>()
    private var profilesWanted = 0
    private var profilesFailed = false
    private var channelsLoaded = false
    private var channelListFailed = false
    private var liveStatusFailed = false
    private var liveLoadedAt = 0L
    private var categoriesLoadedAt = 0L
    private var channelsLoadedAt = 0L
    private var followRevision = PurpleApp.instance.followChanges.revision

    private val livePage: ContentGrid = ContentGrid(context, R.string.followed_empty) { live.loadMore() }
    private val categoryPage: ContentGrid = ContentGrid(context, R.string.following_categories_empty) { categories.loadMore() }
    private val channelPage: ContentGrid = ContentGrid(context, R.string.following_channels_empty) {
        if (!channelsLoaded || channelListFailed) loadChannels() else {
            if (profilesFailed) { profilesFailed = false; ensureProfiles(profilesWanted) }
            if (liveStatusFailed) refreshChannelLive()
        }
    }
    private val pages get() = listOf(livePage, categoryPage, channelPage)

    private val liveAdapter: GridAdapter = GridAdapter(4, { item ->
        if (item is RowItem.StreamItem) Nav.openStream(context, item.stream.login, item.stream, liveAdapterAvatar(item.stream),
            ZapList(context.getString(R.string.row_followed), live.currentItems))
    }) { if (!live.hasFailed) live.loadMore() }
    private fun liveAdapterAvatar(stream: Stream) = liveAdapter.avatars[stream.userId]
    private val categoryAdapter: GridAdapter = GridAdapter(7, { if (it is RowItem.CategoryItem) Nav.openCategory(context, it.category, Screen.FOLLOWING) }) {
        if (!categories.hasFailed) categories.loadMore()
    }
    private val channelAdapter = FollowedChannelsAdapter({ Nav.openChannel(context, it) }, ::ensureProfiles)

    private val live: Pager<Stream> = Pager(scope, { it.userId }, { helix.followedStreamsPage(account ?: throw NotLoggedInException(), it) }, onAuthRequired) { items, failed ->
        liveAdapter.submitList(items.map { RowItem.StreamItem(it) })
        livePage.show(items.size, failed)
        if (!failed) liveLoadedAt = SystemClock.elapsedRealtime()
        val requestedAccount = account
        scope.launch {
            try {
                val urls = avatars.urls(items.map { it.userId })
                if (account == requestedAccount) liveAdapter.avatars = urls
            } catch (_: IOException) { /* A missing avatar must not hide a stream. */ }
        }
    }
    private val categories: Pager<Category> = Pager(scope, { it.id }, { com.purpletv.twitch.Page(api.liveCategories(), null) }, onAuthRequired) { items, failed ->
        categoryAdapter.submitList(items.map { RowItem.CategoryItem(it) })
        categoryPage.show(items.size, failed)
        if (!failed) categoriesLoadedAt = SystemClock.elapsedRealtime()
    }

    init {
        liveAdapter.attachTo(livePage.grid)
        categoryAdapter.attachTo(categoryPage.grid)
        channelAdapter.attachTo(channelPage.grid)
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { select(index) }
            tab.nextFocusLeftId = if (index == 0) R.id.nav_following else tabs[index - 1].id
            tab.nextFocusRightId = tabs[minOf(index + 1, tabs.lastIndex)].id
        }
        pages.forEach { it.loading() }
        select(0)
    }

    fun bindAccount(id: String?) {
        if (account == id) return
        account = id
        live.reset()
        categories.reset()
        channelJob?.cancel()
        profileJob?.cancel()
        liveStatusJob?.cancel()
        profileIds.clear()
        profilesWanted = 0
        profilesFailed = false
        channelsLoaded = false
        channelListFailed = false
        liveStatusFailed = false
        liveLoadedAt = 0L
        categoriesLoadedAt = 0L
        channelsLoadedAt = 0L
        channels = emptyList()
        liveAdapter.submitList(emptyList())
        liveAdapter.avatars = emptyMap()
        categoryAdapter.submitList(emptyList())
        channelAdapter.submitList(emptyList())
        pages.forEach { it.grid.scrollToPosition(0); it.loading() }
        select(0)
    }

    fun show() {
        val revision = PurpleApp.instance.followChanges.revision
        if (revision != followRevision) {
            followRevision = revision
            live.reset()
            categories.reset()
            channelJob?.cancel()
            liveStatusJob?.cancel()
            liveLoadedAt = 0L
            categoriesLoadedAt = 0L
            channelsLoadedAt = 0L
        }
        loadSelected()
    }

    private fun select(index: Int) {
        selected = index
        onTabSelected(tabs[index].id)
        tabs.forEachIndexed { i, tab -> tab.isSelected = i == index }
        view.findViewById<TextView>(R.id.following_heading).setText(when (index) {
            0 -> R.string.row_followed
            1 -> R.string.following_live_categories
            else -> R.string.following_followed_channels
        })
        body.removeAllViews()
        body.addView(pages[index].view)
        loadSelected()
    }

    private fun loadSelected() {
        if (account == null) return
        val now = SystemClock.elapsedRealtime()
        when (selected) {
            0 -> {
                if (live.hasLoaded && liveLoadedAt > 0 && now - liveLoadedAt >= HOME_MAX_AGE_MS) live.reset()
                if (!live.hasLoaded) live.loadMore()
            }
            1 -> {
                if (categories.hasLoaded && categoriesLoadedAt > 0 && now - categoriesLoadedAt >= HOME_MAX_AGE_MS) categories.reset()
                if (!categories.hasLoaded) categories.loadMore()
            }
            2 -> if (!channelsLoaded || homeIsStale(channelsLoadedAt, now)) loadChannels()
        }
    }

    private fun loadChannels() {
        val id = account ?: return
        if (channelJob?.isActive == true) return
        if (channels.isEmpty()) channelPage.loading()
        channelJob = scope.launch {
            try {
                val followed = helix.allFollowedChannels(id)
                ensureActive()
                if (id != account) return@launch
                val existing = channels.associateBy { it.id }
                channels = followed.map { channel -> existing[channel.id]?.let {
                    channel.copy(avatarUrl = it.avatarUrl, backgroundUrl = it.backgroundUrl, live = it.live)
                } ?: channel }
                channelsLoaded = true
                channelListFailed = false
                channelsLoadedAt = SystemClock.elapsedRealtime()
                channelAdapter.submitList(channels)
                showChannelStatus()
                ensureProfiles(40)
                refreshChannelLive()
            } catch (e: IOException) {
                channelListFailed = true
                channelPage.show(channels.size, true)
                if (e is NotLoggedInException) onAuthRequired()
            }
        }
    }

    /** Profiles are hydrated in batches as the alphabetical grid scrolls. */
    private fun ensureProfiles(position: Int) {
        profilesWanted = maxOf(profilesWanted, position + 40).coerceAtMost(channels.size)
        if (profileJob?.isActive == true || profilesFailed) return
        val id = account
        profileJob = scope.launch {
            try {
                while (true) {
                    val missing = channels.take(profilesWanted).filter { it.id !in profileIds }.map { it.id }.take(100)
                    if (missing.isEmpty()) break
                    val profiles = helix.users(missing).associateBy { it.id }
                    ensureActive()
                    if (account != id) return@launch
                    profileIds.addAll(missing)
                    channels = channels.map { channel -> profiles[channel.id]?.let {
                        channel.copy(avatarUrl = it.profileImageUrl, backgroundUrl = it.offlineImageUrl)
                    } ?: channel }
                    channelAdapter.submitList(channels)
                }
                showChannelStatus()
            } catch (e: IOException) {
                profilesFailed = true
                channelPage.show(channels.size, true)
                if (e is NotLoggedInException) onAuthRequired()
            }
        }
    }

    private fun showChannelStatus() { channelPage.show(channels.size, channelListFailed || profilesFailed || liveStatusFailed) }

    private fun refreshChannelLive() {
        val id = account ?: return
        if (liveStatusJob?.isActive == true) return
        liveStatusJob = scope.launch {
            try {
                val streams = collectPages(key = { stream: Stream -> stream.userId }) { helix.followedStreamsPage(id, it) }.associateBy { it.userId }
                ensureActive()
                if (account != id) return@launch
                channels = channels.map { it.copy(live = streams[it.id]) }
                liveStatusFailed = false
                channelAdapter.submitList(channels)
                showChannelStatus()
            } catch (e: IOException) {
                liveStatusFailed = true
                showChannelStatus()
                if (e is NotLoggedInException) onAuthRequired()
            }
        }
    }
}
