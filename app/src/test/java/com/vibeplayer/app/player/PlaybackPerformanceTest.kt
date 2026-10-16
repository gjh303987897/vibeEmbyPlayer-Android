package com.vibeplayer.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackPerformanceTest {
    @Test fun `CPU percentage accounts for all cores and rejects invalid samples`() {
        assertEquals(25f, cpuUsage(100, 2100, 1000, 3000, 4)!!, .001f)
        assertEquals(100f, cpuUsage(0, 9000, 0, 1000, 4)!!, .001f)
        assertNull(cpuUsage(100, 99, 1000, 2000, 4))
        assertNull(cpuUsage(100, 200, 1000, 1000, 4))
        assertNull(cpuUsage(100, 200, 1000, 2000, 0))
    }

    @Test fun `GPU sample uses busy time and total time instead of frequency`() {
        assertEquals(25f, parseGpuBusy("250 1000")!!, .001f)
        assertEquals(0f, parseGpuBusy("0 1000")!!, .001f)
        listOf("", "600000", "20 0", "110 100", "-1 100", "text 100", "1 100 extra")
            .forEach { assertNull(it, parseGpuBusy(it)) }
    }
}
