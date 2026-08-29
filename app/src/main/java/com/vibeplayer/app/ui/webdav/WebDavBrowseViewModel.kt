package com.vibeplayer.app.ui.webdav

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.data.repository.TransferRepository
import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.WebDavItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WebDavUiState(
    val server: ServerConfig? = null,
    val path: String = "",
    val items: List<WebDavItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val needPassword: Boolean = false
) {
    val currentDirectoryName: String
        get() = path.split("/").filter { it.isNotBlank() }.lastOrNull() ?: server?.name ?: "/"
}

@HiltViewModel
class WebDavBrowseViewModel @Inject constructor(
    private val repository: MediaServerRepository,
    private val webDavRepository: WebDavRepository,
    private val transferRepository: TransferRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebDavUiState())
    val uiState: StateFlow<WebDavUiState> = _uiState.asStateFlow()
    private var browseJob: Job? = null
    private var browseRequestId = 0L

    fun load(serverId: String) {
        viewModelScope.launch {
            val server = repository.getServer(serverId) ?: return@launch
            val needPassword = !webDavRepository.hasPassword(server)
            _uiState.update { it.copy(server = server, needPassword = needPassword) }
            if (!needPassword) browse(server, "")
        }
    }

    fun savePassword(password: String) {
        val server = _uiState.value.server ?: return
        webDavRepository.saveCredentials(server, password)
        _uiState.update { it.copy(needPassword = false) }
        browse(server, "")
    }

    fun navigateTo(path: String) {
        val server = _uiState.value.server ?: return
        browse(server, path)
    }

    fun goUp() {
        val path = _uiState.value.path
        val parent = path.trimEnd('/').substringBeforeLast('/')
        navigateTo(if (parent.isEmpty()) "" else parent)
    }

    private fun browse(server: ServerConfig, path: String) {
        browseJob?.cancel()
        val requestId = ++browseRequestId
        browseJob = viewModelScope.launch {
            // Clear stale rows immediately. Keeping the previous directory on
            // screen made a failed child PROPFIND look like a frozen navigation.
            _uiState.update { it.copy(path = path, items = emptyList(), loading = true, error = null) }
            webDavRepository.list(server, path).fold(
                onSuccess = { items ->
                    if (requestId == browseRequestId) {
                        _uiState.update { it.copy(path = path, items = items, loading = false) }
                    }
                },
                onFailure = { e ->
                    if (requestId == browseRequestId) {
                        _uiState.update { it.copy(loading = false, error = e.message ?: "WebDAV request failed") }
                    }
                }
            )
        }
    }

    /**
     * Enqueues a download of [item] into the user-chosen SAF directory tree.
     * Failures are surfaced via [WebDavUiState.error].
     */
    fun download(item: WebDavItem, destTreeUri: String) {
        val server = _uiState.value.server ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            runCatching { transferRepository.downloadToTree(server, item, destTreeUri) }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message ?: "Download failed") }
                }
        }
    }

    /** Creates a new folder named [name] inside the current directory. */
    fun createDirectory(name: String) {
        val server = _uiState.value.server ?: return
        val clean = name.trim().trimStart('/')
        if (clean.isEmpty()) {
            _uiState.update { it.copy(error = "Folder name is empty") }
            return
        }
        val base = _uiState.value.path
        val target = if (base.isEmpty()) clean else "$base/$clean"
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            webDavRepository.createDirectory(server, target).fold(
                onSuccess = { browse(server, base) },
                onFailure = { e ->
                    _uiState.update { it.copy(loading = false, error = e.message ?: "Create folder failed") }
                }
            )
        }
    }

    /** Uploads the file picked via SAF ([uri]) into the current directory. */
    fun upload(uri: Uri) {
        val server = _uiState.value.server ?: return
        val name = displayNameOf(uri)
        if (name.isBlank()) {
            _uiState.update { it.copy(error = "Invalid file name") }
            return
        }
        val base = _uiState.value.path
        val target = if (base.isEmpty()) name else "$base/$name"
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Failed to read file")
                    if (bytes.isEmpty()) throw IllegalStateException("File is empty")
                    webDavRepository.upload(server, target, bytes)
                }
            }
            result.fold(
                onSuccess = { browse(server, base) },
                onFailure = { e ->
                    _uiState.update { it.copy(loading = false, error = e.message ?: "Upload failed") }
                }
            )
        }
    }

    private fun displayNameOf(uri: Uri): String {
        runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return c.getString(idx)
                    }
                }
        }
        return uri.lastPathSegment ?: ""
    }
}
