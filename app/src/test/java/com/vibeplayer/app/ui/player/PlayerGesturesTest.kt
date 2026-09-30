package com.vibeplayer.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGesturesTest {
    @Test
    fun `right swipe advances and left swipe rewinds`() {
        val duration = 600_000L
        assertEquals(150_000L, swipeSeekTarget(120_000L, duration, 200f, 400f))
        assertEquals(90_000L, swipeSeekTarget(120_000L, duration, -200f, 400f))
    }

    @Test
    fun `seek stays within video bounds`() {
        assertEquals(0L, swipeSeekTarget(5_000L, 600_000L, -400f, 400f))
        assertEquals(600_000L, swipeSeekTarget(595_000L, 600_000L, 400f, 400f))
    }

    @Test
    fun `unknown duration cannot produce a seek target`() {
        assertEquals(12_000L, swipeSeekTarget(12_000L, 0L, 200f, 400f))
    }
}
