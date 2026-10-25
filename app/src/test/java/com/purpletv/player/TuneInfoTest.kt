package com.purpletv.player

import com.purpletv.twitch.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class TuneInfoTest {
    @Test
    fun tuneInUsesTheCardsThumbnailSize() {
        val s = Stream("9", "khatux", "Khatux", "AION 2 PARTNER", "g", "AION 2", 180, "https://x/live_user_khatux-{width}x{height}.jpg")
        assertEquals(
            TuneInfo("khatux", "Khatux", "AION 2 PARTNER", "AION 2", 180, "https://x/live_user_khatux-440x248.jpg", "a.png"),
            s.toTuneInfo("a.png"),
        )
    }
}
