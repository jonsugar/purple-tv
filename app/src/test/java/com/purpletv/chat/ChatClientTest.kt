package com.purpletv.chat

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

class ChatClientTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun handshakeAndPong() {
        val received = LinkedBlockingQueue<String>()
        val opened = CountDownLatch(1)
        var twitch: WebSocket? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                twitch = webSocket
                opened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                text.split("\r\n").filter { it.isNotEmpty() }.forEach(received::add)
            }
        }))
        server.start()
        val messages = LinkedBlockingQueue<ChatMessage>()
        val client = ChatClient(OkHttpClient(), "PGL", server.url("/").toString().replace("http", "ws"),
            deliver = Executor { it.run() }) { messages.add(it) }

        client.connect()

        assertEquals("CAP REQ :twitch.tv/tags twitch.tv/commands", received.poll(5, SECONDS))
        assertEquals("PASS SCHMOOPIIE", received.poll(5, SECONDS))
        assertTrue(received.poll(5, SECONDS)!!.matches(Regex("NICK justinfan\\d+")))
        assertEquals("JOIN #pgl", received.poll(5, SECONDS))
        assertTrue(opened.await(5, SECONDS))

        twitch!!.send("PING :tmi.twitch.tv\r\n")
        assertEquals("PONG :tmi.twitch.tv", received.poll(5, SECONDS))

        // Twitch can pack several lines into one frame.
        twitch!!.send(":tmi.twitch.tv 001 justinfan1 :Welcome\r\n" +
            "@color=#FF0000;display-name=Mario :mario!mario@mario.tmi.twitch.tv PRIVMSG #pgl :ciao\r\n")
        assertEquals(ChatMessage("Mario", "#FF0000", "ciao"), messages.poll(5, SECONDS))
        assertNull(messages.poll(300, MILLISECONDS))

        client.close()
    }
}
