package com.vibeplayer.app.data.local.datastore

import com.vibeplayer.app.model.ServerConfig
import org.junit.Assert.*
import org.junit.Test

class ServiceOrderTest {
    @Test fun `reordering visible services retains hidden and newly inserted services`() {
        val first = server("a")
        val hidden = server("private").copy(privateMode = true)
        val last = server("b").copy(name = "Latest edited name")
        val inserted = server("new")
        val reordered = reorderExistingServices(listOf(first, hidden, last, inserted), listOf("b", "deleted", "b", "a"))
        assertEquals(listOf(last, hidden, first, inserted), reordered)
    }

    @Test fun `empty or stale sort cannot remove services`() {
        val services = listOf(server("private").copy(privateMode = true), server("a"))
        assertEquals(services, reorderExistingServices(services, emptyList()))
        assertEquals(services, reorderExistingServices(services, listOf("deleted")))
    }

    private fun server(id: String) = ServerConfig(id, id, "https://example.test", "user")
}
