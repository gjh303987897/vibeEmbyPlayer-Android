package com.vibeplayer.app.ui.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.repository.ActiveSessionManager
import com.vibeplayer.app.data.repository.IptvRepository
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A service account shown on the services home screen. */
data class ServiceItemUi(
    val server: ServerConfig,
    val hasSession: Boolean,
    val hasSavedPassword: Boolean = false
)

data class ServicesUiState(
    val items: List<ServiceItemUi> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val lastLoggedInServerId: String? = null,
    val navigationServerId: String? = null,
    val enteringServerId: String? = null,
    /**
     * Set when the user asked to save a password but secure storage refused the
     * write (unavailable Keystore). The screen turns it into a snackbar, because
     * "saved" that quietly does nothing is exactly how this bug used to hide.
     */
    val passwordWarning: Boolean = false
)

/** UI form for adding a new media server account. */
data class ServerForm(
    val name: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val serviceType: ServiceType = ServiceType.EMBY,
    val autoLogin: Boolean = true,
    val trustSelfSignedCertificate: Boolean = false
)

@HiltViewModel
class ServicesViewModel @Inject constructor(
    private val repository: MediaServerRepository,
    private val activeSessionManager: ActiveSessionManager,
    private val webDavRepository: WebDavRepository,
    private val iptvRepository: IptvRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServicesUiState())
    val uiState: StateFlow<ServicesUiState> = _uiState.asStateFlow()

    /** Mirrors the latest "could not save the password securely" result into the state. */
    private var passwordWarning: Boolean
        get() = _uiState.value.passwordWarning
        set(value) {
            _uiState.update { it.copy(passwordWarning = value) }
        }

    private var pendingEntryServerId: String? = null

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeServices().collect { servers ->
                publishItems(servers)
            }
        }
    }

    /**
     * Recomputes the visible items for [servers]. Session / saved-password flags
     * live in the Keystore-backed store, so reading them costs a cipher call:
     * that is done off the main thread and never inside the composition.
     */
    private suspend fun publishItems(servers: List<ServerConfig>) {
        val items = withContext(Dispatchers.Default) {
            servers.map { server ->
                ServiceItemUi(
                    server = server,
                    hasSession = when (server.serviceType) {
                        ServiceType.EMBY,
                        ServiceType.JELLYFIN -> repository.hasSession(server)
                        else -> true
                    },
                    hasSavedPassword = repository.hasSavedPassword(server)
                )
            }
        }
        _uiState.update { it.copy(items = items) }
    }

    /**
     * Re-reads the session / saved-password flags. Called when the screen is shown
     * and after any credential change: the services DataStore does not emit when a
     * password or token is stored, so without this a freshly saved password would
     * leave the card stuck on "sign in" (the reported "it asks for the password
     * again even though I saved it").
     */
    fun refresh() {
        viewModelScope.launch { refreshItems() }
    }

    private suspend fun refreshItems() {
        val servers = runCatching { repository.observeServices().first() }
            .getOrDefault(emptyList())
        publishItems(servers)
    }

    fun openAddDialog() {
        _showAddDialog.value = true
    }

    fun dismissAddDialog() {
        _showAddDialog.value = false
    }

    /** Saves a new server and attempts an initial login. */
    fun addServer(form: ServerForm, password: String) {
        val config = ServerConfig(
            id = UUID.randomUUID().toString(),
            name = form.name.ifBlank { form.baseUrl },
            baseUrl = normalizeScheme(form.baseUrl),
            username = form.username,
            serviceType = form.serviceType,
            autoLogin = form.autoLogin,
            trustSelfSignedCertificate = form.trustSelfSignedCertificate,
            privateMode = false
        )
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, errorMessage = null) }
            try {
                repository.addServer(config)
            } catch (t: Throwable) {
                // Persisting the new account must never crash the save. Surface
                // the error and keep the dialog so the user can retry.
                _uiState.update { it.copy(loading = false, errorMessage = t.message) }
                return@launch
            }
            try {
                when (config.serviceType) {
                    ServiceType.EMBY, ServiceType.JELLYFIN -> {
                        val loginResult = repository.login(config, password)
                        val message = loginResult.exceptionOrNull()?.message
                        if (loginResult.isSuccess) {
                            activeSessionManager.setActiveSession(loginResult.getOrNull())
                            // One-tap entry: persist the password only when the user
                            // opted to save it.
                            if (config.autoLogin && !repository.savePassword(config, password)) {
                                // Secure storage refused the write: the next entry would
                                // silently ask for the password again, so say so.
                                passwordWarning = true
                            }
                        }
                        _uiState.update {
                            it.copy(
                                loading = false,
                                lastLoggedInServerId = if (loginResult.isSuccess) config.id else null,
                                errorMessage = message
                            )
                        }
                        // The DataStore emit for the new row happens before the token /
                        // password are written, so recompute the card flags here to
                        // reflect one-tap entry immediately.
                        if (loginResult.isSuccess) refreshItems()
                    }
                    ServiceType.WEBDAV -> {
                        webDavRepository.saveCredentials(config, password)
                        _uiState.update { it.copy(loading = false, lastLoggedInServerId = config.id) }
                    }
                    else -> {
                        _uiState.update { it.copy(loading = false, lastLoggedInServerId = config.id) }
                    }
                }
            } catch (t: Throwable) {
                // The initial login / credential step must never crash the save.
                _uiState.update { it.copy(loading = false, errorMessage = t.message) }
                return@launch
            }
            _showAddDialog.value = false
        }
    }

    /**
     * Logs into an existing server with the given password.
     *
     * @param savePassword explicit user choice to keep the password for one-tap
     *   entry; when null it falls back to the server's stored autoLogin flag.
     */
    fun loginServer(server: ServerConfig, password: String, savePassword: Boolean? = null) {
        val persistPassword = savePassword ?: server.autoLogin
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, errorMessage = null) }
            try {
                val result = repository.login(server, password)
                if (result.isSuccess) {
                    activeSessionManager.setActiveSession(result.getOrNull())
                    // One-tap entry: keep the password so the next tap logs
                    // straight in (only opt-in / autoLogin servers persist it).
                    if (persistPassword && password.isNotBlank() &&
                        !repository.savePassword(server, password)
                    ) {
                        passwordWarning = true
                    } else if (!persistPassword) {
                        // User opted out: do not keep a previously saved password
                        // usable for one-tap entry (the fresh session is kept).
                        repository.clearSavedPassword(server)
                    }
                    // Keep the persisted choice in sync, so the next sign-in keeps
                    // doing exactly what the user ticked here.
                    if (persistPassword != server.autoLogin &&
                        (server.serviceType == ServiceType.EMBY ||
                            server.serviceType == ServiceType.JELLYFIN)
                    ) {
                        repository.updateServer(server.copy(autoLogin = persistPassword))
                    }
                    refreshItems()
                }
                _uiState.update { state ->
                    state.copy(
                        loading = false,
                        lastLoggedInServerId = if (result.isSuccess) server.id else null,
                        navigationServerId = if (result.isSuccess && pendingEntryServerId == server.id) server.id else null,
                        enteringServerId = if (pendingEntryServerId == server.id) null else state.enteringServerId,
                        errorMessage = result.exceptionOrNull()?.message
                    )
                }
                if (pendingEntryServerId == server.id) pendingEntryServerId = null
            } catch (t: Throwable) {
                // A login failure / unexpected error must never crash the app.
                if (pendingEntryServerId == server.id) pendingEntryServerId = null
                _uiState.update {
                    it.copy(
                        loading = false,
                        enteringServerId = if (it.enteringServerId == server.id) null else it.enteringServerId,
                        errorMessage = t.message
                    )
                }
            }
        }
    }

    /**
     * Restores the persisted session for a service and makes it active.
     * Returns true when the session is usable and the caller may navigate home.
     */
    fun openServer(server: ServerConfig): Boolean {
        if (server.serviceType != ServiceType.EMBY && server.serviceType != ServiceType.JELLYFIN) {
            return true
        }
        val session = repository.restoreSession(server) ?: return false
        activeSessionManager.setActiveSession(session)
        return true
    }

    /**
     * One-tap entry: if a usable session exists return true so the caller can
     * navigate immediately. Otherwise, if a saved password exists, trigger an
     * async auto-login (navigation then happens when login succeeds, driven by
     * [lastLoggedInServerId]) and return false.
     */
    fun enterServer(server: ServerConfig) {
        if (openServer(server)) {
            _uiState.update { it.copy(navigationServerId = server.id, enteringServerId = null) }
            return
        }
        if (server.serviceType == ServiceType.EMBY || server.serviceType == ServiceType.JELLYFIN) {
            val saved = repository.savedPassword(server)
            if (!saved.isNullOrEmpty()) {
                pendingEntryServerId = server.id
                _uiState.update { it.copy(enteringServerId = server.id) }
                loginServer(server, saved)
            }
        }
    }

    fun acknowledgeLoginSuccess() {
        _uiState.update { it.copy(lastLoggedInServerId = null) }
    }

    fun consumeNavigation() {
        _uiState.update { it.copy(navigationServerId = null) }
    }

    fun removeServer(server: ServerConfig) {
        viewModelScope.launch {
            repository.logout(server)
            repository.removeServer(server.id)
            if (server.serviceType == ServiceType.IPTV) {
                iptvRepository.deleteServiceData(server.id)
            }
            refreshItems()
        }
    }

    /**
     * Updates an existing server's editable fields (name / base URL / username).
     * When a new password is supplied and the user opts to save it, the password
     * is persisted so tapping the card enters directly next time.
     */
    fun editServer(
        server: ServerConfig,
        form: ServerForm,
        password: String = "",
        savePassword: Boolean = false
    ) {
        viewModelScope.launch {
            val credentialServer = server.serviceType == ServiceType.EMBY ||
                server.serviceType == ServiceType.JELLYFIN
            // Save the password before the services list updates so that when the
            // services DataStore emits, the collect re-reads hasSavedPassword and
            // the card already reflects one-tap entry.
            if (credentialServer && savePassword && password.isNotBlank()) {
                repository.savePassword(server, password)
            } else if (credentialServer && !savePassword) {
                repository.clearSavedPassword(server)
            }
            repository.updateServer(
                server.copy(
                    name = form.name.ifBlank { form.baseUrl.ifBlank { server.name } },
                    baseUrl = normalizeScheme(form.baseUrl).ifBlank { server.baseUrl },
                    username = form.username.ifBlank { server.username },
                    autoLogin = if (credentialServer) savePassword else server.autoLogin,
                    trustSelfSignedCertificate = form.trustSelfSignedCertificate
                )
            )
            refreshItems()
        }
    }

    /**
     * Ensures a bare LAN address (e.g. `192.168.1.5:8096`) is usable by
     * prepending a scheme, so URL parsing does not fail during login and the
     * server save never crashes on an invalid URL.
     */
    private fun normalizeScheme(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return trimmed
        val hasScheme = trimmed.contains("://") || trimmed.startsWith("http://") ||
            trimmed.startsWith("https://")
        return if (hasScheme) trimmed else "http://$trimmed"
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /** Consumes [ServicesUiState.passwordWarning] after it has been shown. */
    fun acknowledgePasswordWarning() {
        _uiState.update { it.copy(passwordWarning = false) }
    }

    /** Moves a service from [from] to [to] and persists the new order. */
    fun reorder(from: Int, to: Int) {
        val servers = _uiState.value.items.map { it.server }.toMutableList()
        if (from in servers.indices && to in servers.indices) {
            val moved = servers.removeAt(from)
            servers.add(to, moved)
            viewModelScope.launch { repository.reorderServices(servers) }
        }
    }
}
