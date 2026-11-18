package com.purpletv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.PrefsTokenStore
import com.purpletv.auth.TokenStore
import com.purpletv.home.HomeSnapshot
import com.purpletv.home.GhostFollowStore
import com.purpletv.home.PinStore
import com.purpletv.home.StreamPreferences
import com.purpletv.net.DebugMode
import com.purpletv.net.Http
import com.purpletv.player.PlayerPrefs
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.HomeApi
import com.purpletv.twitch.FollowingApi
import com.purpletv.twitch.RecordedPlaybackApi
import com.purpletv.twitch.TwitchGraphQl
import com.purpletv.twitch.FollowApi
import com.purpletv.twitch.FollowChanges
import com.purpletv.twitch.PlaybackApi
import com.purpletv.player.ZapList
import com.purpletv.twitch.deviceId
import com.purpletv.ui.DebugIndicator
import okhttp3.OkHttpClient

/** Holds the app-wide singletons; screens reach them through [instance]. */
class PurpleApp : Application(), ImageLoaderFactory {
    val debugMode = DebugMode()
    lateinit var http: OkHttpClient
    lateinit var tokens: TokenStore
    lateinit var playback: PlaybackApi
    lateinit var home: HomeApi
    lateinit var following: FollowingApi
    lateinit var follows: FollowApi
    lateinit var pins: PinStore
    lateinit var ghostFollows: GhostFollowStore
    lateinit var streamPrefs: StreamPreferences
    val followChanges = FollowChanges()
    lateinit var recordedPlayback: RecordedPlaybackApi
    lateinit var auth: DeviceAuth
    lateinit var helix: HelixClient
    lateinit var playerPrefs: PlayerPrefs
    val snapshot = HomeSnapshot()
    /** The row the last stream was opened from, for the player's Channels strip. */
    var zap: ZapList? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        DebugIndicator(this, debugMode)
        http = Http.client(debugMode)
        tokens = PrefsTokenStore(getSharedPreferences("purpletv", MODE_PRIVATE))
        auth = DeviceAuth(http, TV_CLIENT_ID, SCOPES, tokens)
        playback = PlaybackApi(http, auth)
        home = HomeApi(http, auth, deviceId = deviceId(getSharedPreferences("purpletv", MODE_PRIVATE)))
        helix = HelixClient(http, tokens, auth)
        val gql = TwitchGraphQl(http, auth)
        following = FollowingApi(gql)
        pins = PinStore(getSharedPreferences("favourite_pins", MODE_PRIVATE))
        ghostFollows = GhostFollowStore(getSharedPreferences("ghost_follows", MODE_PRIVATE))
        streamPrefs = StreamPreferences(getSharedPreferences("live_streams", MODE_PRIVATE))
        follows = FollowApi(gql, followChanges, pins::remove)
        recordedPlayback = RecordedPlaybackApi(http, gql)
        playerPrefs = PlayerPrefs(getSharedPreferences("player", MODE_PRIVATE))
    }

    /** Pictures fade in briefly; Coil skips the fade for images already in its memory cache. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .callFactory { Http.imageCalls(debugMode) }
        .crossfade(IMAGE_FADE_MS)
        .build()

    companion object {
        /**
         * Twitch's own TV client. Signing in with it gives one token for Helix and for playback,
         * where Twitch applies the user's subscriptions. Twitch doesn't offer this client to third
         * parties, so Purple TV is meant for personal use.
         */
        const val TV_CLIENT_ID = "ue6666qo983tsx6so1t0vnawi233wa"
        const val SCOPES = "user_read user:read:follows"
        const val IMAGE_FADE_MS = 120

        lateinit var instance: PurpleApp
    }
}
