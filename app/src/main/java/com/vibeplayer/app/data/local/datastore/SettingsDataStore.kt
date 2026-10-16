package com.vibeplayer.app.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vibeplayer.app.player.LONG_PRESS_SPEEDS
import com.vibeplayer.app.player.PlayerPreferences
import com.vibeplayer.app.player.VideoDecodeMode
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
    private val languageMirror = context.applicationContext
        .getSharedPreferences("settings_language_mirror", Context.MODE_PRIVATE)
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
        val PREFERRED_TEXT_LANGUAGE = stringPreferencesKey("preferred_text_language")
        val LONG_PRESS_SPEED = floatPreferencesKey("player_long_press_speed")
        val SHOW_PERFORMANCE = booleanPreferencesKey("player_show_performance")
        val VIDEO_DECODE_MODE = stringPreferencesKey("player_video_decode_mode")
    }

    val themeMode: Flow<String?> = dataStore.data
        .map { it[Keys.THEME_MODE] }

    val dynamicColor: Flow<Boolean> = dataStore.data
        .map { it[Keys.DYNAMIC_COLOR] ?: true }

    val language: Flow<String?> = dataStore.data
        .map { it[Keys.LANGUAGE] }

    /** Synchronous bootstrap value used before the first Activity is created. */
    val initialLanguage: String?
        get() = languageMirror.getString(LANGUAGE_MIRROR_KEY, null)

    val pageTransitions: Flow<Boolean> = dataStore.data
        .map { it[Keys.PAGE_TRANSITIONS] ?: true }

    /** Empty means that subtitle selection follows only forced/default flags. */
    val preferredTextLanguage: Flow<String> = dataStore.data
        .map { it[Keys.PREFERRED_TEXT_LANGUAGE].orEmpty() }

    val playerPreferences: Flow<PlayerPreferences> = dataStore.data.map { preferences ->
        PlayerPreferences(
            longPressSpeed = preferences[Keys.LONG_PRESS_SPEED]?.takeIf { it in LONG_PRESS_SPEEDS } ?: 2f,
            showPerformance = preferences[Keys.SHOW_PERFORMANCE] ?: false,
            videoDecodeMode = VideoDecodeMode.entries.firstOrNull { it.name == preferences[Keys.VIDEO_DECODE_MODE] }
                ?: VideoDecodeMode.HARDWARE
        )
    }

    suspend fun setLongPressSpeed(speed: Float) {
        require(speed in LONG_PRESS_SPEEDS)
        dataStore.edit { it[Keys.LONG_PRESS_SPEED] = speed }
    }

    suspend fun setShowPerformance(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_PERFORMANCE] = enabled }
    }

    suspend fun setVideoDecodeMode(mode: VideoDecodeMode) {
        dataStore.edit { it[Keys.VIDEO_DECODE_MODE] = mode.name }
    }

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
        languageMirror.edit().putString(LANGUAGE_MIRROR_KEY, value).apply()
        dataStore.edit { it[Keys.LANGUAGE] = value }
    }

    suspend fun setPageTransitions(enabled: Boolean) {
        dataStore.edit { it[Keys.PAGE_TRANSITIONS] = enabled }
    }

    suspend fun setPreferredTextLanguage(language: String?) {
        dataStore.edit { preferences ->
            val value = language?.trim().orEmpty()
            if (value.isEmpty()) preferences.remove(Keys.PREFERRED_TEXT_LANGUAGE)
            else preferences[Keys.PREFERRED_TEXT_LANGUAGE] = value
        }
    }

    private companion object {
        const val LANGUAGE_MIRROR_KEY = "language"
    }
}
