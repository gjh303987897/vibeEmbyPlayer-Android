package com.vibeplayer.app.util

/**
 * Normalizes text pasted or typed into a URL/address field.
 *
 * Only whitespace at the beginning and end is removed. Characters inside the
 * URL are deliberately left untouched so this helper is not accidentally used
 * as a general text-field sanitizer.
 */
fun normalizeUrlInput(value: String): String = value.trim()
