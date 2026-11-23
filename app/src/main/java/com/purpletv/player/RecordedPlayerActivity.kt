package com.purpletv.player

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.purpletv.PurpleApp
import com.purpletv.net.Http
import com.purpletv.R
import com.purpletv.home.MainActivity
import com.purpletv.home.Screen
import com.purpletv.twitch.ClipRendition
import com.purpletv.twitch.NotLoggedInException
import com.purpletv.twitch.RecordingKind
import com.purpletv.twitch.RecordingUnavailableException
import com.purpletv.twitch.durationText
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/** Recorded playback has its own controls and lifecycle, leaving the live decoder path intact. */
@OptIn(UnstableApi::class)
class RecordedPlayerActivity : Activity() {
    private val app get() = PurpleApp.instance
    private val scope = MainScope()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var playerView: PlayerView
    private lateinit var controls: View
    private lateinit var seek: SeekBar
    private lateinit var playPause: TextView
    private lateinit var qualityMenu: View
    private lateinit var qualityList: LinearLayout
    private lateinit var progress: VodProgress
    private lateinit var kind: RecordingKind
    private var videoId = ""
    private var account = ""
    private var exo: ExoPlayer? = null
    private var selector: DefaultTrackSelector? = null
    private var resolving: Job? = null
    private var clips = emptyList<ClipRendition>()
    private var position = 0L
    private var playWhenReady = true
    private var qualityApplied = false
    private var prepared = false
    private var ended = false
    private var lastSavedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recorded_player)
        videoId = intent.getStringExtra(EXTRA_ID).orEmpty()
        kind = RecordingKind.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) } ?: RecordingKind.VOD
        account = app.tokens.userId.orEmpty()
        progress = VodProgress(getSharedPreferences("vod_progress", MODE_PRIVATE))
        position = savedInstanceState?.getLong("position") ?: if (kind == RecordingKind.VOD) progress.position(account, videoId) else 0L
        playWhenReady = savedInstanceState?.getBoolean("playing") ?: true
        playerView = findViewById(R.id.recorded_player_view)
        controls = findViewById(R.id.recorded_controls)
        seek = findViewById(R.id.recorded_seek)
        playPause = findViewById(R.id.recorded_play_pause)
        qualityMenu = findViewById(R.id.recorded_quality_menu)
        qualityList = qualityMenu.findViewById(R.id.quality_list)
        findViewById<TextView>(R.id.recorded_title).text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        findViewById<View>(R.id.recorded_back).setOnClickListener { finish() }
        playPause.setOnClickListener { togglePlayback() }
        findViewById<View>(R.id.recorded_start_over).setOnClickListener { startOver() }
        findViewById<View>(R.id.recorded_quality).setOnClickListener { showQuality() }
        findViewById<View>(R.id.recorded_retry).setOnClickListener { savePosition(); start() }
        seek.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN) seekBy(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000)
                true
            } else false
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                val player = exo ?: return
                if (fromUser && player.duration > 0) { player.seekTo(player.duration * value / 1000); ended = false; showControls(false) }
            }
            override fun onStartTrackingTouch(bar: SeekBar) { ui.removeCallbacks(hide) }
            override fun onStopTrackingTouch(bar: SeekBar) { scheduleHide() }
        })
        for (id in listOf(R.id.recorded_back, R.id.recorded_play_pause, R.id.recorded_start_over, R.id.recorded_quality)) {
            findViewById<View>(id).nextFocusUpId = R.id.recorded_seek
        }
        seek.nextFocusDownId = R.id.recorded_play_pause
    }

    override fun onStart() {
        super.onStart()
        if (app.tokens.accessToken == null || app.tokens.userId != account) { openMain(); return }
        start()
        ui.post(tick)
    }

    override fun onStop() {
        savePosition()
        playWhenReady = exo?.playWhenReady ?: playWhenReady
        release()
        ui.removeCallbacks(tick)
        ui.removeCallbacks(hide)
        super.onStop()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        savePosition()
        outState.putLong("position", position)
        outState.putBoolean("playing", exo?.playWhenReady ?: playWhenReady)
        super.onSaveInstanceState(outState)
    }

    private fun start() {
        release()
        ended = false
        prepared = false
        qualityApplied = false
        clips = emptyList()
        findViewById<View>(R.id.recorded_message).visibility = View.GONE
        qualityMenu.visibility = View.GONE
        selector = DefaultTrackSelector(this)
        val player = ExoPlayer.Builder(this, FreshDecoderRenderersFactory(this))
            .setTrackSelector(selector!!)
            .setMediaSourceFactory(DefaultMediaSourceFactory(Http.mediaFactory(app.debugMode).setUserAgent("PurpleTV")))
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        exo = player
        playerView.player = player
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { prepared = true; updateControls(); scheduleHide() }
                if (state == Player.STATE_ENDED) {
                    ended = true
                    position = 0
                    if (kind == RecordingKind.VOD) progress.save(account, videoId, 0, completed = true)
                    showControls(true)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { updateControls(); scheduleHide() }
            override fun onTracksChanged(tracks: Tracks) {
                if (!qualityApplied && kind == RecordingKind.VOD && vodQualities().isNotEmpty()) { qualityApplied = true; applyVodQuality(savedQuality(vodQualities())) }
            }
            override fun onPlayerError(error: PlaybackException) { showError(error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) }
        })
        resolving = scope.launch {
            try {
                val url = if (kind == RecordingKind.VOD) app.recordedPlayback.vodUrl(videoId) else {
                    clips = app.recordedPlayback.clipRenditions(videoId)
                    pickClip(savedQuality(clipQualities())).url
                }
                player.setMediaItem(MediaItem.fromUri(url), position)
                player.prepare()
                player.playWhenReady = playWhenReady
            } catch (e: NotLoggedInException) {
                requireLogin(e)
            } catch (e: IOException) {
                showError(e is RecordingUnavailableException)
            }
        }
        showControls(true)
    }

    private fun release() {
        resolving?.cancel()
        playerView.player = null
        exo?.release()
        exo = null
        selector = null
    }

    private fun savePosition() {
        val player = exo ?: return
        if (!prepared || ended) return
        position = player.currentPosition.coerceAtLeast(0)
        if (kind == RecordingKind.VOD) progress.save(account, videoId, position)
    }

    private fun togglePlayback() {
        val player = exo ?: return
        if (ended) startOver() else { player.playWhenReady = !player.playWhenReady; showControls(false) }
    }

    private fun startOver() {
        ended = false
        position = 0
        if (kind == RecordingKind.VOD) progress.save(account, videoId, 0)
        exo?.seekTo(0)
        exo?.play()
        showControls(false)
    }

    private fun seekBy(delta: Long) {
        val player = exo ?: return
        if (player.duration <= 0) return
        ended = false
        player.seekTo((player.currentPosition + delta).coerceIn(0, player.duration))
        updateControls()
        scheduleHide()
    }

    private fun updateControls() {
        val player = exo ?: return
        val duration = player.duration.coerceAtLeast(0)
        val elapsed = player.currentPosition.coerceAtLeast(0)
        seek.isEnabled = duration > 0
        seek.progress = if (duration > 0) (elapsed * 1000 / duration).toInt() else 0
        findViewById<TextView>(R.id.recorded_time).text = getString(R.string.recording_time, durationText(elapsed / 1000), durationText(duration / 1000))
        playPause.setText(if (ended) R.string.replay else if (player.playWhenReady) R.string.pause else R.string.play)
    }

    private fun showControls(focus: Boolean) {
        controls.visibility = View.VISIBLE
        updateControls()
        if (focus) playPause.requestFocus()
        scheduleHide()
    }

    private val hide = Runnable {
        if (exo?.isPlaying == true && qualityMenu.visibility != View.VISIBLE) {
            controls.visibility = View.GONE
            currentFocus?.clearFocus()
        }
    }
    private fun scheduleHide() {
        ui.removeCallbacks(hide)
        if (!ended && exo?.isPlaying == true && qualityMenu.visibility != View.VISIBLE) ui.postDelayed(hide, 6_000)
    }
    private val tick = object : Runnable {
        override fun run() {
            updateControls()
            if (SystemClock.elapsedRealtime() - lastSavedAt >= 10_000) { savePosition(); lastSavedAt = SystemClock.elapsedRealtime() }
            ui.postDelayed(this, 1_000)
        }
    }

    private fun showError(unavailable: Boolean) {
        savePosition()
        exo?.pause()
        findViewById<View>(R.id.recorded_message).visibility = View.VISIBLE
        findViewById<TextView>(R.id.recorded_message_text).setText(if (unavailable) R.string.recording_unavailable else R.string.recording_error)
        findViewById<View>(R.id.recorded_retry).requestFocus()
    }

    private fun requireLogin(error: NotLoggedInException) {
        if (!error.clearLogin(app.tokens)) return
        openMain()
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, Screen.FOLLOWING.name).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun clipQualities() = clips.map { QualityOption(if (it.frameRate >= 50) "${it.height}p${it.frameRate.toInt()}" else "${it.height}p", it.height, it.frameRate) }
    private fun savedQuality(options: List<QualityOption>): QualityOption? = if (app.playerPrefs.isAuto) null else
        pickQuality(options, app.playerPrefs.maxHeight, app.playerPrefs.preferHighFrameRate)
    private fun pickClip(option: QualityOption?): ClipRendition = clips.firstOrNull { it.height == option?.height && it.frameRate == option.frameRate } ?: clips.first()
    private fun vodQualities(): List<QualityOption> = exo?.currentTracks?.groups.orEmpty().filter { it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { group -> qualityOptions((0 until group.length).map { group.getTrackFormat(it) }) }.distinctBy { it.label }
        .sortedWith(compareByDescending<QualityOption> { it.height }.thenByDescending { it.frameRate })

    private fun applyVodQuality(option: QualityOption?) {
        val params = selector?.buildUponParameters()?.clearOverridesOfType(C.TRACK_TYPE_VIDEO) ?: return
        if (option != null) exo?.currentTracks?.groups.orEmpty().filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { group ->
            (0 until group.length).firstOrNull { qualityOptions(listOf(group.getTrackFormat(it))).firstOrNull() == option }?.let {
                params.addOverride(TrackSelectionOverride(group.mediaTrackGroup, it))
            }
        }
        selector?.setParameters(params)
    }

    private fun showQuality() {
        val options = if (kind == RecordingKind.CLIP) clipQualities() else vodQualities()
        if (options.isEmpty()) return
        qualityList.removeAllViews()
        val current = savedQuality(options)
        var selected: View? = null
        (listOf<QualityOption?>(null) + options).forEach { option ->
            val row = layoutInflater.inflate(R.layout.item_quality, qualityList, false) as TextView
            row.text = option?.label ?: getString(R.string.quality_auto)
            row.isSelected = option == current
            val check = getDrawable(R.drawable.ic_check)!!.mutate().apply { alpha = if (option == current) 255 else 0 }
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(check, null, null, null)
            row.setOnClickListener {
                app.playerPrefs.maxHeight = option?.height ?: PlayerPrefs.AUTO
                if (option != null) app.playerPrefs.preferHighFrameRate = option.frameRate >= 50
                if (kind == RecordingKind.VOD) applyVodQuality(option) else exo?.let { player ->
                    val wasPlaying = player.playWhenReady
                    val at = if (ended) 0L else player.currentPosition
                    player.setMediaItem(MediaItem.fromUri(pickClip(option).url), at)
                    player.prepare()
                    player.playWhenReady = wasPlaying
                }
                closeQuality()
            }
            qualityList.addView(row)
            if (option == current) selected = row
        }
        qualityMenu.visibility = View.VISIBLE
        (selected ?: qualityList.getChildAt(0)).requestFocus()
        ui.removeCallbacks(hide)
    }

    private fun closeQuality() {
        qualityMenu.visibility = View.GONE
        findViewById<View>(R.id.recorded_quality).requestFocus()
        scheduleHide()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (qualityMenu.visibility == View.VISIBLE) {
            val row = qualityList.indexOfChild(currentFocus)
            if (event.action == KeyEvent.ACTION_DOWN && menuKeepsKey(event.keyCode, row, qualityList.childCount)) return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayback(); showControls(true); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { if (ended) startOver() else exo?.play(); showControls(true); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo?.pause(); showControls(true); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); showControls(true); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10_000); showControls(true); return true }
                KeyEvent.KEYCODE_MENU -> { showControls(false); showQuality(); return true }
            }
            if (event.keyCode in NAV_KEYS && findViewById<View>(R.id.recorded_message).visibility != View.VISIBLE) {
                if (controls.visibility != View.VISIBLE) {
                    showControls(true)
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        seek.requestFocus()
                        seekBy(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000)
                    }
                    return true
                }
                scheduleHide()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Platform Activity back handling")
    override fun onBackPressed() { if (qualityMenu.visibility == View.VISIBLE) closeQuality() else super.onBackPressed() }

    companion object {
        const val EXTRA_ID = "recording_id"
        const val EXTRA_KIND = "recording_kind"
        const val EXTRA_TITLE = "recording_title"
        private val NAV_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    }
}
