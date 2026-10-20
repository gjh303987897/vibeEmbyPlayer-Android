package com.vibeplayer.app.player

import kotlin.math.roundToInt

enum class PipEntryBlock { NONE, UNSUPPORTED, PERMISSION, PRIVATE_VIDEO, NOT_PLAYING }

data class PipPlaybackEligibility(
    val supported: Boolean,
    val permissionAllowed: Boolean,
    val playerVisible: Boolean,
    val hasVideo: Boolean,
    val prepared: Boolean,
    val playing: Boolean,
    val privatePlayback: Boolean = false,
    val failed: Boolean = false,
    val sleepExpired: Boolean = false
) {
    val block: PipEntryBlock get() = when {
        !supported -> PipEntryBlock.UNSUPPORTED
        !permissionAllowed -> PipEntryBlock.PERMISSION
        privatePlayback -> PipEntryBlock.PRIVATE_VIDEO
        !playerVisible || !hasVideo || !prepared || !playing || failed || sleepExpired -> PipEntryBlock.NOT_PLAYING
        else -> PipEntryBlock.NONE
    }

    fun autoEnter(enabled: Boolean, modalOpen: Boolean) = enabled && !modalOpen && block == PipEntryBlock.NONE
}

data class PipAspectRatio(val width: Int, val height: Int)

/** The platform rejects ratios outside 1:2.39..2.39:1, including valid ultrawide media. */
fun pipAspectRatio(width: Int, height: Int, sarNum: Int = 1, sarDen: Int = 1): PipAspectRatio {
    if (width <= 0 || height <= 0) return PipAspectRatio(16, 9)
    val sar = if (sarNum > 0 && sarDen > 0) sarNum.toDouble() / sarDen else 1.0
    val ratio = width.toDouble() / height * sar
    return when {
        ratio <= 100.0 / 239 -> PipAspectRatio(100, 239)
        ratio >= 239.0 / 100 -> PipAspectRatio(239, 100)
        else -> PipAspectRatio((ratio * 1_000_000).roundToInt().coerceIn(418_411, 2_390_000), 1_000_000)
    }
}

data class PlayerPipState(
    val supported: Boolean = false,
    val entryBlock: PipEntryBlock = PipEntryBlock.UNSUPPORTED,
    val inPictureInPicture: Boolean = false,
    val entering: Boolean = false
) {
    val hideChrome: Boolean get() = inPictureInPicture || entering
    val canEnter: Boolean get() = entryBlock == PipEntryBlock.NONE && !hideChrome
}
