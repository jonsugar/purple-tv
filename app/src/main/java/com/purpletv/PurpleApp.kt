package com.purpletv

import android.app.Application
import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.PrefsTokenStore
import com.purpletv.auth.TokenStore
import com.purpletv.net.Http
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        http = Http.client()
        tokens = PrefsTokenStore(getSharedPreferences("purpletv", MODE_PRIVATE))
        playback = PlaybackApi(http, tokens)
        auth = DeviceAuth(http, HELIX_CLIENT_ID, tokens)
        helix = HelixClient(http, tokens, auth)
    }

    companion object {
        /** A Twitch application registered for Purple TV (public client, Device Code Flow). */
        const val HELIX_CLIENT_ID = "your-twitch-client-id"

        lateinit var instance: PurpleApp
    }
}
