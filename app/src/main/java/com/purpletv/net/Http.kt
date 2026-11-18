package com.purpletv.net

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    private const val API_TIMEOUT_S = 15L
    private const val MEDIA_TIMEOUT_S = 8L

    fun client(debugMode: DebugMode): OkHttpClient = builder(debugMode)
        .connectTimeout(API_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(API_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    fun imageCalls(debugMode: DebugMode): Call.Factory {
        val client = builder(debugMode).build()
        return Call.Factory { ImageCall(client.newCall(it)) }
    }

    @UnstableApi
    fun mediaFactory(debugMode: DebugMode): OkHttpDataSource.Factory {
        val client = builder(debugMode)
            .connectTimeout(MEDIA_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(MEDIA_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
        return OkHttpDataSource.Factory(client)
    }

    private fun builder(debugMode: DebugMode) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            debugMode.checkNetworking()
            chain.proceed(chain.request())
        }
        .eventListener(object : EventListener() {
            // Keep the call registered until its body ends, even after headers have arrived.
            override fun callStart(call: Call) = debugMode.callStarted(call)
            override fun callEnd(call: Call) = debugMode.callFinished(call)
            override fun callFailed(call: Call, ioe: IOException) = debugMode.callFinished(call)
        })

    // Coil ignores failures for calls it cancelled. An outage also cancels the underlying socket,
    // but Coil still needs the failure so it can finish the request and show its normal error state.
    private class ImageCall(private val call: Call) : Call by call {
        @Volatile private var cancelledByCoil = false

        override fun cancel() {
            cancelledByCoil = true
            call.cancel()
        }

        override fun isCanceled() = cancelledByCoil

        override fun clone(): Call = ImageCall(call.clone())

        override fun enqueue(responseCallback: Callback) {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = responseCallback.onFailure(this@ImageCall, e)
                override fun onResponse(call: Call, response: Response) = responseCallback.onResponse(this@ImageCall, response)
            })
        }
    }
}
