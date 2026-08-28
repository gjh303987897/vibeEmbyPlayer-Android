package com.vibeplayer.app.ui.services

import com.vibeplayer.app.model.ServiceType
import java.net.URI

/**
 * The service editor keeps the URL scheme, host/path and port as separate
 * fields, while [ServerConfig] continues to persist one base URL for backward
 * compatibility with existing installs.
 */
data class ServerAddressParts(
    val scheme: String,
    val host: String,
    val port: String
)

fun defaultServerPort(serviceType: ServiceType, scheme: String): String = when (serviceType) {
    ServiceType.EMBY, ServiceType.JELLYFIN ->
        if (scheme.equals("https", ignoreCase = true)) "8920" else "8096"
    ServiceType.WEBDAV -> if (scheme.equals("https", ignoreCase = true)) "443" else "80"
    else -> if (scheme.equals("https", ignoreCase = true)) "443" else "80"
}

/** Parses both new-style URLs and legacy saved base URLs into editor fields. */
fun parseServerAddress(baseUrl: String): ServerAddressParts {
    val trimmed = baseUrl.trim()
    if (trimmed.isEmpty()) return ServerAddressParts("http", "", "")

    val candidate = if (trimmed.contains("://")) trimmed else "http://$trimmed"
    val uri = runCatching { URI(candidate) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: "http"
    val host = uri?.let(::hostAndPath)
        ?.takeIf { it.isNotBlank() }
        ?: trimmed.removePrefix("http://").removePrefix("https://").trimEnd('/')
    val port = uri?.port?.takeIf { it in 1..65535 }?.toString()
        ?: if (scheme == "https") "443" else "80"
    return ServerAddressParts(scheme, host, port)
}

/** Builds the persisted URL, returning null when the host or port is invalid. */
fun buildServerBaseUrl(scheme: String, hostInput: String, portInput: String): String? {
    val selectedScheme = scheme.lowercase().takeIf { it == "http" || it == "https" } ?: return null
    val port = portInput.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val rawHost = hostInput.trim()
    if (rawHost.isEmpty() || rawHost.any { it.isWhitespace() }) return null

    // Be forgiving if a user pastes a complete URL into the host field. The
    // protocol button and separate port field remain authoritative.
    val candidate = when {
        rawHost.contains("://") -> rawHost
        rawHost.startsWith("[") -> "$selectedScheme://$rawHost"
        rawHost.count { it == ':' } > 1 -> "$selectedScheme://[$rawHost]"
        else -> "$selectedScheme://$rawHost"
    }
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val normalizedHost = host.removePrefix("[").removeSuffix("]")
    val path = uri.rawPath.orEmpty().trimEnd('/')
    val authorityHost = if (normalizedHost.contains(':')) "[$normalizedHost]" else normalizedHost
    return "$selectedScheme://$authorityHost:$port$path"
}

private fun hostAndPath(uri: URI): String? {
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val normalizedHost = host.removePrefix("[").removeSuffix("]")
    val path = uri.rawPath.orEmpty().trimEnd('/')
    val displayHost = if (normalizedHost.contains(':')) "[$normalizedHost]" else normalizedHost
    return displayHost + path
}
