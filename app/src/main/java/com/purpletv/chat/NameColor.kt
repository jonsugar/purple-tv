package com.purpletv.chat

import kotlin.math.pow
import kotlin.math.roundToInt

private const val MIN_CONTRAST = 4.5
private const val MIX_STEPS = 10
private val HEX_COLOR = Regex("#[0-9A-Fa-f]{6}")

/** WCAG 2 contrast ratio between two opaque colours, from 1 to 21. */
fun contrastRatio(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * A chat name's colour, readable on the chat's background: Twitch lets people pick dark blues and
 * reds, so those are mixed toward white in 10% steps until they reach a 4.5:1 contrast. No colour,
 * or one that is not `#RRGGBB`, gets [fallback].
 */
fun readableNameColor(hex: String?, fallback: Int, background: Int = 0xFF18181B.toInt()): Int {
    if (hex == null || !HEX_COLOR.matches(hex)) return fallback
    val color = (0xFF000000 or hex.substring(1).toLong(16)).toInt()
    for (step in 0..MIX_STEPS) {
        val mixed = towardWhite(color, step / MIX_STEPS.toDouble())
        if (contrastRatio(mixed, background) >= MIN_CONTRAST) return mixed
    }
    return towardWhite(color, 1.0)
}

private fun towardWhite(color: Int, amount: Double): Int {
    fun channel(shift: Int): Int {
        val c = (color shr shift) and 0xFF
        return (c + (0xFF - c) * amount).roundToInt()
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun luminance(color: Int): Double {
    fun linear(shift: Int): Double {
        val c = ((color shr shift) and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
}
