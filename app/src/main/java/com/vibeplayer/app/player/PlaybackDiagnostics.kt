package com.vibeplayer.app.player

data class PlaybackDiagnostics(val outputFps: Float? = null, val networkBytesPerSecond: Long? = null)

/** Uses a separate cumulative counter, leaving history accounting untouched. */
class PlaybackDiagnosticSampler {
    private var previousTime: Long? = null
    private var previousBytes: Long? = null

    fun sample(nowMs: Long, bytes: Long?, outputFps: Float?): PlaybackDiagnostics {
        val lastTime = previousTime
        val lastBytes = previousBytes
        val speed = if (bytes != null && lastBytes != null && bytes >= lastBytes &&
            lastTime != null && nowMs > lastTime) {
            ((bytes - lastBytes).toDouble() * 1000 / (nowMs - lastTime)).toLong().coerceAtLeast(0)
        } else null
        previousTime = nowMs
        previousBytes = bytes?.takeIf { it >= 0 }
        return PlaybackDiagnostics(outputFps?.takeIf { it.isFinite() && it >= 0f }, speed)
    }
}
