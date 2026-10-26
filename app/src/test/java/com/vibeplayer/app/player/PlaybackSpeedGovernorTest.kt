package com.vibeplayer.app.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackSpeedGovernorTest {
    @Test fun veryLowAutomaticRatesUseNewTimelinesOnEntryAndExit() {
        assertTrue(lowRateNeedsResync(2f, .2f))
        assertTrue(lowRateNeedsResync(.2f, 1f))
        assertTrue(lowRateNeedsResync(.125f, .2f))
        assertFalse(lowRateNeedsResync(.2f, .2f))
        assertFalse(lowRateNeedsResync(2f, 1f))
    }
    @Test fun sustained4k60PressureReduces2xToSustainable1point5x() {
        val governor = PlaybackSpeedGovernor().apply { reset(0) }
        assertNull(governor.sample(1_000, 2f, 60.0, 105f, -1f, true))
        for (now in listOf(2_500L, 3_000L, 3_500L))
            assertNull(governor.sample(now, 2f, 60.0, 105f, -.4f, true))
        assertEquals(1.5f, governor.sample(4_000, 2f, 60.0, 105f, -.4f, true))
        assertNull(governor.sample(7_000, 1.5f, 60.0, 105f, -.4f, true))
    }

    @Test fun bufferingOrRecoveredSyncBreaksThePressureWindow() {
        val governor = PlaybackSpeedGovernor()
        repeat(3) { assertNull(governor.sample(it * 500L, 2f, 60.0, 100f, -.6f, true)) }
        assertNull(governor.sample(1_500, 2f, 60.0, 100f, -.6f, false))
        repeat(3) { assertNull(governor.sample(2_000L + it * 500L, 2f, 60.0, 100f, -.6f, true)) }
        assertNull(governor.sample(4_000, 2f, 60.0, 100f, 0f, true))
    }

    @Test fun missingMetricsAndVideoLeadingAudioNeverGuessDecoderPressure() {
        val governor = PlaybackSpeedGovernor()
        repeat(8) {
            assertNull(governor.sample(it * 500L, 2f, null, 10f, -1f, true))
            assertNull(governor.sample(it * 500L, 2f, 60.0, Float.NaN, -1f, true))
            assertNull(governor.sample(it * 500L, 2f, 60.0, 10f, 1f, true))
        }
    }

    @Test fun verySlowDecoderRetainsHeadroomBelowQuarterSpeed() {
        val governor = PlaybackSpeedGovernor()
        var result: Float? = null
        repeat(4) { result = governor.sample(it * 500L, 2f, 60.0, 15f, -1f, true) }
        assertEquals(.2f, result)
        repeat(4) { result = governor.sample(10_000L + it * 500L, .2f, 60.0, 5f, -1f, true) }
        assertEquals(.1f, result)
        repeat(8) { assertNull(governor.sample(20_000L + it * 500L, .1f, 60.0, 5f, -1f, true)) }
    }

    @Test fun expensiveRenderingIsLimitedSeparatelyFromRawDecodeSpeedAndDisplayCap() {
        val governor = PlaybackSpeedGovernor()
        var result: Float? = null
        repeat(4) { result = governor.sample(it * 500L, .2f, 60.0, 12.5f, -.04f, true, 7.8f, 60f) }
        assertEquals(.1f, result)
        repeat(8) {
            assertNull(governor.sample(10_000L + it * 500L, 2f, 60.0, 120f, -.3f, true, 60f, 60f))
        }
    }
}
