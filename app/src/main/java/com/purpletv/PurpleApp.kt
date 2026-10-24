package com.purpletv

import android.app.Application
import com.purpletv.auth.PrefsTokenStore
import com.purpletv.auth.TokenStore
import com.purpletv.net.Http
import com.purpletv.twitch.PlaybackApi
import okhttp3.OkHttpClient

/** Holds the app-wide singletons; screens reach them through [instance]. */
class PurpleApp : Application() {
    lateinit var http: OkHttpClient
    lateinit var tokens: TokenStore
    lateinit var playback: PlaybackApi

    override fun onCreate() {
        super.onCreate()
        instance = this
        http = Http.client()
        tokens = PrefsTokenStore(getSharedPreferences("purpletv", MODE_PRIVATE))
        playback = PlaybackApi(http, tokens)
    }

    companion object {
        lateinit var instance: PurpleApp
    }
}
