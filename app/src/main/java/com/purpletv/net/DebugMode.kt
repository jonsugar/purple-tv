package com.purpletv.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException

/** Process-local debug settings and the transfers that an outage must interrupt. */
class DebugMode {
    data class State(val enabled: Boolean = false, val networkingDisabled: Boolean = false)

    private val settings = MutableStateFlow(State())
    val state = settings.asStateFlow()
    private val calls = mutableSetOf<Call>()
    private val sockets = mutableSetOf<WebSocket>()

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        settings.value = State(enabled, enabled && settings.value.networkingDisabled)
    }

    @Synchronized
    fun setNetworkingDisabled(disabled: Boolean) {
        val current = settings.value
        if (!current.enabled || current.networkingDisabled == disabled) return
        settings.value = current.copy(networkingDisabled = disabled)
        if (disabled) {
            calls.forEach { it.cancel() }
            sockets.forEach { it.cancel() }
        }
    }

    internal fun checkNetworking() {
        if (state.value.networkingDisabled) throw IOException("Networking disabled in Debug mode")
    }

    @Synchronized
    internal fun callStarted(call: Call) {
        if (state.value.networkingDisabled) call.cancel() else calls.add(call)
    }

    @Synchronized
    internal fun callFinished(call: Call) {
        calls.remove(call)
    }

    // OkHttp omits its EventListener from WebSocket calls. Track the socket through its handshake
    // and until the chat listener reports that the connection ended.
    @Synchronized
    internal fun openWebSocket(
        http: OkHttpClient,
        request: Request,
        listener: WebSocketListener,
    ): WebSocket? {
        if (state.value.networkingDisabled) return null
        return http.newWebSocket(request, listener).also(sockets::add)
    }

    @Synchronized
    internal fun webSocketFinished(socket: WebSocket) {
        sockets.remove(socket)
    }
}
