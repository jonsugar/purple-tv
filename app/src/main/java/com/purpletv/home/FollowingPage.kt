package com.purpletv.home

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.purpletv.R
import com.purpletv.PurpleApp
import com.purpletv.player.ZapList
import com.purpletv.twitch.FollowKind
import com.purpletv.twitch.FollowedChannel
import com.purpletv.twitch.FollowingApi
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.NotLoggedInException
import com.purpletv.twitch.Stream
import com.purpletv.twitch.Category
import com.purpletv.twitch.Page
import com.purpletv.ui.AvatarCache
import com.purpletv.ui.ContentGrid
import com.purpletv.ui.FollowedChannelsAdapter
import com.purpletv.ui.GridAdapter
import com.purpletv.ui.Nav
import com.purpletv.ui.Pager
import com.purpletv.ui.RowAdapter
import com.purpletv.ui.RowItem
import com.purpletv.ui.RowKind
import com.purpletv.ui.emptyMessage
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
    private val pins: PinStore,
    private val streamPrefs: StreamPreferences,
    private val onAuthRequired: () -> Unit,
    private val onTabSelected: (Int) -> Unit = {},
) {
    val view: View = LayoutInflater.from(context).inflate(R.layout.page_following, null)
    private val body: FrameLayout = view.findViewById(R.id.following_body)
    private val tabs = listOf(R.id.following_live, R.id.following_categories, R.id.following_channels).map { view.findViewById<TextView>(it) }
    private val reorder: View = view.findViewById(R.id.following_reorder)
    private var selected = 0
    private var account: String? = null
    private var channelJob: Job? = null
    private var profileJob: Job? = null
    private var liveStatusJob: Job? = null
    private var channels = emptyList<FollowedChannel>()
    private var liveItems = emptyList<Stream>()
    private var categoryItems = emptyList<Category>()
    private var orderedLive = emptyList<Stream>()
    private var openedItem: Pair<FollowKind, String>? = null
    private var channelMove: ChannelMove? = null
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

    private val liveAdapter = GridAdapter(4, ::openStream) {}
    private val pinnedLive = RowAdapter(::openStream)
    private val categoryAdapter = GridAdapter(7, ::openCategory) {}
    private val pinnedCategories = RowAdapter(::openCategory)
    private val channelAdapter = FollowedChannelsAdapter(::openChannel, ::ensureProfiles)
    private val pinnedChannels = FollowedChannelsAdapter(::openPinnedChannel, {}, horizontal = true, onLongClick = ::startPinMove)
    private val liveSections: FollowingSections
    private val categorySections: FollowingSections
    private val channelSections: FollowingSections

    private val live: Pager<Stream> = Pager(scope, Stream::userId, {
        Page(helix.followedStreams(account ?: throw NotLoggedInException()), null)
    }, onAuthRequired) { items, failed ->
        liveItems = items
        renderLive()
        if (!failed) liveLoadedAt = SystemClock.elapsedRealtime()
        val requestedAccount = account
        scope.launch {
            try {
                val urls = avatars.urls(items.map { it.userId })
                if (account == requestedAccount) {
                    liveAdapter.avatars = urls
                    pinnedLive.avatars = urls
                }
            } catch (_: IOException) { /* A missing avatar must not hide a stream. */ }
        }
    }
    private val categories: Pager<Category> = Pager(scope, Category::id, { Page(api.liveCategories(), null) }, onAuthRequired) { items, failed ->
        categoryItems = items
        renderCategories()
        categoryPage.show(items.size, failed)
        if (!failed) categoriesLoadedAt = SystemClock.elapsedRealtime()
    }

    init {
        liveAdapter.attachTo(livePage.grid)
        categoryAdapter.attachTo(categoryPage.grid)
        channelAdapter.attachTo(channelPage.grid)
        liveSections = FollowingSections(context, livePage.grid, R.string.row_followed, RowKind.STREAMS, pinnedLive)
        categorySections = FollowingSections(context, categoryPage.grid, R.string.following_live_categories, RowKind.CATEGORIES, pinnedCategories)
        channelSections = FollowingSections(context, channelPage.grid, R.string.following_followed_channels, RowKind.CHANNELS, pinnedChannels)
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { select(index) }
            tab.nextFocusLeftId = if (index == 0) R.id.nav_following else tabs[index - 1].id
            tab.nextFocusRightId = tabs[minOf(index + 1, tabs.lastIndex)].id
        }
        reorder.nextFocusLeftId = tabs.last().id
        reorder.nextFocusRightId = reorder.id
        reorder.setOnClickListener {
            val owner = account ?: return@setOnClickListener
            showReorderPins(reorder, pins, owner, FollowKind.CATEGORY)
        }
        pages.forEach { it.loading() }
        select(0)
    }

    fun bindAccount(id: String?) {
        if (account == id) return
        cancelPinMove(restoreFocus = false)
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
        liveItems = emptyList()
        categoryItems = emptyList()
        orderedLive = emptyList()
        openedItem = null
        liveAdapter.submitList(null)
        pinnedLive.submitList(null)
        liveAdapter.avatars = emptyMap()
        pinnedLive.avatars = emptyMap()
        categoryAdapter.submitList(null)
        pinnedCategories.submitList(null)
        channelAdapter.submitList(null)
        pinnedChannels.submitList(null)
        applyPins()
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
        cancelPinMove(restoreFocus = false)
        selected = index
        onTabSelected(tabs[index].id)
        tabs.forEachIndexed { i, tab -> tab.isSelected = i == index }
        updateReorder()
        body.removeAllViews()
        body.addView(pages[index].view)
        loadSelected()
    }

    fun onReturn(pinsChanged: Boolean, followChanged: Boolean) {
        val item = openedItem
        openedItem = null
        if (pinsChanged) applyPins(if (followChanged) null else item)
    }

    fun applyPins(restoreItem: Pair<FollowKind, String>? = null) {
        updateReorder()
        renderLive()
        renderCategories(restoreItem?.takeIf { it.first == FollowKind.CATEGORY }?.second)
        renderChannels(restoreItem?.takeIf { it.first == FollowKind.CHANNEL }?.second)
        if (channelsLoaded) ensureProfiles(profilesWanted)
    }

    private fun updateReorder() {
        val visible = selected == 1 && pins.list(account, FollowKind.CATEGORY).isNotEmpty()
        val hadFocus = reorder.hasFocus()
        reorder.visibility = if (visible) View.VISIBLE else View.GONE
        tabs.last().nextFocusRightId = if (visible) reorder.id else tabs.last().id
        if (hadFocus && !visible) tabs[selected].requestFocus()
    }

    private fun openStream(item: RowItem) {
        if (item !is RowItem.StreamItem) return
        val stream = item.stream
        Nav.openStream(context, stream.login, stream, liveAdapter.avatars[stream.userId],
            ZapList(context.getString(R.string.row_followed), orderedLive))
    }

    private fun openCategory(item: RowItem) {
        if (item !is RowItem.CategoryItem) return
        openedItem = FollowKind.CATEGORY to item.category.id
        Nav.openCategory(context, item.category, Screen.FOLLOWING)
    }

    private fun openChannel(channel: FollowedChannel) {
        openedItem = FollowKind.CHANNEL to channel.id
        Nav.openChannel(context, channel)
    }

    private fun openPinnedChannel(channel: FollowedChannel) {
        if (channelMove == null) openChannel(channel)
    }

    private fun startPinMove(channel: FollowedChannel): Boolean {
        val owner = account ?: return false
        if (channelMove != null || pinnedChannels.itemCount < 2) return false
        channelMove = ChannelMove(owner, channel.id)
        channelSections.setMovingChannel(channel.displayName)
        return true
    }

    fun handlePinMoveKey(event: KeyEvent): Boolean {
        val move = channelMove ?: return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val left = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
                    shiftPin(move, if (left != rtl) -1 else 1)
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Let the View finish the long press that entered move mode.
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) move.confirmPressed = true
                if (!move.confirmPressed) return false
                if (event.action == KeyEvent.ACTION_UP) {
                    move.confirmPressed = false
                    if (!event.isCanceled) finishPinMove(move, save = true, restoreFocus = true)
                }
            }
            else -> return false
        }
        return true
    }

    private fun shiftPin(move: ChannelMove, direction: Int) {
        val (displayed) = channelGroups()
        val index = displayed.indexOfFirst { it.id == move.id }
        if (index < 0) return
        val neighbour = displayed.getOrNull(index + direction) ?: return
        val order = channelPins()
        move.offset += order.indexOfFirst { it.id == neighbour.id } - order.indexOfFirst { it.id == move.id }
        renderChannels()
    }

    fun cancelPinMove(restoreFocus: Boolean = true): Boolean {
        val move = channelMove ?: return false
        finishPinMove(move, save = false, restoreFocus)
        return true
    }

    private fun finishPinMove(move: ChannelMove, save: Boolean, restoreFocus: Boolean) {
        channelMove = null
        channelSections.setMovingChannel(null)
        if (save) pins.move(move.account, FollowKind.CHANNEL, move.id, move.offset)
        renderChannels(focusPin = move.id.takeIf { restoreFocus })
    }

    private fun renderLive() {
        val language = streamPrefs.language.value
        val (pinned, regular) = splitByPins(language.filter(liveItems), pins.list(account, FollowKind.CHANNEL), Stream::userId)
        orderedLive = pinned + regular
        pinnedLive.submitList(pinned.map { RowItem.StreamItem(it) })
        liveAdapter.submitList(regular.map { RowItem.StreamItem(it) })
        liveSections.show(pinned.size, regular.size)
        if (live.hasLoaded) livePage.show(orderedLive.size, live.hasFailed, language.emptyMessage(R.string.followed_empty))
    }

    fun refreshLanguage() = renderLive()

    private fun renderCategories(restore: String? = null) {
        val (pinned, regular) = splitByPins(categoryItems, pins.list(account, FollowKind.CATEGORY), Category::id)
        categorySections.show(pinned.size, regular.size)
        val movedToPins = restore != null && pinnedCategories.currentList.none { (it as RowItem.CategoryItem).category.id == restore }
        val movedToRegular = restore != null && categoryAdapter.currentList.none { (it as RowItem.CategoryItem).category.id == restore }
        pinnedCategories.submitList(pinned.map { RowItem.CategoryItem(it) }) {
            if (movedToPins) pinned.indexOfFirst { it.id == restore }.takeIf { it >= 0 }?.let(categorySections::focusPinned)
        }
        categoryAdapter.submitList(regular.map { RowItem.CategoryItem(it) }) {
            if (movedToRegular) regular.indexOfFirst { it.id == restore }.takeIf { it >= 0 }?.let(categorySections::focusRegular)
        }
    }

    private fun channelPins(): List<FavouritePin> {
        val saved = pins.list(account, FollowKind.CHANNEL)
        val move = channelMove ?: return saved
        return saved.toMutableList().apply { movePin(move.id, move.offset) }
    }

    private fun channelGroups() = splitByPins(channels, channelPins(), FollowedChannel::id)

    private fun renderChannels(restore: String? = null, focusPin: String? = channelMove?.id) {
        val (pinned, regular) = channelGroups()
        channelSections.show(pinned.size, regular.size)
        val movedToPins = restore != null && pinnedChannels.currentList.none { it.id == restore }
        val movedToRegular = restore != null && channelAdapter.currentList.none { it.id == restore }
        pinnedChannels.submitList(pinned) {
            val focusId = focusPin ?: restore.takeIf { movedToPins }
            if (focusId != null) pinned.indexOfFirst { it.id == focusId }.takeIf { it >= 0 }?.let(channelSections::focusPinned)
        }
        channelAdapter.submitList(regular) {
            if (movedToRegular) regular.indexOfFirst { it.id == restore }.takeIf { it >= 0 }?.let(channelSections::focusRegular)
        }
    }

    private data class ChannelMove(
        val account: String,
        val id: String,
        var offset: Int = 0,
        var confirmPressed: Boolean = false,
    )

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
                renderChannels()
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
                    val (pinned, regular) = channelGroups()
                    val missing = (pinned + regular.take(profilesWanted)).asSequence()
                        .map { it.id }.filter { it !in profileIds }.take(100).toList()
                    if (missing.isEmpty()) break
                    val profiles = helix.users(missing).associateBy { it.id }
                    ensureActive()
                    if (account != id) return@launch
                    profileIds.addAll(missing)
                    channels = channels.map { channel -> profiles[channel.id]?.let {
                        channel.copy(avatarUrl = it.profileImageUrl, backgroundUrl = it.offlineImageUrl)
                    } ?: channel }
                    renderChannels()
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
                val streams = helix.followedStreams(id).associateBy { it.userId }
                ensureActive()
                if (account != id) return@launch
                channels = channels.map { it.copy(live = streams[it.id]) }
                liveStatusFailed = false
                renderChannels()
                showChannelStatus()
            } catch (e: IOException) {
                liveStatusFailed = true
                showChannelStatus()
                if (e is NotLoggedInException) onAuthRequired()
            }
        }
    }
}
