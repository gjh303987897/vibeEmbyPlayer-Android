package com.vibeplayer.app.data.remote

import com.vibeplayer.app.model.ServiceType
import okhttp3.Headers

/**
 * Builds the Emby/Jellyfin Authentication headers.
 *
 * Emby uses the `Emby` scheme; Jellyfin uses the `MediaBrowser` scheme with an
 * `X-Emby-Token` compatibility header, matching the Qt reference.
 */
object EmbyAuth {

    private const val CLIENT = "VibePlayer"
    private const val DEVICE = "Android"
    const val DEVICE_ID = "vibe-player-android"
    private const val VERSION = "0.1.0"

    fun schemeFor(serviceType: ServiceType): String = when (serviceType) {
        ServiceType.EMBY -> "Emby"
        else -> "MediaBrowser"
    }

    /** Authorization header value with an optional access token. */
    fun authorization(scheme: String, token: String = ""): String {
        val base = "$scheme Client=\"$CLIENT\", Device=\"$DEVICE\", DeviceId=\"$DEVICE_ID\", Version=\"$VERSION\""
        return if (token.isEmpty()) base else "$base, Token=\"$token\""
    }

    /** Builds request headers for an authenticated (or login) request. */
    fun headers(serviceType: ServiceType, token: String = ""): Headers {
        val scheme = schemeFor(serviceType)
        val builder = Headers.Builder()
            .add("Authorization", authorization(scheme, token))
        if (token.isNotEmpty()) {
            builder.add("X-Emby-Token", token)
        }
        return builder.build()
    }
}
