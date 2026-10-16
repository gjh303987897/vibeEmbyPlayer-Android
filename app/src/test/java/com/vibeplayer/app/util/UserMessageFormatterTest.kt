package com.vibeplayer.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserMessageFormatterTest {
    @Test
    fun `native identifiers use a localized fallback instead of raw diagnostic text`() {
        val result = UserMessageFormatter.classify("IJK_ERROR_-10000_0")
        assertEquals(UserErrorKind.UNKNOWN, result.kind)
        assertNull(result.detail)
    }
}
