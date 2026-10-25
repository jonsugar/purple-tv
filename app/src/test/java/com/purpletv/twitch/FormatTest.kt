package com.purpletv.twitch

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatTest {

    @Test
    fun sizesBraceTemplates() {
        assertEquals("https://x/440x248.jpg", sized("https://x/{width}x{height}.jpg", 440, 248))
    }

    @Test
    fun sizesPercentTemplates() {
        assertEquals("https://x/285x380.jpg", sized("https://x/%{width}x%{height}.jpg", 285, 380))
    }

    @Test
    fun formatsViewerCounts() {
        assertEquals("950", formatViewers(950))
        assertEquals("1.2K", formatViewers(1234))
        assertEquals("12.3K", formatViewers(12345))
        assertEquals("1.2M", formatViewers(1234567))
    }

    @Test
    fun formatsViewerCountsInItalian() {
        assertEquals("1,2K", formatViewers(1234, decimalSeparator = ',', million = " Mln"))
        assertEquals("1,2 Mln", formatViewers(1234567, decimalSeparator = ',', million = " Mln"))
    }

    @Test
    fun writesFullCountsInTheLanguage() {
        assertEquals("32.534", fullCount(32534, Locale.ITALY))
        assertEquals("32,534", fullCount(32534, Locale.US))
    }

    @Test
    fun dropsZeroDecimal() {
        assertEquals("1K", formatViewers(1000))
    }
}
