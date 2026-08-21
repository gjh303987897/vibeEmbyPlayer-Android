package com.vibeplayer.app.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * Http data source factory that injects a mutable set of request headers on
 * every request. Used to send Authorization headers (e.g. WebDAV Basic auth)
 * to ExoPlayer without exposing credentials in the play URL or logs.
 */
@OptIn(UnstableApi::class)
class AuthHeaderDataSourceFactory(
    userAgent: String = "VibePlayer"
) : DataSource.Factory {

    private val base = DefaultHttpDataSource.Factory().setUserAgent(userAgent)
    private val headers = mutableMapOf<String, String>()

    fun setHeaders(newHeaders: Map<String, String>) {
        headers.clear()
        headers.putAll(newHeaders)
    }

    override fun createDataSource(): DataSource {
        if (headers.isNotEmpty()) {
            base.setDefaultRequestProperties(headers)
        }
        return base.createDataSource()
    }
}
