package com.vibeplayer.app.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vibeplayer.app.util.LocaleHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Lightweight app preferences backed by DataStore.
 *
 * Placeholder for lightweight settings (theme, language, layout, etc.).
 * Sensitive credentials must NOT be stored here.
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LANGUAGE = stringPreferencesKey("language")
        val PAGE_TRANSITIONS = booleanPreferencesKey("page_transitions")
    }

    val themeMode: Flow<String?> = context.settingsDataStore.data
        .map { it[Keys.THEME_MODE] }

    val dynamicColor: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.DYNAMIC_COLOR] ?: true }

    val language: Flow<String?> = context.settingsDataStore.data
        .map { it[Keys.LANGUAGE] }

    val pageTransitions: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.PAGE_TRANSITIONS] ?: true }

    suspend fun setThemeMode(value: String) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = value }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setLanguage(value: String) {
        // Mirror the selection synchronously so Activity.attachBaseContext can
        // apply it on the next launch (DataStore is async and not ready there).
        LocaleHelper.currentLocaleTag = value
        context.settingsDataStore.edit { it[Keys.LANGUAGE] = value }
    }

    suspend fun setPageTransitions(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.PAGE_TRANSITIONS] = enabled }
    }
}
