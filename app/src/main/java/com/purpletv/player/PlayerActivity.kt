package com.purpletv.player

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.core.view.doOnLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.purpletv.PurpleApp
import com.purpletv.R
import com.purpletv.chat.ChatClient
import com.purpletv.chat.ChatView
import com.purpletv.twitch.Stream
import com.purpletv.twitch.FollowKind
import com.purpletv.ui.FollowButton
import com.purpletv.twitch.StreamOfflineException
import com.purpletv.twitch.fullCount
import com.purpletv.ui.AvatarCache
import com.purpletv.ui.RowAdapter
import com.purpletv.ui.RowItem
import com.purpletv.ui.RowKind
import com.purpletv.ui.fixRowHeight
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

/**
 * Full-screen live player. The video goes through ExoPlayer into a SurfaceView, so the TV's
 * hardware decoder renders it on the video plane instead of the app drawing it with the GPU.
 *
 * The overlay follows the official TV app: any key shows it (and it shows once when the picture
 * starts), with Back, the channel card and buttons for quality and chat; it hides after a few idle
 * seconds or on Back. Menu opens the quality list directly. The chat column narrows the video
 * rather than covering it.
 * Decoder counters and rebuffer times are logged under "PurpleTV": `logcat -s PurpleTV`.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private val scope = MainScope()
    private val ui = Handler(Looper.getMainLooper())
    private val app get() = PurpleApp.instance
    private val prefs get() = app.playerPrefs
    private val retry = RetryPolicy()

    private var login = ""
    private lateinit var zap: ZapList
    private lateinit var playerView: PlayerView
    private lateinit var stage: View
    private lateinit var overlay: View
    private lateinit var backButton: View
    private lateinit var qualityButton: View
    private lateinit var chatButton: View
    private lateinit var zapButton: View
    private lateinit var followView: View
    private lateinit var followButton: FollowButton
    private var confirmingUnfollow = false
    private lateinit var zapStrip: View
    private val zapAdapter = ZapAdapter { zapTo(it) }
    private lateinit var qualityMenu: View
    private lateinit var qualityList: LinearLayout
    private lateinit var message: View
    private lateinit var tune: View
    private lateinit var offlinePage: View
    private val offlineAdapter = RowAdapter({ item -> if (item is RowItem.StreamItem) zapTo(item.stream) })
    private var channelName = ""
    private lateinit var chatView: ChatView
    private var chat: ChatClient? = null

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var resolving: Job? = null
    private var attempts = 0
    private var qualityApplied = false
    private var bufferingSince = 0L
    private var stream: Stream? = null
    private var introShown = false
    private var layer = PlayerLayer.TUNING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        login = intent.getStringExtra(EXTRA_CHANNEL).orEmpty().trim().lowercase()
        playerView = findViewById(R.id.player_view)
        stage = findViewById(R.id.player_stage)
        overlay = findViewById(R.id.info_overlay)
        backButton = findViewById(R.id.overlay_back)
        qualityButton = findViewById(R.id.overlay_quality)
        chatButton = findViewById(R.id.overlay_chat)
        zapButton = findViewById(R.id.overlay_zap)
        followView = findViewById(R.id.overlay_follow)
        followButton = FollowButton(followView, scope, app.follows, { app.tokens.userId }, {
            app.tokens.clearLogin()
            startActivity(android.content.Intent(this, com.purpletv.home.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }, ::updateButtonLabel) { confirming ->
            confirmingUnfollow = confirming
            if (overlay.visibility == View.VISIBLE) scheduleAutoHide()
        }
        zapStrip = findViewById(R.id.zap_strip)
        backButton.setOnClickListener { finish() }
        qualityButton.setOnClickListener { showQualityMenu() }
        chatButton.setOnClickListener { toggleChat() }
        zapButton.setOnClickListener { showZapStrip() }
        // Back sits alone at the top; the other buttons share the bottom row.
        backButton.nextFocusDownId = R.id.overlay_quality
        backButton.nextFocusRightId = R.id.overlay_quality
        qualityButton.nextFocusUpId = R.id.overlay_back
        qualityButton.nextFocusLeftId = R.id.overlay_back
        chatButton.nextFocusUpId = R.id.overlay_back
        zapButton.nextFocusUpId = R.id.overlay_back
        followView.nextFocusUpId = R.id.overlay_back
        zap = app.zap ?: ZapList(getString(R.string.row_followed), app.snapshot.followedLive)
        zapButton.visibility = if (zap.isEmpty()) View.GONE else View.VISIBLE
        chatButton.nextFocusRightId = if (zap.isEmpty()) R.id.overlay_follow else R.id.overlay_zap
        zapButton.nextFocusRightId = R.id.overlay_follow
        followView.nextFocusLeftId = if (zap.isEmpty()) R.id.overlay_chat else R.id.overlay_zap
        followView.nextFocusRightId = R.id.overlay_follow
        nameButtons()
        findViewById<TextView>(R.id.zap_title).text = zap.title
        findViewById<RecyclerView>(R.id.zap_list).apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = zapAdapter
            itemAnimator = null
        }
        qualityMenu = findViewById(R.id.quality_menu)
        qualityList = findViewById(R.id.quality_list)
        message = findViewById(R.id.player_message)
        chatView = findViewById(R.id.chat)
        findViewById<ImageView>(R.id.info_avatar).clipToOutline = true
        tune = findViewById(R.id.tune_layer)
        bindTune(tuneFromIntent())
        offlinePage = findViewById(R.id.offline_page)
        findViewById<View>(R.id.offline_row).apply {
            findViewById<TextView>(R.id.row_title).setText(R.string.offline_meanwhile)
            findViewById<RecyclerView>(R.id.row_list).apply {
                layoutManager = LinearLayoutManager(this@PlayerActivity, LinearLayoutManager.HORIZONTAL, false)
                adapter = offlineAdapter
                itemAnimator = null
                fixRowHeight(RowKind.STREAMS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        start()
        loadStreamInfo()
        if (prefs.chatVisible) showChat()
    }

    override fun onStop() {
        super.onStop()
        release()
        hideChat()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Any key while the overlay shows keeps it up a little longer.
        if (event.action == KeyEvent.ACTION_DOWN && overlay.visibility == View.VISIBLE) scheduleAutoHide()
        return super.dispatchKeyEvent(event)
    }

    /** Reached only by keys no button took: focus moves between the buttons on its own. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (zapStrip.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { hideZapStrip(); return true }
            val list = findViewById<RecyclerView>(R.id.zap_list)
            val index = currentFocus?.let { list.findContainingViewHolder(it) }?.bindingAdapterPosition ?: -1
            if (zapPanelKeepsKey(keyCode, index, zap.streams.size)) return true
            return super.onKeyDown(keyCode, event)
        }
        if (qualityMenu.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { hideQualityMenu(); return true }
            val row = qualityList.indexOfChild(currentFocus)
            if (menuKeepsKey(keyCode, row, qualityList.childCount)) {
                if (row < 0) qualityList.getChildAt(0)?.requestFocus()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        // The offline page is a page of its own: the arrows move between its cards, Back leaves.
        if (layer == PlayerLayer.OFFLINE) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_MENU) { showOverlay(); showQualityMenu(); return true }
        if (overlay.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { hideOverlay(); return true }
            // The overlay came up on its own, with nothing focused: the first key only picks a button.
            if (isNavigationKey(keyCode) && currentFocus?.let { isInside(it, overlay) } != true) {
                qualityButton.requestFocus()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        if (isNavigationKey(keyCode)) { showOverlay(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun isNavigationKey(keyCode: Int) = keyCode in NAVIGATION_KEYS

    private fun isInside(view: View, parent: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === parent) return true
            v = v.parent as? View
        }
        return false
    }

    // --- Playback

    private fun start() {
        val selector = DefaultTrackSelector(this)
        // A quicker restart after a rebuffer: the default waits for 5 s of buffer, which is the
        // stall seen when Twitch switches from its ad slate back to the live stream.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
            .build()
        val exo = ExoPlayer.Builder(this, FreshDecoderRenderersFactory(this))
            .setTrackSelector(selector).setLoadControl(loadControl).build()
        exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        exo.addListener(PlaybackListener())
        exo.addAnalyticsListener(DecoderLogger())
        playerView.player = exo
        player = exo
        trackSelector = selector
        qualityApplied = false
        introShown = false
        attempts = 0
        on(PlayerEvent.Started)
        resolve()
        ui.postDelayed(logStats, STATS_INTERVAL_MS)
    }

    /** Fetches a fresh playlist URL and plays it; also how the player recovers from errors. */
    private fun resolve() {
        resolving?.cancel()
        resolving = scope.launch {
            val exo = player ?: return@launch
            try {
                val url = app.playback.liveStreamUrl(login)
                // Twitch's newest segment trails real time by a few seconds; at ExoPlayer's default
                // offset the buffer hovered near zero and every late segment stalled the picture.
                val item = MediaItem.Builder().setUri(url)
                    .setLiveConfiguration(MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(LIVE_OFFSET_MS)
                        .setMinOffsetMs(LIVE_MIN_OFFSET_MS)
                        .setMaxPlaybackSpeed(1f)
                        .build())
                    .build()
                val source = HlsMediaSource.Factory(DefaultHttpDataSource.Factory()).createMediaSource(item)
                exo.setMediaSource(source)
                exo.prepare()
                exo.playWhenReady = true
                message.visibility = View.GONE
                Log.i(TAG, "playing $login")
            } catch (e: StreamOfflineException) {
                on(PlayerEvent.Offline)
            } catch (e: IOException) {
                Log.w(TAG, "cannot resolve $login: ${e.message}")
                retryLater()
            }
        }
    }

    private fun retryLater() {
        on(PlayerEvent.ConnectionLost)
        showMessage(R.string.player_reconnecting)
        val wait = retry.delayFor(attempts++)
        resolving = scope.launch { delay(wait); resolve() }
    }

    private fun release() {
        ui.removeCallbacks(logStats)
        ui.removeCallbacks(autoHide)
        resolving?.cancel()
        playerView.player = null
        player?.release()
        player = null
        trackSelector = null
    }

    // --- Tune-in

    private fun tuneFromIntent() = TuneInfo(
        login = login,
        name = intent.getStringExtra(EXTRA_NAME) ?: login,
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
        game = intent.getStringExtra(EXTRA_GAME).orEmpty(),
        viewers = intent.getIntExtra(EXTRA_VIEWERS, 0),
        thumbnail = intent.getStringExtra(EXTRA_THUMB).orEmpty(),
        avatar = intent.getStringExtra(EXTRA_AVATAR),
    )

    private fun bindTune(info: TuneInfo) {
        val thumb = findViewById<ImageView>(R.id.tune_thumb)
        if (info.thumbnail.isEmpty()) thumb.setImageDrawable(null) else thumb.load(info.thumbnail)
        val avatar = findViewById<ImageView>(R.id.tune_avatar)
        if (info.avatar.isNullOrEmpty()) avatar.setImageDrawable(null)
        else avatar.load(info.avatar) { transformations(CircleCropTransformation()) }
        channelName = info.name
        findViewById<TextView>(R.id.tune_name).text = info.name
        findViewById<TextView>(R.id.tune_title).text = info.title
        findViewById<TextView>(R.id.tune_meta).text = metaText(info.game, "", info.viewers.takeIf { it > 0 })
    }

    /** Moves between the tune-in, the video and the messages; see [nextLayer]. */
    private fun on(event: PlayerEvent) {
        val next = nextLayer(layer, event)
        if (next == layer && event != PlayerEvent.Started) return
        layer = next
        tune.animate().cancel()
        if (next == PlayerLayer.OFFLINE) showOfflinePage() else offlinePage.visibility = View.GONE
        when (next) {
            PlayerLayer.TUNING -> { tune.alpha = 1f; tune.visibility = View.VISIBLE }
            PlayerLayer.VIDEO -> tune.animate().alpha(0f).setDuration(TUNE_FADE_MS).withEndAction { tune.visibility = View.GONE }.start()
            PlayerLayer.RECONNECTING, PlayerLayer.OFFLINE -> tune.visibility = View.GONE
        }
    }

    // --- Offline page

    private fun showOfflinePage() {
        hideOverlay()
        message.visibility = View.GONE
        offlinePage.visibility = View.VISIBLE
        findViewById<TextView>(R.id.offline_title).text = getString(R.string.offline_title, channelName)
        val suggestions = offlineSuggestions(app.snapshot.followedLive, login)
        val row = findViewById<View>(R.id.offline_row)
        row.visibility = if (suggestions.isEmpty()) View.GONE else View.VISIBLE
        offlineAdapter.avatars = app.snapshot.avatars
        offlineAdapter.submitList(suggestions.map { RowItem.StreamItem(it) })
        if (suggestions.isNotEmpty()) focusFirstSuggestion(row.findViewById(R.id.row_list))
        val shownFor = login
        scope.launch {
            val user = runCatching { app.helix.userByLogin(shownFor) }.getOrNull() ?: return@launch
            if (login != shownFor || layer != PlayerLayer.OFFLINE) return@launch
            channelName = user.displayName
            findViewById<TextView>(R.id.offline_title).text = getString(R.string.offline_title, user.displayName)
            if (user.profileImageUrl.isNotEmpty()) {
                findViewById<ImageView>(R.id.offline_avatar).load(user.profileImageUrl) { transformations(CircleCropTransformation()) }
            }
            if (user.offlineImageUrl.isNotEmpty()) {
                findViewById<ImageView>(R.id.offline_image).load(user.offlineImageUrl) {
                    listener(onSuccess = { _, _ ->
                        findViewById<View>(R.id.offline_scrim_side).visibility = View.VISIBLE
                        findViewById<View>(R.id.offline_scrim_bottom).visibility = View.VISIBLE
                    })
                }
            }
        }
    }

    private fun focusFirstSuggestion(list: RecyclerView, attempt: Int = 0) {
        list.postDelayed({
            val focused = list.layoutManager?.findViewByPosition(0)?.requestFocus() == true
            if (!focused && attempt < ZAP_FOCUS_ATTEMPTS) focusFirstSuggestion(list, attempt + 1)
        }, if (attempt == 0) 0L else ZAP_FOCUS_RETRY_MS)
    }

    private fun showMessage(text: Int) {
        message.visibility = View.VISIBLE
        findViewById<TextView>(R.id.player_message_title).setText(text)
    }

    private inner class PlaybackListener : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> bufferingSince = SystemClock.elapsedRealtime()
                Player.STATE_READY -> {
                    if (bufferingSince > 0) Log.i(TAG, "rebuffer ${SystemClock.elapsedRealtime() - bufferingSince}ms")
                    bufferingSince = 0
                    attempts = 0
                    message.visibility = View.GONE
                    // The overlay shows once when the picture starts, without taking the focus, so a
                    // first OK cannot press Back by surprise.
                    if (!introShown) { introShown = true; showOverlay(focus = false) }
                }
                Player.STATE_ENDED -> resolve()
            }
        }

        override fun onRenderedFirstFrame() = on(PlayerEvent.FirstFrame)

        override fun onTracksChanged(tracks: Tracks) {
            if (!qualityApplied && renditions().isNotEmpty()) {
                qualityApplied = true
                applySavedQuality()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback error ${error.errorCodeName}")
            val exo = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                exo.seekToDefaultPosition()
                exo.prepare()
            } else {
                // Network trouble or a playlist that ended: resolving again tells offline from a glitch.
                retryLater()
            }
        }
    }

    // --- Quality

    private class Rendition(val option: QualityOption, val override: TrackSelectionOverride)

    private fun renditions(): List<Rendition> {
        val exo = player ?: return emptyList()
        val found = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
            (0 until group.length).mapNotNull { i ->
                qualityOptions(listOf(group.getTrackFormat(i))).firstOrNull()
                    ?.let { Rendition(it, TrackSelectionOverride(group.mediaTrackGroup, i)) }
            }
        }
        return found.distinctBy { it.option.label }
            .sortedWith(compareByDescending<Rendition> { it.option.height }.thenByDescending { it.option.frameRate })
    }

    private fun applySavedQuality() {
        val chosen = if (prefs.isAuto) null else pickQuality(renditions().map { it.option }, prefs.maxHeight, prefs.preferHighFrameRate)
        applyQuality(chosen)
    }

    private fun applyQuality(option: QualityOption?) {
        val selector = trackSelector ?: return
        val params = selector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        renditions().firstOrNull { it.option == option }?.let { params.addOverride(it.override) }
        selector.setParameters(params)
    }

    private fun showQualityMenu() {
        val options = renditions().map { it.option }
        if (options.isEmpty()) return
        qualityList.removeAllViews()
        val current = if (prefs.isAuto) null else pickQuality(options, prefs.maxHeight, prefs.preferHighFrameRate)
        val entries = listOf<QualityOption?>(null) + options
        var focus: View? = null
        entries.forEach { option ->
            val row = layoutInflater.inflate(R.layout.item_quality, qualityList, false) as TextView
            row.text = option?.label ?: autoLabel(getString(R.string.quality_auto), playingOption(player?.videoFormat))
            // Every row keeps the check's room, so the labels line up; only the current choice shows it.
            val check = getDrawable(R.drawable.ic_check)!!.mutate().apply { alpha = if (option == current) OPAQUE else 0 }
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(check, null, null, null)
            row.setOnClickListener {
                if (option == null) {
                    prefs.maxHeight = PlayerPrefs.AUTO
                } else {
                    prefs.maxHeight = option.height
                    prefs.preferHighFrameRate = option.frameRate >= HIGH_FPS
                }
                applyQuality(option)
                hideQualityMenu()
            }
            qualityList.addView(row)
            if (option == current) focus = row
        }
        qualityMenu.visibility = View.VISIBLE
        // As in the official app, the current choice is where the focus starts.
        (focus ?: qualityList.getChildAt(0)).requestFocus()
        ui.removeCallbacks(autoHide)
    }

    private fun hideQualityMenu() {
        qualityMenu.visibility = View.GONE
        if (overlay.visibility == View.VISIBLE) {
            qualityButton.requestFocus()
            scheduleAutoHide()
        }
    }

    // --- Chat

    private fun toggleChat() {
        prefs.chatVisible = chat == null
        if (prefs.chatVisible) showChat() else hideChat()
    }

    /** Connects only while the column shows: a hidden chat costs no network or CPU. */
    private fun showChat() {
        if (chat != null) return
        chatView.clear()
        chatView.visibility = View.VISIBLE
        setStageEnd(resources.getDimensionPixelSize(R.dimen.chat_width))
        chat = ChatClient(app.http, login) { chatView.add(it) }.also { it.connect() }
    }

    private fun reconnectChat() {
        chat?.close()
        chatView.clear()
        chat = ChatClient(app.http, login) { chatView.add(it) }.also { it.connect() }
    }

    private fun hideChat() {
        chat?.close()
        chat = null
        chatView.visibility = View.GONE
        setStageEnd(0)
    }

    private fun setStageEnd(px: Int) {
        val params = stage.layoutParams as android.widget.FrameLayout.LayoutParams
        if (params.marginEnd == px) return
        params.marginEnd = px
        stage.layoutParams = params
    }

    // --- Info overlay

    private fun loadStreamInfo() {
        val requestedLogin = login
        scope.launch {
            val live = runCatching { app.helix.streamByLogin(requestedLogin) }.getOrNull() ?: return@launch
            if (login != requestedLogin) return@launch
            stream = live
            followButton.bind(FollowKind.CHANNEL, live.userId, live.displayName.ifBlank { live.login })
            val avatar = runCatching { AvatarCache(app.helix).urls(listOf(live.userId))[live.userId] }.getOrNull()
            if (login != requestedLogin) return@launch
            avatar?.let {
                findViewById<ImageView>(R.id.info_avatar).load(it) { transformations(CircleCropTransformation()) }
            }
            bindInfo()
        }
    }

    private fun bindInfo() {
        val live = stream
        findViewById<TextView>(R.id.info_name).text = live?.displayName ?: login
        findViewById<TextView>(R.id.info_title).text = live?.title.orEmpty()
        val since = live?.let { uptime(it.startedAt, System.currentTimeMillis()) }.orEmpty()
        findViewById<TextView>(R.id.info_meta).text = metaText(live?.gameName.orEmpty(), since, live?.viewers)
    }

    /** Game · uptime · viewers; the viewer count closes the line, in the official app's pink. */
    private fun metaText(game: String, since: String, viewerCount: Int?): CharSequence {
        val viewers = viewerCount?.let { getString(R.string.player_viewers, fullCount(it, Locale.getDefault())) }.orEmpty()
        val line = metaLine(game, since, viewers)
        return SpannableString(line).apply {
            if (viewers.isNotEmpty()) {
                setSpan(ForegroundColorSpan(getColor(R.color.viewers_pink)), line.length - viewers.length, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /** The focused button's name shows above the row; the quality button adds what plays. */
    private fun nameButtons() {
        for (button in listOf(qualityButton, chatButton, zapButton, followView)) {
            button.setOnFocusChangeListener { _, _ -> updateButtonLabel() }
        }
    }

    private fun updateButtonLabel() {
        val label = findViewById<TextView>(R.id.overlay_button_label)
        val buttons = listOf(qualityButton, chatButton, zapButton, followView)
        val focused = buttons.firstOrNull { it.isFocused }
        label.text = when (focused) {
            qualityButton -> playingOption(player?.videoFormat)?.let { getString(R.string.overlay_quality_label, it.label) }
                ?: getString(R.string.quality_title)
            chatButton -> getString(R.string.chat_title)
            zapButton -> getString(R.string.zap_button)
            followView -> followView.contentDescription
            else -> ""
        }
        label.visibility = if (focused == null) View.INVISIBLE else View.VISIBLE
    }

    private fun showOverlay(focus: Boolean = true) {
        bindInfo()
        overlay.visibility = View.VISIBLE
        // Focus starts on quality, not Back: a double OK must not leave the channel.
        if (focus) qualityButton.requestFocus()
        scheduleAutoHide()
    }

    private fun hideOverlay() {
        hideQualityMenu()
        hideZapStrip()
        overlay.visibility = View.GONE
        currentFocus?.clearFocus()
        ui.removeCallbacks(autoHide)
    }

    private fun scheduleAutoHide() {
        ui.removeCallbacks(autoHide)
        if (!confirmingUnfollow && qualityMenu.visibility != View.VISIBLE && zapStrip.visibility != View.VISIBLE) {
            ui.postDelayed(autoHide, OVERLAY_TIMEOUT_MS)
        }
    }

    private val autoHide = Runnable { hideOverlay() }

    // --- Zapping

    /** Opens on the channel playing; the overlay stays up while the strip is open. */
    private fun showZapStrip() {
        zapAdapter.submit(zap.streams, login)
        zapStrip.visibility = View.VISIBLE
        ui.removeCallbacks(autoHide)
        val list = findViewById<RecyclerView>(R.id.zap_list)
        val index = zap.focusIndex(login)
        list.scrollToPosition(index)
        focusZapCard(list, index)
        // The channel's details and the buttons move up to make room for the strip.
        zapStrip.doOnLayout { strip -> liftOverlay(-strip.height.toFloat()) }
    }

    private fun focusZapCard(list: RecyclerView, index: Int, attempt: Int = 0) {
        list.postDelayed({
            val focused = list.layoutManager?.findViewByPosition(index)?.requestFocus() == true
            if (!focused && attempt < ZAP_FOCUS_ATTEMPTS) focusZapCard(list, index, attempt + 1)
        }, if (attempt == 0) 0L else ZAP_FOCUS_RETRY_MS)
    }

    private fun hideZapStrip() {
        if (zapStrip.visibility != View.VISIBLE) return
        zapStrip.visibility = View.GONE
        liftOverlay(0f)
        if (overlay.visibility == View.VISIBLE) {
            zapButton.requestFocus()
            scheduleAutoHide()
        }
    }

    private fun liftOverlay(dy: Float) {
        for (id in intArrayOf(R.id.info_avatar_frame, R.id.info_text, R.id.overlay_buttons, R.id.overlay_button_label)) {
            findViewById<View>(id).animate().translationY(dy).setDuration(LIFT_MS).setInterpolator(DECELERATE).start()
        }
    }

    /** Switches channel without leaving the player: the tune-in covers the change. */
    private fun zapTo(target: Stream) {
        hideZapStrip()
        hideOverlay()
        if (target.login.equals(login, ignoreCase = true)) return
        login = target.login.lowercase()
        followButton.clear()
        followButton.bind(FollowKind.CHANNEL, target.userId, target.displayName.ifBlank { target.login })
        stream = target
        bindTune(target.toTuneInfo(app.snapshot.avatars[target.userId]))
        message.visibility = View.GONE
        // A fresh player, as when the screen opens: the same one stopped and prepared again
        // presented the new channel at ~52 fps on this TV (frames held for 2-3 vsyncs).
        release()
        start()
        if (chat != null) reconnectChat()
        loadStreamInfo()
    }

    // --- Diagnostics

    private val logStats = object : Runnable {
        override fun run() {
            val exo = player ?: return
            exo.videoDecoderCounters?.let { c ->
                c.ensureUpdated()
                val f = exo.videoFormat
                Log.i(TAG, "stats ${f?.width}x${f?.height}@${f?.frameRate} rendered=${c.renderedOutputBufferCount} " +
                    "dropped=${c.droppedBufferCount} skipped=${c.skippedOutputBufferCount} " +
                    "maxConsecutiveDropped=${c.maxConsecutiveDroppedBufferCount} " +
                    "liveOffset=${exo.currentLiveOffset}ms buffered=${exo.totalBufferedDuration}ms speed=${exo.playbackParameters.speed}")
            }
            ui.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    private class DecoderLogger : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long,
        ) {
            Log.i(TAG, "video decoder $decoderName")
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime, format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            Log.i(TAG, "format ${format.width}x${format.height}@${format.frameRate} ${format.codecs} ${format.bitrate}bps " +
                "reuse=${decoderReuseEvaluation?.result}")
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            Log.w(TAG, "dropped $droppedFrames frames in ${elapsedMs}ms")
        }
    }

    companion object {
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_NAME = "tune_name"
        const val EXTRA_TITLE = "tune_title"
        const val EXTRA_GAME = "tune_game"
        const val EXTRA_VIEWERS = "tune_viewers"
        const val EXTRA_THUMB = "tune_thumb"
        const val EXTRA_AVATAR = "tune_avatar"
        private const val TUNE_FADE_MS = 200L
        private const val LIFT_MS = 160L
        private const val OPAQUE = 255
        private const val ZAP_FOCUS_ATTEMPTS = 10
        private const val ZAP_FOCUS_RETRY_MS = 32L
        private val DECELERATE = DecelerateInterpolator()
        private const val TAG = "PurpleTV"
        private const val STATS_INTERVAL_MS = 5_000L
        private const val OVERLAY_TIMEOUT_MS = 6_000L
        private val NAVIGATION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        private const val HIGH_FPS = 50f
        private const val MIN_BUFFER_MS = 15_000
        private const val MAX_BUFFER_MS = 50_000
        private const val START_BUFFER_MS = 1_500
        private const val REBUFFER_MS = 2_000
        private const val LIVE_OFFSET_MS = 10_000L
        private const val LIVE_MIN_OFFSET_MS = 8_000L
    }
}
