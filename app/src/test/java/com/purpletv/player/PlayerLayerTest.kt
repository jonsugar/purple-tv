package com.purpletv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerLayerTest {
    @Test
    fun startingShowsTheTuneIn() {
        assertEquals(PlayerLayer.TUNING, nextLayer(PlayerLayer.VIDEO, PlayerEvent.Started))
    }

    @Test
    fun theFirstFrameRevealsTheVideo() {
        assertEquals(PlayerLayer.VIDEO, nextLayer(PlayerLayer.TUNING, PlayerEvent.FirstFrame))
    }

    @Test
    fun anOfflineChannelLeavesTheTuneIn() {
        assertEquals(PlayerLayer.OFFLINE, nextLayer(PlayerLayer.TUNING, PlayerEvent.Offline))
    }

    @Test
    fun aLostConnectionShowsReconnecting() {
        assertEquals(PlayerLayer.RECONNECTING, nextLayer(PlayerLayer.TUNING, PlayerEvent.ConnectionLost))
        assertEquals(PlayerLayer.RECONNECTING, nextLayer(PlayerLayer.VIDEO, PlayerEvent.ConnectionLost))
    }

    @Test
    fun theVideoReturnsAfterReconnecting() {
        assertEquals(PlayerLayer.VIDEO, nextLayer(PlayerLayer.RECONNECTING, PlayerEvent.FirstFrame))
    }

    @Test
    fun offlineStaysUntilANewStart() {
        assertEquals(PlayerLayer.OFFLINE, nextLayer(PlayerLayer.OFFLINE, PlayerEvent.FirstFrame))
    }
}
