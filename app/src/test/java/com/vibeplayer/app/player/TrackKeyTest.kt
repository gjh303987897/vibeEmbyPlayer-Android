package com.vibeplayer.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackKeyTest {

    @Test
    fun `valid track key resolves group and track indices`() {
        assertEquals(3 to 2, parseTrackKey("3:2"))
    }

    @Test
    fun `malformed or negative track keys are rejected`() {
        listOf("", "1", "1:2:3", "a:2", "1:b", "-1:0", "0:-1").forEach { key ->
            assertNull(key, parseTrackKey(key))
        }
    }
}
