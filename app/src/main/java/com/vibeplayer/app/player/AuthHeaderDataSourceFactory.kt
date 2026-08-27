package com.vibeplayer.app.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.vibeplayer.app.di.OkHttpClientFactory

/**
 * Media3 HTTP factory that applies request headers and the current server's
 * explicit TLS policy to playback requests. A self-signed opt-in selects the
 * isolated permissive OkHttp client; all other playback keeps platform TLS
 * verification. This is mutable because the app owns one shared ExoPlayer.
 */
@OptIn(UnstableApi::class)
class AuthHeaderDataSourceFactory(
    private val clientFactory: OkHttpClientFactory,
    private val userAgent: String = "VibePlayer"
) : DataSource.Factory {

    private var headers: Map<String, String> = emptyMap()
    private var trustSelfSignedCertificate: Boolean = false

    @Synchronized
    fun configure(
        newHeaders: Map<String, String>,
        trustSelfSignedCertificate: Boolean
    ) {
        headers = newHeaders.toMap()
        this.trustSelfSignedCertificate = trustSelfSignedCertificate
    }

    @Synchronized
    override fun createDataSource(): DataSource =
        OkHttpDataSource.Factory(clientFactory.client(trustSelfSignedCertificate))
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .createDataSource()
}
