package com.vibeplayer.app.player.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpByteRangeTest {
    @Test fun `parses bounded open and suffix ranges`() {
        assertEquals(2L..5L, parseHttpByteRange("bytes=2-5", 10))
        assertEquals(7L..9L, parseHttpByteRange("bytes=-3", 10))
        assertEquals(8L..9L, parseHttpByteRange("bytes=8-", 10))
        assertEquals(2L..9L, parseHttpByteRange("bytes=2-99", 10))
    }

    @Test fun `rejects malformed and unsatisfiable ranges`() {
        listOf("bytes=-", "bytes=10-", "bytes=8-2", "items=0-1", "bytes=0-1,3-4")
            .forEach { assertNull(it, parseHttpByteRange(it, 10)) }
        assertNull(parseHttpByteRange("bytes=0-1", 0))
    }
}
