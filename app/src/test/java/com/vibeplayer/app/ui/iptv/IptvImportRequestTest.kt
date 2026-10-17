package com.vibeplayer.app.ui.iptv

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvImportRequestTest {
    @Test fun `a delivered result is consumed once even after leaving and returning to the page`() {
        val savedState = SavedStateHandle()
        val request = IptvImportRequest(savedState)
        assertTrue(request.begin("service-a", importing = false))
        assertEquals(IptvImportSelection("service-a", "content://playlist/a"), request.complete("content://playlist/a"))
        assertNull(request.complete("content://playlist/a"))
        // Navigation keeps/recreates the screen but must not resurrect its completed import.
        val returning = IptvImportRequest(savedState)
        assertFalse(returning.pending)
        repeat(5) { assertNull(returning.complete("content://playlist/a")) }
    }

    @Test fun `back cancels a picker request without reviving the previous import`() {
        val request = IptvImportRequest(SavedStateHandle())
        assertTrue(request.begin("service-a", importing = false))
        assertEquals("service-a", request.complete("content://playlist/a")?.serviceId)
        assertTrue(request.begin("service-a", importing = false))
        // Cancel the new picker with Back, then replay the old non-null result.
        assertNull(request.complete(null))
        assertFalse(request.pending)
        assertNull(request.complete("content://playlist/a"))
        assertTrue(request.begin("service-a", importing = false))
    }

    @Test fun `rapid taps and a running import cannot open another picker`() {
        val request = IptvImportRequest(SavedStateHandle())
        assertFalse(request.begin("service-a", importing = true))
        assertFalse(request.pending)
        assertTrue(request.begin("service-a", importing = false))
        repeat(5) { assertFalse(request.begin("service-a", importing = false)) }
        assertFalse(request.begin("service-b", importing = false))
        assertEquals("service-a", request.complete("content://playlist/a")?.serviceId)
    }

    @Test fun `activity recreation preserves the pending target but never repeats a consumed result`() {
        val saved = SavedStateHandle()
        assertTrue(IptvImportRequest(saved).begin("service-a", importing = false))
        val snapshot = saved.keys().associateWith { saved.get<Any?>(it) }
        val restoredState = SavedStateHandle(snapshot)
        val restored = IptvImportRequest(restoredState)
        assertTrue(restored.pending)
        assertEquals("service-a", restored.complete("content://playlist/a")?.serviceId)
        val afterResult = SavedStateHandle(restoredState.keys().associateWith { restoredState.get<Any?>(it) })
        assertNull(IptvImportRequest(afterResult).complete("content://playlist/a"))
    }

    @Test fun `a deliberate later import of the same service is still allowed`() {
        val request = IptvImportRequest(SavedStateHandle())
        repeat(3) {
            assertTrue(request.begin("service-a", importing = false))
            assertEquals("service-a", request.complete("content://playlist/a")?.serviceId)
            assertNull(request.complete("content://playlist/a"))
        }
    }
}
