package com.vibeplayer.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Applies the user-selected UI language (["system"] / "en_US" / "zh_CN") to the
 * application context.
 *
 * DataStore is asynchronous, so a synchronous in-memory mirror
 * ([currentLocaleTag]) is kept and read from [applyLocaleIfNeeded], which is
 * called from [android.app.Activity.attachBaseContext] so the new locale is
 * applied before any view / Compose root is created.
 */
object LocaleHelper {

    /** Default behavior: follow the system locale. */
    const val SYSTEM = "system"

    /** In-memory mirror of the stored language setting (see [applyLocaleIfNeeded]). */
    @Volatile
    var currentLocaleTag: String? = null

    /** Convert a stored tag into a [Locale], or null when "system" should be used. */
    fun toLocale(tag: String?): Locale? {
        if (tag.isNullOrBlank() || tag == SYSTEM) return null
        val parts = tag.split('_', '-')
        return when (parts.size) {
            1 -> Locale(parts[0])
            else -> Locale(parts[0], parts[1])
        }
    }

    /**
     * Wrap [base] so its resources (and thus string resources, Compose etc.)
     * resolve in the configured locale. Returns [base] unchanged when no
     * explicit locale is selected (i.e. "system").
     *
     * The AppBundleLocaleChanges lint is suppressed because this media app is
     * not distributed with Play Core on-demand language strings; the user
     * explicitly picks the runtime language in settings.
     */
    @SuppressLint("AppBundleLocaleChanges")
    fun applyLocaleIfNeeded(base: Context): Context {
        val locale = toLocale(currentLocaleTag) ?: return base
        val config = Configuration(base.resources.configuration)
        Locale.setDefault(locale)
        // minSdk >= 26, so LocaleList / setLocales are always available.
        config.setLocales(LocaleList(locale))
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        return ContextWrapper(base).apply { resources.updateConfiguration(config, resources.displayMetrics) }
    }
}
