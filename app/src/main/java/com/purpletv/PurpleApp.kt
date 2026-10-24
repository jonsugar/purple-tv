package com.purpletv

import android.app.Application
import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.PrefsTokenStore
import com.purpletv.auth.TokenStore
import com.purpletv.net.Http
import com.purpletv.player.PlayerPrefs
import com.purpletv.twitch.HelixClient
import com.purpletv.twitch.PlaybackApi
import okhttp3.OkHttpClient

/** Holds the app-wide singletons; screens reach them through [instance]. */
class PurpleApp : Application() {
    lateinit var http: OkHttpClient
    lateinit var tokens: TokenStore
    lateinit var playback: PlaybackApi
    lateinit var auth: DeviceAuth
    lateinit var helix: HelixClient
    lateinit var playerPrefs: PlayerPrefs

    override fun onCreate() {
        super.onCreate()
        instance = this
        http = Http.client()
        tokens = PrefsTokenStore(getSharedPreferences("purpletv", MODE_PRIVATE))
        auth = DeviceAuth(http, TV_CLIENT_ID, SCOPES, tokens)
        playback = PlaybackApi(http, auth)
        helix = HelixClient(http, tokens, auth)
        playerPrefs = PlayerPrefs(getSharedPreferences("player", MODE_PRIVATE))
    }

    companion object {
        /**
         * Twitch's own TV client. Signing in with it gives one token for Helix and for playback,
         * where Twitch applies the user's subscriptions. Twitch doesn't offer this client to third
         * parties, so Purple TV is meant for personal use.
         */
        const val TV_CLIENT_ID = "ue6666qo983tsx6so1t0vnawi233wa"
        const val SCOPES = "user_read user:read:follows"

        lateinit var instance: PurpleApp
    }
}
