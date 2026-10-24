package com.purpletv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MetaLineTest {

    @Test
    fun joinsGameUptimeAndViewersLikeTheOfficialApp() {
        assertEquals("Counter-Strike | 10:49:19 | 32.534 spettatori", metaLine("Counter-Strike", "10:49:19", "32.534 spettatori"))
    }

    @Test
    fun leavesOutWhatIsMissing() {
        assertEquals("32.534 spettatori", metaLine("", "", "32.534 spettatori"))
        assertEquals("Just Chatting | 380 spettatori", metaLine("Just Chatting", "", "380 spettatori"))
    }
}
