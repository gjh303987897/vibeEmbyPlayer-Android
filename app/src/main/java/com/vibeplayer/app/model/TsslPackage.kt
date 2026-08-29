package com.vibeplayer.app.model

/**
 * A locally stored TSSL package (encrypted-HLS secret material).
 * Only a short identifier preview is surfaced to the UI; the full TSSL
 * document and keys live in application-local storage and are treated as
 * secrets.
 */
data class TsslPackage(
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    /** Short identifier preview (first 16 + last 12 characters), when parseable. */
    val identifierPreview: String? = null,
    /** False for files that are present locally but fail TSSL validation. */
    val isValid: Boolean = identifierPreview != null
)
