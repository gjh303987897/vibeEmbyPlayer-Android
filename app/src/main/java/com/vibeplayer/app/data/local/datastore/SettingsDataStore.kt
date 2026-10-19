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
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vibeplayer.app.player.LONG_PRESS_SPEEDS
import com.vibeplayer.app.player.PlayerPreferences
import com.vibeplayer.app.player.VideoDecodeMode
import com.vibeplayer.app.player.SubtitleAppearance
import com.vibeplayer.app.model.IptvQualityPreference
import com.vibeplayer.app.model.AppearancePreferences
import com.vibeplayer.app.model.HomeLayout
import com.vibeplayer.app.model.PlayerLayout
import com.vibeplayer.app.model.normalizeServiceCardScale
import com.vibeplayer.app.domain.history.HistoryRetentionPolicy
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
        val IPTV_QUALITY = stringPreferencesKey("iptv_quality_preference")
        val HISTORY_RETENTION_DAYS = intPreferencesKey("history_retention_days")
        val EMBY_HOME_LAYOUT = stringPreferencesKey("emby_home_layout")
        val JELLYFIN_HOME_LAYOUT = stringPreferencesKey("jellyfin_home_layout")
        val PLAYER_LAYOUT = stringPreferencesKey("player_layout")
        val SERVICE_CARD_SCALE = intPreferencesKey("service_card_scale")
        val SHOW_PLAYBACK_DIAGNOSTICS = booleanPreferencesKey("player_show_diagnostics")
        val SUBTITLE_FONT_SIZE = intPreferencesKey("subtitle_font_size_sp")
        val SUBTITLE_BOTTOM_OFFSET = intPreferencesKey("subtitle_bottom_offset_dp")
        val SUBTITLE_BACKGROUND = intPreferencesKey("subtitle_background_opacity_percent")
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

    val historyRetentionDays: Flow<Int> = dataStore.data.map {
        HistoryRetentionPolicy.normalizeDays(it[Keys.HISTORY_RETENTION_DAYS] ?: HistoryRetentionPolicy.DEFAULT_DAYS)
    }

    suspend fun setHistoryRetentionDays(days: Int) {
        dataStore.edit { it[Keys.HISTORY_RETENTION_DAYS] = HistoryRetentionPolicy.normalizeDays(days) }
    }

    /** Empty means that subtitle selection follows only forced/default flags. */
    val preferredTextLanguage: Flow<String> = dataStore.data
        .map { it[Keys.PREFERRED_TEXT_LANGUAGE].orEmpty() }

    val playerPreferences: Flow<PlayerPreferences> = dataStore.data.map { preferences ->
        PlayerPreferences(
            longPressSpeed = preferences[Keys.LONG_PRESS_SPEED]?.takeIf { it in LONG_PRESS_SPEEDS } ?: 2f,
            showPerformance = preferences[Keys.SHOW_PERFORMANCE] ?: false,
            showDiagnostics = preferences[Keys.SHOW_PLAYBACK_DIAGNOSTICS] ?: false,
            layout = PlayerLayout.entries.firstOrNull { it.name == preferences[Keys.PLAYER_LAYOUT] }
                ?: PlayerLayout.TRENDY,
            videoDecodeMode = VideoDecodeMode.entries.firstOrNull { it.name == preferences[Keys.VIDEO_DECODE_MODE] }
                ?: VideoDecodeMode.HARDWARE,
            iptvQuality = IptvQualityPreference.entries.firstOrNull { it.name == preferences[Keys.IPTV_QUALITY] }
                ?: IptvQualityPreference.HIGHEST,
            subtitleAppearance = SubtitleAppearance(
                preferences[Keys.SUBTITLE_FONT_SIZE] ?: 16,
                preferences[Keys.SUBTITLE_BOTTOM_OFFSET] ?: 0,
                preferences[Keys.SUBTITLE_BACKGROUND] ?: 65
            ).normalized()
        )
    }

    val appearancePreferences: Flow<AppearancePreferences> = dataStore.data.map { preferences ->
        AppearancePreferences(
            embyHomeLayout = HomeLayout.entries.firstOrNull { it.name == preferences[Keys.EMBY_HOME_LAYOUT] } ?: HomeLayout.TRENDY,
            jellyfinHomeLayout = HomeLayout.entries.firstOrNull { it.name == preferences[Keys.JELLYFIN_HOME_LAYOUT] } ?: HomeLayout.TRENDY,
            playerLayout = PlayerLayout.entries.firstOrNull { it.name == preferences[Keys.PLAYER_LAYOUT] } ?: PlayerLayout.TRENDY,
            serviceCardScalePercent = normalizeServiceCardScale(preferences[Keys.SERVICE_CARD_SCALE] ?: 100)
        )
    }

    suspend fun setHomeLayout(jellyfin: Boolean, layout: HomeLayout) {
        dataStore.edit { it[if (jellyfin) Keys.JELLYFIN_HOME_LAYOUT else Keys.EMBY_HOME_LAYOUT] = layout.name }
    }

    suspend fun setPlayerLayout(layout: PlayerLayout) {
        dataStore.edit { it[Keys.PLAYER_LAYOUT] = layout.name }
    }

    suspend fun setServiceCardScale(percent: Int) {
        dataStore.edit { it[Keys.SERVICE_CARD_SCALE] = normalizeServiceCardScale(percent) }
    }

    suspend fun setShowDiagnostics(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_PLAYBACK_DIAGNOSTICS] = enabled }
    }

    suspend fun setLongPressSpeed(speed: Float) {
        require(speed in LONG_PRESS_SPEEDS)
        dataStore.edit { it[Keys.LONG_PRESS_SPEED] = speed }
    }

    suspend fun setSubtitleAppearance(appearance: SubtitleAppearance) {
        val safe = appearance.normalized()
        dataStore.edit {
            it[Keys.SUBTITLE_FONT_SIZE] = safe.fontSizeSp
            it[Keys.SUBTITLE_BOTTOM_OFFSET] = safe.bottomOffsetDp
            it[Keys.SUBTITLE_BACKGROUND] = safe.backgroundOpacityPercent
        }
    }

    suspend fun setShowPerformance(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_PERFORMANCE] = enabled }
    }

    suspend fun setVideoDecodeMode(mode: VideoDecodeMode) {
        dataStore.edit { it[Keys.VIDEO_DECODE_MODE] = mode.name }
    }

    suspend fun setIptvQuality(preference: IptvQualityPreference) {
        dataStore.edit { it[Keys.IPTV_QUALITY] = preference.name }
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
