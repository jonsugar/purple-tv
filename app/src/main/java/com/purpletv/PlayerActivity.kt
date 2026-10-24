package com.purpletv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Full-screen live player. The video goes through ExoPlayer into a SurfaceView, so the TV's
 * hardware decoder renders it on the video plane instead of the app drawing it with the GPU.
 *
 * Every few seconds it logs the decoder's own counters under the tag "PurpleTV", which is what
 * the smoothness measurements read: `logcat -s PurpleTV`.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private val scope = MainScope()
    private val stats = Handler(Looper.getMainLooper())
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerView(this).apply {
            useController = false
            keepScreenOn = true
            setShutterBackgroundColor(Color.BLACK)
        }
        setContentView(playerView)
    }

    override fun onStart() {
        super.onStart()
        play(intent.getStringExtra(EXTRA_CHANNEL) ?: DEFAULT_CHANNEL)
    }

    override fun onStop() {
        super.onStop()
        release()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun play(login: String) {
        val trackSelector = DefaultTrackSelector(this).apply {
            // The prototype always takes the best rendition: the question is whether 1080p60 is smooth.
            setParameters(buildUponParameters().setForceHighestSupportedBitrate(true))
        }
        val exo = ExoPlayer.Builder(this).setTrackSelector(trackSelector).build()
        exo.addAnalyticsListener(DecoderLogger())
        playerView.player = exo
        player = exo

        scope.launch {
            val url = try {
                PurpleApp.instance.playback.liveStreamUrl(login)
            } catch (e: IOException) {
                Log.e(TAG, "cannot resolve $login", e)
                Toast.makeText(this@PlayerActivity, getString(R.string.stream_unavailable, login, e.message),
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            val source = HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
                .createMediaSource(MediaItem.fromUri(url))
            exo.setMediaSource(source)
            exo.prepare()
            exo.playWhenReady = true
            Log.i(TAG, "playing $login")
        }
        stats.postDelayed(logStats, STATS_INTERVAL_MS)
    }

    private fun release() {
        stats.removeCallbacks(logStats)
        playerView.player = null
        player?.release()
        player = null
    }

    private val logStats = object : Runnable {
        override fun run() {
            val exo = player ?: return
            exo.videoDecoderCounters?.let { c ->
                c.ensureUpdated()
                val f = exo.videoFormat
                Log.i(TAG, "stats ${f?.width}x${f?.height}@${f?.frameRate} rendered=${c.renderedOutputBufferCount} " +
                    "dropped=${c.droppedBufferCount} skipped=${c.skippedOutputBufferCount} " +
                    "maxConsecutiveDropped=${c.maxConsecutiveDroppedBufferCount}")
            }
            stats.postDelayed(this, STATS_INTERVAL_MS)
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
            Log.i(TAG, "format ${format.width}x${format.height}@${format.frameRate} ${format.codecs} ${format.bitrate}bps")
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            Log.w(TAG, "dropped $droppedFrames frames in ${elapsedMs}ms")
        }

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            Log.e(TAG, "playback error ${error.errorCodeName}", error)
        }
    }

    companion object {
        const val EXTRA_CHANNEL = "channel"
        private const val DEFAULT_CHANNEL = "pgl"
        private const val TAG = "PurpleTV"
        private const val STATS_INTERVAL_MS = 5_000L
    }
}
