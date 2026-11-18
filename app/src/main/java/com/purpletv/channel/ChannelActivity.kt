package com.purpletv.channel

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.transform.CircleCropTransformation
import com.purpletv.PurpleApp
import com.purpletv.R
import com.purpletv.home.MainActivity
import com.purpletv.home.Screen
import com.purpletv.twitch.ClipPeriod
import com.purpletv.twitch.NotLoggedInException
import com.purpletv.twitch.Stream
import com.purpletv.twitch.clipWindow
import com.purpletv.twitch.FollowKind
import com.purpletv.ui.FollowButton
import com.purpletv.ui.GhostFollowButton
import com.purpletv.ui.Nav
import com.purpletv.ui.NavRail
import com.purpletv.ui.PinButton
import com.purpletv.ui.RecordingRow
import com.purpletv.ui.viewersText
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

class ChannelActivity : Activity() {
    private val scope = MainScope()
    private val app get() = PurpleApp.instance
    private var account: String? = null
    private var broadcasterId = ""
    private var broadcasterName = ""
    private var broadcasterLogin = ""
    private var avatar = ""
    private var live: Stream? = null
    private var job: Job? = null
    private lateinit var videos: RecordingRow
    private lateinit var clips: RecordingRow
    private var window = clipWindow(ClipPeriod.WEEK, System.currentTimeMillis())
    private var loaded = false
    private lateinit var followButton: FollowButton
    private lateinit var ghostButton: GhostFollowButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = app.tokens.userId
        setContentView(R.layout.activity_channel)
        val followView = findViewById<View>(R.id.channel_follow)
        followButton = FollowButton(followView, scope, app.follows, { app.tokens.userId }, ::requireLogin,
            pinButton = PinButton(findViewById(R.id.channel_pin), followView, app.pins) { app.tokens.userId })
        val ghostView = findViewById<View>(R.id.channel_ghost_follow)
        ghostButton = GhostFollowButton(ghostView, app.ghostFollows, { app.tokens.userId })
        followView.nextFocusRightId = ghostView.id
        ghostView.nextFocusLeftId = followView.id
        val rail = NavRail(findViewById(R.id.nav_rail), ::openMain)
        rail.select(Screen.FOLLOWING)
        app.snapshot.meAvatar?.let(rail::setAvatar)
        videos = RecordingRow(findViewById(R.id.channel_videos_row), scope, R.string.channel_videos_empty,
            { app.helix.videos(broadcasterId, it) }, ::requireLogin)
        clips = RecordingRow(findViewById(R.id.channel_clips_row), scope, R.string.channel_clips_empty,
            { app.helix.clips(broadcasterId, window, it) }, ::requireLogin)
        val filters = listOf(R.id.clips_day, R.id.clips_week, R.id.clips_month, R.id.clips_all).map { findViewById<View>(it) }
        filters.forEachIndexed { i, button ->
            button.isSelected = i == 1
            button.setOnClickListener {
                if (button.isSelected) return@setOnClickListener
                filters.forEach { it.isSelected = it === button }
                window = clipWindow(ClipPeriod.entries[i], System.currentTimeMillis())
                clips.reset()
                clips.load()
            }
            button.nextFocusLeftId = if (i == 0) R.id.nav_following else filters[i - 1].id
            button.nextFocusRightId = filters[minOf(i + 1, filters.lastIndex)].id
        }
        findViewById<View>(R.id.channel_retry).setOnClickListener { load() }
        findViewById<View>(R.id.channel_watch_live).setOnClickListener {
            live?.let { Nav.openStream(this, it.login, it, avatar) }
        }
        findViewById<TextView>(R.id.channel_name).text = intent.getStringExtra(EXTRA_LOGIN).orEmpty()
    }

    override fun onStart() {
        super.onStart()
        if (app.tokens.userId != account) { openMain(Screen.FOLLOWING); return }
        if (app.tokens.accessToken == null) { requireLogin(); return }
        if (!loaded) load() else {
            followButton.bind(FollowKind.CHANNEL, broadcasterId, broadcasterName)
            ghostButton.bind(broadcasterId, broadcasterLogin, broadcasterName)
            refreshLive()
        }
    }

    private fun load() {
        if (job?.isActive == true) return
        findViewById<TextView>(R.id.channel_status).apply { setText(R.string.loading); visibility = View.VISIBLE }
        findViewById<View>(R.id.channel_retry).visibility = View.GONE
        job = scope.launch {
            try {
                val id = intent.getStringExtra(EXTRA_ID).orEmpty()
                val user = if (id.isEmpty()) app.helix.userByLogin(intent.getStringExtra(EXTRA_LOGIN).orEmpty())
                    else app.helix.users(listOf(id)).firstOrNull()
                if (user == null) throw IOException("Channel unavailable")
                broadcasterId = user.id
                broadcasterName = user.displayName.ifBlank { user.login }
                broadcasterLogin = user.login
                followButton.bind(FollowKind.CHANNEL, broadcasterId, broadcasterName)
                ghostButton.bind(broadcasterId, broadcasterLogin, broadcasterName)
                avatar = user.profileImageUrl
                findViewById<TextView>(R.id.channel_name).text = user.displayName
                if (avatar.isNotEmpty()) findViewById<ImageView>(R.id.channel_avatar).load(avatar) { transformations(CircleCropTransformation()) }
                if (user.offlineImageUrl.isNotEmpty()) findViewById<ImageView>(R.id.channel_backdrop).load(user.offlineImageUrl)
                findViewById<View>(R.id.channel_status).visibility = View.GONE
                findViewById<View>(R.id.channel_sections).visibility = View.VISIBLE
                loaded = true
                videos.load()
                clips.load()
                refreshLive()
            } catch (e: IOException) {
                findViewById<TextView>(R.id.channel_status).setText(R.string.row_error)
                findViewById<View>(R.id.channel_retry).visibility = View.VISIBLE
                if (e is NotLoggedInException) requireLogin()
            }
        }
    }

    private fun refreshLive() {
        scope.launch {
            try {
                live = app.helix.streamByLogin(broadcasterLogin)
                findViewById<View>(R.id.channel_watch_live).visibility = if (live == null) View.GONE else View.VISIBLE
                findViewById<TextView>(R.id.channel_state).text = live?.let {
                    listOf(it.gameName, viewersText(it.viewers)).filter(String::isNotEmpty).joinToString(" · ")
                } ?: getString(R.string.offline)
            } catch (e: IOException) {
                if (e is NotLoggedInException) requireLogin()
                else findViewById<TextView>(R.id.channel_state).setText(R.string.channel_status_unavailable)
            }
        }
    }

    private fun openMain(screen: Screen) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, screen.name).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun requireLogin() { app.tokens.clearLogin(); openMain(Screen.FOLLOWING) }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val EXTRA_ID = "channel_id"
        const val EXTRA_LOGIN = "channel_login"
    }
}
