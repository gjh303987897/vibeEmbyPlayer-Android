package com.vibeplayer.app.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SleepTimerMode { OFF, COUNTDOWN, AFTER_VIDEO }

data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val remainingMs: Long = 0,
    val minutes: Int = 0,
    val expired: Boolean = false
)

/** Session-only timer. The owner supplies elapsedRealtime, ticks and the actual pause action. */
internal class SleepTimer(private val onExpired: () -> Unit) {
    private val _state = MutableStateFlow(SleepTimerState())
    val state = _state.asStateFlow()
    private var deadlineMs = 0L

    fun start(minutes: Int, nowMs: Long) {
        require(minutes in 1..1440)
        val now = nowMs.coerceAtLeast(0)
        val duration = minutes * 60_000L
        deadlineMs = now + duration.coerceAtMost(Long.MAX_VALUE - now)
        _state.value = SleepTimerState(SleepTimerMode.COUNTDOWN, deadlineMs - now, minutes)
    }

    fun afterVideo() { _state.value = SleepTimerState(SleepTimerMode.AFTER_VIDEO) }
    fun cancel() { deadlineMs = 0; _state.value = SleepTimerState() }

    fun tick(nowMs: Long) {
        if (_state.value.mode != SleepTimerMode.COUNTDOWN) return
        val remaining = (deadlineMs - nowMs.coerceAtLeast(0)).coerceAtLeast(0)
        if (remaining == 0L) expire() else _state.value = _state.value.copy(remainingMs = remaining)
    }

    fun onVideoCompleted() {
        when (_state.value.mode) {
            SleepTimerMode.AFTER_VIDEO -> expire()
            SleepTimerMode.COUNTDOWN -> cancel()
            SleepTimerMode.OFF -> Unit
        }
    }

    fun acknowledgeExpiration() { if (_state.value.expired) cancel() }

    private fun expire() {
        _state.value = SleepTimerState(expired = true)
        onExpired()
    }
}
