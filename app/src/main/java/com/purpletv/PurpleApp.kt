package com.purpletv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.PrefsTokenStore
import com.purpletv.auth.TokenStore
import com.purpletv.home.HomeSnapshot
import com.purpletv.net.Http
import com.purpletv.player.PlayerPrefs
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.HomeApi
import com.purpletv.twitch.PlaybackApi
import com.purpletv.twitch.deviceId
import okhttp3.OkHttpClient

/** Holds the app-wide singletons; screens reach them through [instance]. */
class PurpleApp : Application(), ImageLoaderFactory {
    lateinit var http: OkHttpClient
    lateinit var tokens: TokenStore
    lateinit var playback: PlaybackApi
    lateinit var home: HomeApi
    lateinit var auth: DeviceAuth
    lateinit var helix: HelixClient
    lateinit var playerPrefs: PlayerPrefs
    val snapshot = HomeSnapshot()

    override fun onCreate() {
        super.onCreate()
        instance = this
        http = Http.client()
        tokens = PrefsTokenStore(getSharedPreferences("purpletv", MODE_PRIVATE))
        auth = DeviceAuth(http, TV_CLIENT_ID, SCOPES, tokens)
        playback = PlaybackApi(http, auth)
        home = HomeApi(http, auth, deviceId = deviceId(getSharedPreferences("purpletv", MODE_PRIVATE)))
        helix = HelixClient(http, tokens, auth)
        playerPrefs = PlayerPrefs(getSharedPreferences("player", MODE_PRIVATE))
    }

    /** Pictures fade in briefly; Coil skips the fade for images already in its memory cache. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this).crossfade(IMAGE_FADE_MS).build()

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
