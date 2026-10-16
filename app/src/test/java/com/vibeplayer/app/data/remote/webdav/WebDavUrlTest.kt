package com.vibeplayer.app.data.remote.webdav

import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebDavUrlTest {
    private val client = WebDavClient(OkHttpClientFactory())
    private val server = ServerConfig("dav", "DAV", "https://media.example/dav/", "user", ServiceType.WEBDAV)

    @Test
    fun `playback preserves decoded filenames beneath the service base`() {
        val path = "视频/第 1 集 #1?备份%2F +.mkv"
        val url = client.resolveUrl(server, path).toHttpUrl()
        assertEquals(listOf("dav", "视频", "第 1 集 #1?备份%2F +.mkv"), url.pathSegments)
        assertNull(url.query)
        assertNull(url.fragment)
    }

    @Test
    fun `base path is retained for nested files and root`() {
        assertEquals("https://media.example/dav/folder/movie.mp4", client.resolveUrl(server, "/folder/movie.mp4"))
        assertEquals("https://media.example/dav", client.resolveUrl(server, "/"))
    }
}
