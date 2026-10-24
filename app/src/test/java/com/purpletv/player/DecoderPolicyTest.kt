package com.purpletv.player

import androidx.media3.common.Format
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderPolicyTest {

    private fun video(width: Int, height: Int, bitrate: Int = 6_000_000) =
        Format.Builder().setWidth(width).setHeight(height).setAverageBitrate(bitrate).build()

    @Test
    fun aSmallerPictureNeedsAFreshDecoder() {
        assertTrue(needsFreshDecoder(video(1920, 1080), video(1280, 720)))
    }

    @Test
    fun aLargerPictureNeedsAFreshDecoder() {
        assertTrue(needsFreshDecoder(video(1280, 720), video(1920, 1080)))
    }

    @Test
    fun theSameSizeKeepsTheDecoder() {
        assertFalse(needsFreshDecoder(video(1920, 1080, 8_000_000), video(1920, 1080, 6_000_000)))
    }
}
