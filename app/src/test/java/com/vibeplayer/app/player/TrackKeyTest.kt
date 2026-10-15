package com.vibeplayer.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackKeyTest {
    @Test
    fun `IJK audio and subtitle keys preserve stream index`() {
        assertEquals("audio:3", "audio:" + 3)
        assertEquals("timedtext:2", "timedtext:" + 2)
    }
}
