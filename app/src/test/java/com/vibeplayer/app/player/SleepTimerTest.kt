package com.vibeplayer.app.player

import org.junit.Assert.*
import org.junit.Test

class SleepTimerTest {
    @Test fun elapsedDeadlineExpiresOnceRegardlessOfTheMediaPositionOrWallClock() {
        var pauses = 0
        val timer = SleepTimer { pauses++ }
        timer.start(15, 500)
        timer.tick(60_500)
        assertEquals(14 * 60_000L, timer.state.value.remainingMs)
        assertEquals(0, pauses)
        timer.tick(900_500)
        timer.tick(950_500)
        assertEquals(1, pauses)
        assertTrue(timer.state.value.expired)
        assertEquals(SleepTimerMode.OFF, timer.state.value.mode)
    }

    @Test fun delayedTickAfterDeviceSleepUsesElapsedDeadlineInsteadOfTickCount() {
        var pauses = 0
        val timer = SleepTimer { pauses++ }
        timer.start(30, 1000)
        timer.tick(3_600_000)
        assertEquals(1, pauses)
        assertTrue(timer.state.value.expired)
    }

    @Test fun rearmingAndCancellingInvalidateTheOldDeadline() {
        var pauses = 0
        val timer = SleepTimer { pauses++ }
        timer.start(1, 0)
        timer.start(60, 30_000)
        timer.tick(60_000)
        assertEquals(0, pauses)
        assertEquals(60, timer.state.value.minutes)
        timer.cancel()
        timer.tick(Long.MAX_VALUE)
        assertEquals(0, pauses)
        assertEquals(SleepTimerState(), timer.state.value)
    }

    @Test fun afterVideoWaitsForCompletionAndReplacesTheCountdown() {
        var pauses = 0
        val timer = SleepTimer { pauses++ }
        timer.start(1, 0)
        timer.afterVideo()
        timer.tick(999_999)
        assertEquals(0, pauses)
        assertEquals(SleepTimerMode.AFTER_VIDEO, timer.state.value.mode)
        timer.onVideoCompleted()
        timer.onVideoCompleted()
        assertEquals(1, pauses)
        assertTrue(timer.state.value.expired)
        timer.acknowledgeExpiration()
        assertEquals(SleepTimerState(), timer.state.value)
    }

    @Test fun naturalCompletionCancelsRemainingCountdownWithoutPausingAgain() {
        var pauses = 0
        val timer = SleepTimer { pauses++ }
        timer.start(60, 0)
        timer.onVideoCompleted()
        timer.tick(Long.MAX_VALUE)
        assertEquals(0, pauses)
        assertEquals(SleepTimerState(), timer.state.value)
    }

    @Test fun customDurationBoundsRejectInvalidInputAndDeadlineDoesNotOverflow() {
        val timer = SleepTimer { }
        for (value in listOf(Int.MIN_VALUE, 0, 1441, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { timer.start(value, 0) }
        }
        timer.start(1440, 100)
        assertEquals(86_400_000L, timer.state.value.remainingMs)
        timer.start(1, Long.MAX_VALUE - 1000)
        assertEquals(1000L, timer.state.value.remainingMs)
        timer.tick(Long.MAX_VALUE)
        assertTrue(timer.state.value.expired)
    }
}
