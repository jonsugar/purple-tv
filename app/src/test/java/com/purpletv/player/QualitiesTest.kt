package com.purpletv.player

import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualitiesTest {

    private fun video(width: Int, height: Int, fps: Float) =
        Format.Builder().setWidth(width).setHeight(height).setFrameRate(fps).build()

    @Test
    fun labelsAndSortsRenditions() {
        val options = qualityOptions(listOf(
            video(1280, 720, 60f), video(852, 480, 30f), video(1920, 1080, 60f), video(1280, 720, 60f),
        ))
        assertEquals(listOf("1080p60", "720p60", "480p"), options.map { it.label })
    }

    @Test
    fun ignoresAudioOnlyFormats() {
        val options = qualityOptions(listOf(Format.Builder().build(), video(640, 360, 30f)))
        assertEquals(listOf("360p"), options.map { it.label })
    }

    @Test
    fun picksTheBestRenditionUnderTheCap() {
        val options = qualityOptions(listOf(video(1920, 1080, 60f), video(1280, 720, 60f), video(1280, 720, 30f), video(852, 480, 30f)))
        assertEquals("720p60", pickQuality(options, maxHeight = 720, preferHighFrameRate = true)?.label)
        assertEquals("720p", pickQuality(options, maxHeight = 720, preferHighFrameRate = false)?.label)
        assertEquals("1080p60", pickQuality(options, maxHeight = Int.MAX_VALUE, preferHighFrameRate = true)?.label)
    }

    @Test
    fun capBelowEveryRenditionTakesTheSmallest() {
        val options = qualityOptions(listOf(video(1920, 1080, 60f), video(852, 480, 30f)))
        assertEquals("480p", pickQuality(options, maxHeight = 360, preferHighFrameRate = true)?.label)
        assertNull(pickQuality(emptyList(), maxHeight = 720, preferHighFrameRate = true))
    }
}
