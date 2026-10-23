package com.vibeplayer.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.datastore.SettingsDataStore
import com.vibeplayer.app.data.repository.HistoryRetentionManager
import com.vibeplayer.app.model.AppearancePreferences
import com.vibeplayer.app.model.HomeLayout
import com.vibeplayer.app.model.PlayerLayout
import com.vibeplayer.app.security.PrivacyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: String = "system",
    val dynamicColor: Boolean = true,
    val language: String = "system",
    val pageTransitions: Boolean = true,
    val appearance: AppearancePreferences = AppearancePreferences(),
    val historyRetentionDays: Int = 30,
    val retentionSaving: Boolean = false,
    val customizationSaveFailed: Boolean = false,
    val pinConfigured: Boolean = false,
    val privacyActive: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val privacyManager: PrivacyManager,
    private val retention: HistoryRetentionManager
) : ViewModel() {

    private val pinConfigured = MutableStateFlow(privacyManager.isPinConfigured())
    private val retentionSaving = MutableStateFlow(false)
    private val saveFailed = MutableStateFlow(false)

    private data class Prefs(val themeMode: String, val dynamicColor: Boolean, val language: String, val pageTransitions: Boolean)

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            combine(settingsDataStore.themeMode, settingsDataStore.dynamicColor,
                settingsDataStore.language, settingsDataStore.pageTransitions) { theme, dynamic, language, transitions ->
                Prefs(theme ?: "system", dynamic, language ?: "system", transitions)
            },
            settingsDataStore.appearancePreferences,
            settingsDataStore.historyRetentionDays
        ) { prefs, appearance, days -> Triple(prefs, appearance, days) },
        privacyManager.privacyMode,
        pinConfigured,
        retentionSaving,
        saveFailed
    ) { customization, privacyActive, pinCfg, saving, failed ->
        val (prefs, appearance, days) = customization
        SettingsUiState(
            themeMode = prefs.themeMode,
            dynamicColor = prefs.dynamicColor,
            language = prefs.language,
            pageTransitions = prefs.pageTransitions,
            appearance = appearance,
            historyRetentionDays = days,
            retentionSaving = saving,
            customizationSaveFailed = failed,
            pinConfigured = pinCfg,
            privacyActive = privacyActive
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(mode: String) = viewModelScope.launch { settingsDataStore.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) =
        viewModelScope.launch { settingsDataStore.setDynamicColor(enabled) }

    fun setLanguage(language: String) = viewModelScope.launch { settingsDataStore.setLanguage(language) }

    fun setPageTransitions(enabled: Boolean) =
        viewModelScope.launch { settingsDataStore.setPageTransitions(enabled) }

    fun setHomeLayout(jellyfin: Boolean, layout: HomeLayout) = saveCustomization {
        settingsDataStore.setHomeLayout(jellyfin, layout)
    }
    fun setPlayerLayout(layout: PlayerLayout) = saveCustomization { settingsDataStore.setPlayerLayout(layout) }
    fun setServiceCardScale(percent: Int) = saveCustomization { settingsDataStore.setServiceCardScale(percent) }

    fun setHistoryRetentionDays(days: Int) {
        if (retentionSaving.value) return
        retentionSaving.value = true
        saveCustomization {
            try { retention.setRetentionDays(days) } finally { retentionSaving.value = false }
        }
    }

    private fun saveCustomization(action: suspend () -> Unit) = viewModelScope.launch {
        saveFailed.value = false
        try { action() } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("Settings", "Unable to save customization (${error.javaClass.simpleName})")
            saveFailed.value = true
        }
    }

    suspend fun setPin(pin: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        privacyManager.setPin(pin).also { if (it) pinConfigured.update { true } }
    }

    suspend fun changePin(oldPin: String, newPin: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        privacyManager.changePin(oldPin, newPin)
    }

    suspend fun openPrivacy(pin: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ok = privacyManager.openPrivacy(pin)
        if (ok && !privacyManager.isPinConfigured()) {
            pinConfigured.update { true }
        }
        ok
    }

    fun isPinCoolingDown(): Boolean = privacyManager.isPinCoolingDown()

    fun exitPrivacy() { privacyManager.exitPrivacyMode() }
}
