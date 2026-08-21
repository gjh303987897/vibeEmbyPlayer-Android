package com.vibeplayer.app.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import com.vibeplayer.app.util.LocaleHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Lightweight app preferences backed by DataStore.
 *
 * Placeholder for lightweight settings (theme, language, layout, etc.).
 * Sensitive credentials must NOT be stored here.
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext context: Context
) {
    // Single DataStore instance with the same corruption protection as the
    // services store, so a corrupt file resets to empty instead of crashing.
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { File(context.applicationContext.filesDir, "datastore/settings.preferences_pb") }
    )

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LANGUAGE = stringPreferencesKey("language")
        val PAGE_TRANSITIONS = booleanPreferencesKey("page_transitions")
    }

    val themeMode: Flow<String?> = dataStore.data
        .map { it[Keys.THEME_MODE] }

    val dynamicColor: Flow<Boolean> = dataStore.data
        .map { it[Keys.DYNAMIC_COLOR] ?: true }

    val language: Flow<String?> = dataStore.data
        .map { it[Keys.LANGUAGE] }

    val pageTransitions: Flow<Boolean> = dataStore.data
        .map { it[Keys.PAGE_TRANSITIONS] ?: true }

    suspend fun setThemeMode(value: String) {
        dataStore.edit { it[Keys.THEME_MODE] = value }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setLanguage(value: String) {
        // Mirror the selection synchronously so Activity.attachBaseContext can
        // apply it on the next launch (DataStore is async and not ready there).
        LocaleHelper.currentLocaleTag = value
        dataStore.edit { it[Keys.LANGUAGE] = value }
    }

    suspend fun setPageTransitions(enabled: Boolean) {
        dataStore.edit { it[Keys.PAGE_TRANSITIONS] = enabled }
    }
}
