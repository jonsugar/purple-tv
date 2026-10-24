package com.purpletv.chat

data class ChatMessage(val user: String, val color: String?, val text: String)

sealed interface IrcEvent {
    data class Message(val message: ChatMessage) : IrcEvent
    data class Ping(val payload: String) : IrcEvent
    object Other : IrcEvent
}

/** Reads the few Twitch IRC lines a read-only chat needs: chat messages and server pings. */
object IrcParser {
    private const val ACTION = "\u0001ACTION "

    fun parse(line: String): IrcEvent {
        if (line.startsWith("PING ")) return IrcEvent.Ping(line.removePrefix("PING ").removePrefix(":"))
        var rest = line
        var tags = emptyMap<String, String>()
        if (rest.startsWith("@")) {
            val space = rest.indexOf(' ').takeIf { it > 0 } ?: return IrcEvent.Other
            tags = rest.substring(1, space).split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            rest = rest.substring(space + 1)
        }
        // :login!login@login.tmi.twitch.tv PRIVMSG #channel :text, where the text may hold colons.
        val parts = rest.split(' ', limit = 4)
        if (parts.size < 4 || !parts[0].startsWith(":") || parts[1] != "PRIVMSG") return IrcEvent.Other
        val login = parts[0].removePrefix(":").substringBefore('!')
        var text = parts[3].removePrefix(":")
        if (text.startsWith(ACTION) && text.endsWith("\u0001")) text = text.removePrefix(ACTION).removeSuffix("\u0001")
        val user = tags["display-name"].orEmpty().ifEmpty { login }
        return IrcEvent.Message(ChatMessage(user, tags["color"]?.ifEmpty { null }, text))
    }
}
