package com.vibeplayer.app.ui.services

import com.vibeplayer.app.model.ServiceType
import org.junit.Assert.*
import org.junit.Test

class ServerAddressSecurityTest {
    @Test fun `new and schemeless addresses default to HTTPS`() {
        assertEquals("https", parseServerAddress("").scheme)
        assertEquals("https", parseServerAddress("media.example.test/dav").scheme)
        assertEquals("https://media.example.test:443/dav", buildServerBaseUrl("https", "media.example.test/dav", "443"))
        assertEquals("8920", defaultServerPort(ServiceType.EMBY, "https"))
    }

    @Test fun `explicit legacy HTTP is preserved for review without inferring consent`() {
        val old = parseServerAddress("http://192.0.2.10:8096/dav")
        assertEquals("http", old.scheme)
        assertEquals("8096", old.port)
        assertEquals("http://192.0.2.10:8096/dav", buildServerBaseUrl(old.scheme, old.host, old.port))
        assertFalse(com.vibeplayer.app.model.ServerConfig("old", "old", "http://192.0.2.10", "user").allowInsecureHttp)
    }
}
