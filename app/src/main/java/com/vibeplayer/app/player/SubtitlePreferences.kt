package com.vibeplayer.app.player

/** Appearance persists globally; timing is scoped to the current playback session. */
data class SubtitleAppearance(
    val fontSizeSp: Int = 16,
    val bottomOffsetDp: Int = 0,
    val backgroundOpacityPercent: Int = 65
) {
    fun normalized() = copy(
        fontSizeSp = fontSizeSp.coerceIn(12, 36),
        bottomOffsetDp = bottomOffsetDp.coerceIn(0, 120),
        backgroundOpacityPercent = backgroundOpacityPercent.coerceIn(0, 100)
    )
}

const val MAX_SUBTITLE_DELAY_MS = 10_000L

/** Positive delay displays later. Negative positions must stay negative until the first cue. */
fun subtitlePositionMs(positionMs: Long, delayMs: Long): Long {
    val delay = delayMs.coerceIn(-MAX_SUBTITLE_DELAY_MS, MAX_SUBTITLE_DELAY_MS)
    val position = positionMs.coerceAtLeast(0)
    return if (delay < 0 && position > Long.MAX_VALUE + delay) Long.MAX_VALUE else position - delay
}
