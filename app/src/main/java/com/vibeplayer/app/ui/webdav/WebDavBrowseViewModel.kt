package com.vibeplayer.app.ui.webdav

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vibeplayer.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.data.repository.TransferRepository
import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.MessageTone
import com.vibeplayer.app.model.WebDavItem
import com.vibeplayer.app.player.hls.EncryptedHlsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WebDavUiState(
    val server: ServerConfig? = null,
    val path: String = "",
    val items: List<WebDavItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val messageTone: MessageTone = MessageTone.INFO,
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
    private val encryptedHlsManager: EncryptedHlsManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebDavUiState())
    val uiState: StateFlow<WebDavUiState> = _uiState.asStateFlow()
    private var browseJob: Job? = null
    private var metadataJob: Job? = null
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
        _uiState.update {
            it.copy(
                needPassword = false,
                message = appContext.getString(R.string.webdav_password_saved),
                messageTone = MessageTone.SUCCESS
            )
        }
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

    /** Retries the current directory without changing the navigation stack. */
    fun retryCurrent() {
        val server = _uiState.value.server ?: return
        browse(server, _uiState.value.path)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun browse(server: ServerConfig, path: String) {
        browseJob?.cancel()
        metadataJob?.cancel()
        metadataJob = null
        val requestId = ++browseRequestId
        browseJob = viewModelScope.launch {
            // Clear stale rows immediately. Keeping the previous directory on
            // screen made a failed child PROPFIND look like a frozen navigation.
            _uiState.update { it.copy(path = path, items = emptyList(), loading = true, error = null) }
            webDavRepository.list(server, path).fold(
                onSuccess = { items ->
                    if (requestId == browseRequestId) {
                        _uiState.update { it.copy(path = path, items = items, loading = false) }
                        resolveEncryptedMetadata(server, path, items, requestId)
                    }
                },
                onFailure = { e ->
                    if (requestId == browseRequestId) {
                        _uiState.update {
                            it.copy(
                                loading = false,
                                error = e.message ?: appContext.getString(R.string.message_error_generic)
                            )
                        }
                    }
                }
            )
        }
    }

    /**
     * Enriches encrypted-HLS rows after the directory itself is visible.  The
     * work is cancellable when the user changes directory, and every update is
     * guarded by the browse generation so a slow response cannot overwrite a
     * newer directory's rows.
     */
    private fun resolveEncryptedMetadata(
        server: ServerConfig,
        path: String,
        items: List<WebDavItem>,
        requestId: Long
    ) {
        val encryptedItems = items.filter { it.isEncryptedHls }
        if (encryptedItems.isEmpty()) return

        metadataJob = viewModelScope.launch {
            for (item in encryptedItems) {
                ensureActive()
                val metadata = encryptedHlsManager
                    .resolveWebDavMetadata(server, item.path)
                    .getOrNull()
                    ?: continue
                if (!isActive || requestId != browseRequestId) return@launch
                _uiState.update { state ->
                    if (state.path != path || state.server?.id != server.id) {
                        state
                    } else {
                        state.copy(
                            items = state.items.map { current ->
                                if (current.path == item.path) {
                                    current.copy(
                                        identifierPreview = metadata.identifierPreview,
                                        sourceFileName = metadata.sourceFileName
                                    )
                                } else {
                                    current
                                }
                            }
                        )
                    }
                }
            }
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
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            message = appContext.getString(R.string.webdav_download_queued),
                            messageTone = MessageTone.SUCCESS
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(error = e.message ?: appContext.getString(R.string.message_error_generic))
                    }
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
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            message = appContext.getString(R.string.webdav_folder_created),
                            messageTone = MessageTone.SUCCESS
                        )
                    }
                    browse(server, base)
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = e.message ?: appContext.getString(R.string.message_error_generic)
                        )
                    }
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
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            message = appContext.getString(R.string.webdav_upload_complete),
                            messageTone = MessageTone.SUCCESS
                        )
                    }
                    browse(server, base)
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = e.message ?: appContext.getString(R.string.message_error_generic)
                        )
                    }
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
