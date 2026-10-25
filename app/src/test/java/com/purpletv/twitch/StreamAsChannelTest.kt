package com.purpletv.twitch

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamAsChannelTest {
    @Test
    fun aLiveStreamBecomesALiveChannel() {
        val s = Stream("1", "yotobi", "Yotobi", "La maratona", "g", "Games + Demos", 3152, "t")
        assertEquals(ChannelResult("yotobi", "Yotobi", true, "Games + Demos", "La maratona", "a.png"), s.asChannel("a.png"))
    }
}
