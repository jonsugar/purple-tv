package com.purpletv.player

import android.view.KeyEvent
import androidx.media3.common.Format
import kotlin.math.roundToInt

data class QualityOption(val label: String, val height: Int, val frameRate: Float)

private const val HIGH_FPS = 50f

/** The stream's video renditions as menu entries: "1080p60", "720p60", "480p"; best first, no duplicates. */
fun qualityOptions(formats: List<Format>): List<QualityOption> = formats
    .filter { it.height > 0 }
    .map { format ->
        val fps = if (format.frameRate > 0) format.frameRate else 0f
        val label = if (fps >= HIGH_FPS) "${format.height}p${fps.roundToInt()}" else "${format.height}p"
        QualityOption(label, format.height, fps)
    }
    .distinctBy { it.label }
    .sortedWith(compareByDescending<QualityOption> { it.height }.thenByDescending { it.frameRate })

/**
 * The rendition for a saved preference: the tallest one not above [maxHeight] (the smallest one
 * when all are taller), at the highest or lowest frame rate it comes in.
 */
fun pickQuality(options: List<QualityOption>, maxHeight: Int, preferHighFrameRate: Boolean): QualityOption? {
    if (options.isEmpty()) return null
    val allowed = options.filter { it.height <= maxHeight }.ifEmpty { listOf(options.minBy { it.height }) }
    val height = allowed.maxOf { it.height }
    val sameHeight = allowed.filter { it.height == height }
    return if (preferHighFrameRate) sameHeight.maxBy { it.frameRate } else sameHeight.minBy { it.frameRate }
}

/**
 * Whether the open quality menu keeps an arrow key for itself: the focus stays on its rows instead
 * of reaching the overlay buttons beneath, where one OK on Back would leave the channel. [row] is
 * the focused row, -1 when the focus is outside the list.
 */
fun menuKeepsKey(keyCode: Int, row: Int, rows: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
    KeyEvent.KEYCODE_DPAD_UP -> row <= 0
    KeyEvent.KEYCODE_DPAD_DOWN -> row < 0 || row >= rows - 1
    else -> false
}
