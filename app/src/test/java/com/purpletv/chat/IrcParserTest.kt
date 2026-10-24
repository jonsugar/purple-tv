package com.purpletv.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class IrcParserTest {

    @Test
    fun parsesPrivmsgWithTags() {
        val line = "@badge-info=;color=#FF0000;display-name=Mario;emotes= :mario!mario@mario.tmi.twitch.tv PRIVMSG #pgl :ciao a tutti"
        assertEquals(IrcEvent.Message(ChatMessage("Mario", "#FF0000", "ciao a tutti")), IrcParser.parse(line))
    }

    @Test
    fun fallsBackToLoginWithoutDisplayName() {
        val line = "@color=;display-name= :mario!mario@mario.tmi.twitch.tv PRIVMSG #pgl :ciao"
        assertEquals(IrcEvent.Message(ChatMessage("mario", null, "ciao")), IrcParser.parse(line))
    }

    @Test
    fun keepsColonsInText() {
        val line = "@color=#00FF00;display-name=Luigi :luigi!luigi@luigi.tmi.twitch.tv PRIVMSG #pgl :ore 10:30"
        assertEquals("ore 10:30", (IrcParser.parse(line) as IrcEvent.Message).message.text)
    }

    @Test
    fun unwrapsMeActions() {
        val line = "@display-name=Luigi :luigi!luigi@luigi.tmi.twitch.tv PRIVMSG #pgl :\u0001ACTION saluta\u0001"
        assertEquals("saluta", (IrcParser.parse(line) as IrcEvent.Message).message.text)
    }

    @Test
    fun parsesPing() {
        assertEquals(IrcEvent.Ping("tmi.twitch.tv"), IrcParser.parse("PING :tmi.twitch.tv"))
    }

    @Test
    fun ignoresEverythingElse() {
        assertEquals(IrcEvent.Other, IrcParser.parse(":tmi.twitch.tv 001 justinfan123 :Welcome, GLHF!"))
        assertEquals(IrcEvent.Other, IrcParser.parse(""))
    }
}
