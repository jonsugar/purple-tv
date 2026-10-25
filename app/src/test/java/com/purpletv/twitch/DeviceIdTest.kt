package com.purpletv.twitch

import com.purpletv.auth.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdTest {

    @Test
    fun makesOneAndKeepsIt() {
        val prefs = FakePrefs()

        val first = deviceId(prefs)

        assertTrue(first.matches(Regex("[A-Za-z0-9]{32}")))
        assertEquals(first, deviceId(prefs))
    }

    @Test
    fun eachInstallGetsItsOwn() {
        assertNotEquals(deviceId(FakePrefs()), deviceId(FakePrefs()))
    }
}
