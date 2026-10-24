package com.purpletv.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    private const val TIMEOUT_S = 15L

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()
}
