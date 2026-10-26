package com.vibeplayer.app.player

data class PlaybackSpeedAdjustment(val requested: Float, val actual: Float)

internal fun lowRateNeedsResync(previous: Float, next: Float): Boolean =
    previous != next && (previous < .25f || next < .25f)

/** Reacts to sustained decoder pressure, never to one late frame or a network stall. */
internal class PlaybackSpeedGovernor {
    private var readyAt = 0L
    private val samples = ArrayDeque<Float>()

    fun reset(now: Long) {
        readyAt = now + 2_500
        samples.clear()
    }

    fun sample(now: Long, speed: Float, sourceFps: Double?, decodeFps: Float,
               videoMinusAudio: Float, eligible: Boolean, outputFps: Float? = null, displayHz: Float = 60f): Float? {
        val fps = sourceFps?.takeIf { it.isFinite() && it > 0 } ?: return null
        val decodeLimited = decodeFps < fps * speed * .95
        val expectedOutput = minOf(fps * speed, displayHz.toDouble())
        val renderLimited = outputFps != null && outputFps.isFinite() && outputFps > 0 &&
            outputFps < expectedOutput * .9
        if (!eligible || !decodeFps.isFinite() || decodeFps <= 0 ||
            !videoMinusAudio.isFinite() || videoMinusAudio / speed >= -.12f ||
            (!decodeLimited && !renderLimited)) {
            samples.clear()
            return null
        }
        if (now < readyAt) return null
        samples.addLast(((if (decodeLimited) decodeFps else requireNotNull(outputFps)) / fps * .90).toFloat())
        if (samples.size < 4) return null
        val capacity = samples.sorted()[samples.size / 2]
        val next = listOf(.1f, .125f, .2f, .25f, .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)
            .lastOrNull { it <= capacity && it < speed - .01f }
            ?: .1f.takeIf { speed > .1f }
        reset(now)
        return next
    }
}
