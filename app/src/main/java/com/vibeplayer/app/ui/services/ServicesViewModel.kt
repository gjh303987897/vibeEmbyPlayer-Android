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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** A service account shown on the services home screen. */
data class ServiceItemUi(
    val server: ServerConfig,
    val hasSession: Boolean
)

data class ServicesUiState(
    val items: List<ServiceItemUi> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val lastLoggedInServerId: String? = null
)

/** UI form for adding a new media server account. */
data class ServerForm(
    val name: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val serviceType: ServiceType = ServiceType.EMBY,
    val autoLogin: Boolean = true
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

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeServices().collect { servers ->
                _uiState.update { state ->
                    state.copy(
                        items = servers.map {
                            ServiceItemUi(
                                server = it,
                                hasSession = when (it.serviceType) {
                                    ServiceType.EMBY,
                                    ServiceType.JELLYFIN -> repository.hasSession(it)
                                    else -> true
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    fun openAddDialog() {
        _showAddDialog.value = true
    }

    fun dismissAddDialog() {
        _showAddDialog.value = false
    }

    /** Saves a new server and attempts an initial login. */
    fun addServer(form: ServerForm, password: String) {        val config = ServerConfig(
            id = UUID.randomUUID().toString(),
            name = form.name.ifBlank { form.baseUrl },
            baseUrl = form.baseUrl,
            username = form.username,
            serviceType = form.serviceType,
            autoLogin = form.autoLogin,
            trustSelfSignedCertificate = false,
            privateMode = false
        )
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, errorMessage = null) }
            repository.addServer(config)
            when (config.serviceType) {
                ServiceType.EMBY, ServiceType.JELLYFIN -> {
                    val loginResult = repository.login(config, password)
                    if (loginResult.isSuccess) {
                        activeSessionManager.setActiveSession(loginResult.getOrNull())
                    }
                    _uiState.update {
                        it.copy(
                            loading = false,
                            lastLoggedInServerId = config.id,
                            errorMessage = loginResult.exceptionOrNull()?.message
                        )
                    }
                }
                ServiceType.WEBDAV -> {
                    webDavRepository.saveCredentials(config, password)
                    _uiState.update { it.copy(loading = false, lastLoggedInServerId = config.id) }
                }
                else -> {
                    _uiState.update { it.copy(loading = false, lastLoggedInServerId = config.id) }
                }
            }
            _showAddDialog.value = false
        }
    }

    /** Logs into an existing server with the given password. */
    fun loginServer(server: ServerConfig, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, errorMessage = null) }
            val result = repository.login(server, password)
            if (result.isSuccess) {
                activeSessionManager.setActiveSession(result.getOrNull())
            }
            _uiState.update { state ->
                state.copy(
                    loading = false,
                    lastLoggedInServerId = if (result.isSuccess) server.id else null,
                    errorMessage = result.exceptionOrNull()?.message
                )
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

    fun removeServer(server: ServerConfig) {
        viewModelScope.launch {
            repository.logout(server)
            repository.removeServer(server.id)
            if (server.serviceType == ServiceType.IPTV) {
                iptvRepository.deleteServiceData(server.id)
            }
        }
    }

    /** Updates an existing server's editable fields (name / base URL / username). */
    fun editServer(server: ServerConfig, form: ServerForm) {
        viewModelScope.launch {
            repository.updateServer(
                server.copy(
                    name = form.name.ifBlank { form.baseUrl.ifBlank { server.name } },
                    baseUrl = form.baseUrl.ifBlank { server.baseUrl },
                    username = form.username.ifBlank { server.username }
                )
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
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
