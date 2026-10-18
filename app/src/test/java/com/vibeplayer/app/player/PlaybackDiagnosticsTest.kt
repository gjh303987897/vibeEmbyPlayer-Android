package com.vibeplayer.app.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test fun usesElapsedTimeAndDoesNotReportSourceFps() {
        val sampler = PlaybackDiagnosticSampler()
        assertNull(sampler.sample(1000, 4000, 23.5f).networkBytesPerSecond)
        val next = sampler.sample(2500, 10_000, 14f)
        assertEquals(4000L, next.networkBytesPerSecond)
        assertEquals(14f, next.outputFps)
        assertEquals(0L, sampler.sample(3500, 10_000, 0f).networkBytesPerSecond)
    }

    @Test fun localPlaybackAndResetCountersDoNotProduceFalseRates() {
        val sampler = PlaybackDiagnosticSampler()
        assertNull(sampler.sample(1000, null, Float.NaN).networkBytesPerSecond)
        assertNull(sampler.sample(2000, null, Float.POSITIVE_INFINITY).outputFps)
        assertNull(sampler.sample(3000, 1000, -1f).networkBytesPerSecond)
        assertNull(sampler.sample(4000, 50, 24f).networkBytesPerSecond)
        assertEquals(50L, sampler.sample(5000, 100, 24f).networkBytesPerSecond)
        assertNull(sampler.sample(5000, 150, 24f).networkBytesPerSecond)
    }
}
