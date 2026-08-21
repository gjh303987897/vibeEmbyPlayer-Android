package com.vibeplayer.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.datastore.SettingsDataStore
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
    val pinConfigured: Boolean = false,
    val privacyActive: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val privacyManager: PrivacyManager
) : ViewModel() {

    private val pinConfigured = MutableStateFlow(privacyManager.isPinConfigured())

    private data class Prefs(val themeMode: String, val dynamicColor: Boolean, val language: String, val pageTransitions: Boolean)

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            settingsDataStore.themeMode,
            settingsDataStore.dynamicColor,
            settingsDataStore.language,
            settingsDataStore.pageTransitions
        ) { theme, dynamic, language, transitions ->
            Prefs(theme ?: "system", dynamic, language ?: "system", transitions)
        },
        privacyManager.privacyMode,
        pinConfigured
    ) { prefs, privacyActive, pinCfg ->
        SettingsUiState(
            themeMode = prefs.themeMode,
            dynamicColor = prefs.dynamicColor,
            language = prefs.language,
            pageTransitions = prefs.pageTransitions,
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

    fun setPin(pin: String): Boolean {
        val ok = privacyManager.setPin(pin)
        if (ok) pinConfigured.update { true }
        return ok
    }

    fun openPrivacy(pin: String): Boolean {
        val ok = privacyManager.openPrivacy(pin)
        if (ok && !privacyManager.isPinConfigured()) {
            pinConfigured.update { true }
        }
        return ok
    }

    fun enterPrivacy() { privacyManager.enterPrivacyMode() }
    fun exitPrivacy() { privacyManager.exitPrivacyMode() }
}
