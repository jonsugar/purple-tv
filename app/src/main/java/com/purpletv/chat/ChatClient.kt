package com.purpletv.chat

import android.os.Handler
import android.os.Looper
import com.purpletv.player.RetryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executor
import kotlin.random.Random

/**
 * Read-only Twitch chat over IRC on a WebSocket, as an anonymous "justinfan" user: no login and
 * nothing is ever sent to the channel. Messages reach [onMessage] through [deliver] (the main
 * thread by default); a dropped connection is retried with backoff until [close].
 */
class ChatClient(
    private val http: OkHttpClient,
    channel: String,
    private val url: String = "wss://irc-ws.chat.twitch.tv:443",
    private val deliver: Executor = MAIN_THREAD,
    private val onMessage: (ChatMessage) -> Unit,
) {
    private val channel = channel.trim().lowercase()
    private val retry = RetryPolicy()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var attempts = 0

    fun connect() {
        if (closed) return
        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    fun close() {
        closed = true
        scope.cancel()
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempts = 0
            webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
            webSocket.send("PASS SCHMOOPIIE")
            webSocket.send("NICK justinfan${Random.nextInt(10_000, 100_000)}")
            webSocket.send("JOIN #$channel")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Twitch packs several lines into one frame.
            text.split("\r\n").filter { it.isNotEmpty() }.forEach { line ->
                when (val event = IrcParser.parse(line)) {
                    is IrcEvent.Ping -> webSocket.send("PONG :${event.payload}")
                    is IrcEvent.Message -> deliver.execute { if (!closed) onMessage(event.message) }
                    IrcEvent.Other -> Unit
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconnect(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reconnect(webSocket)
    }

    private fun reconnect(dropped: WebSocket) {
        if (closed || dropped !== socket) return
        val wait = retry.delayFor(attempts++)
        scope.launch { delay(wait); connect() }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        val MAIN_THREAD = Executor { Handler(Looper.getMainLooper()).post(it) }
    }
}
