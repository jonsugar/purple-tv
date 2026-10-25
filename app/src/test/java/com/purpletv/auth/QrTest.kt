package com.purpletv.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrTest {
    @Test
    fun encodesTheActivationLinkWithFinderPatterns() {
        val m = qrModules("https://www.twitch.tv/activate?public=true&device-code=ABCDEFGH")
        assertEquals(m.size, m[0].size)
        assertTrue(m.size >= 23)
        assertTrue((1..7).all { m[1][it] && m[it][1] }) // top-left finder pattern after the 1-module margin
        assertFalse(m[0][0]) // the margin is light
    }

    @Test
    fun theLinkCarriesTheCodeSoThePhoneNeedsNoTyping() {
        assertEquals(
            "https://www.twitch.tv/activate?public=true&device-code=ABCDEFGH",
            activationLink("https://www.twitch.tv/activate", "ABCDEFGH"),
        )
        // A link that already carries it is left alone.
        assertEquals(
            "https://www.twitch.tv/activate?public=true&device-code=XYZ",
            activationLink("https://www.twitch.tv/activate?public=true&device-code=XYZ", "XYZ"),
        )
    }
}
