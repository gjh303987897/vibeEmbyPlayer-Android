package com.vibeplayer.app.ui.tssl

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.tssl.TsslStore
import com.vibeplayer.app.data.local.datastore.TsslBackupSettings
import com.vibeplayer.app.data.local.datastore.TsslBackupSettingsStore
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.domain.tssl.EncryptedHlsPackager
import com.vibeplayer.app.domain.tssl.TsslBackupService
import com.vibeplayer.app.domain.tssl.TsslBackupTarget
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import com.vibeplayer.app.model.TsslPackage
import com.vibeplayer.app.player.hls.SafHlsSource
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TsslManagerUiState(
    val packages: List<TsslPackage> = emptyList(),
    val webDavTargets: List<ServerConfig> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val backupBusy: Boolean = false,
    val packagingBusy: Boolean = false,
    /** Progress 0..1 while packaging, null when idle. */
    val packagingProgress: Float? = null,
    /** Identifier of the package being backed up, when any. */
    val backingUpFileName: String? = null,
    val backupSettings: TsslBackupSettings = TsslBackupSettings(),
    val s3SecretConfigured: Boolean = false,
    val restoring: Boolean = false,
    val restoreProgress: Float? = null
)

@HiltViewModel
class TsslManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tsslStore: TsslStore,
    private val mediaServerRepository: MediaServerRepository,
    private val backupService: TsslBackupService,
    private val backupSettingsStore: TsslBackupSettingsStore,
    private val packager: EncryptedHlsPackager
) : ViewModel() {

    private val _uiState = MutableStateFlow(TsslManagerUiState())
    val uiState: StateFlow<TsslManagerUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            backupSettingsStore.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        backupSettings = settings,
                        s3SecretConfigured = backupSettingsStore.hasS3Secret()
                    )
                }
            }
        }
        viewModelScope.launch {
            mediaServerRepository.observeServices().collect { servers ->
                val targets = servers.filter { it.serviceType == ServiceType.WEBDAV }
                _uiState.update { it.copy(webDavTargets = targets) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val packages = tsslStore.list()
            _uiState.update { it.copy(packages = packages, loading = false) }
        }
    }

    /** Imports a TSSL document; returns the stored filename or null when invalid. */
    fun import(bytes: ByteArray) {
        viewModelScope.launch {
            val name = tsslStore.import(bytes)
            _uiState.update {
                it.copy(
                    message = if (name != null) "Imported $name" else "Invalid TSSL package",
                    packages = tsslStore.list()
                )
            }
        }
    }

    fun setBackupTarget(target: String) = updateBackupSettings { it.copy(target = target) }
    fun setWebDavServiceId(id: String) = updateBackupSettings { it.copy(webDavServiceId = id) }
    fun setWebDavPath(path: String) = updateBackupSettings { it.copy(webDavPath = path) }
    fun setS3Endpoint(value: String) = updateBackupSettings { it.copy(s3Endpoint = value) }
    fun setS3Bucket(value: String) = updateBackupSettings { it.copy(s3Bucket = value) }
    fun setS3Region(value: String) = updateBackupSettings { it.copy(s3Region = value) }
    fun setS3Prefix(value: String) = updateBackupSettings { it.copy(s3Prefix = value) }
    fun setS3AccessKey(value: String) = updateBackupSettings { it.copy(s3AccessKey = value) }
    fun setTrustSelfSigned(value: Boolean) = updateBackupSettings { it.copy(trustSelfSignedCertificate = value) }

    fun saveS3Secret(secret: String) {
        val saved = backupSettingsStore.saveS3Secret(secret)
        _uiState.update { it.copy(message = if (saved) "S3 secret saved" else "Unable to save S3 secret securely") }
    }

    fun backupAll() {
        if (_uiState.value.backupBusy || _uiState.value.restoring) return
        viewModelScope.launch {
            val target = buildTarget() ?: return@launch
            val packages = tsslStore.list().filter { it.isValid }
            if (packages.isEmpty()) {
                _uiState.update { it.copy(message = "No valid TSSL packages to back up") }
                return@launch
            }
            _uiState.update { it.copy(backupBusy = true, message = null, backingUpFileName = null) }
            val result = backupService.backup(target, packages.map { it.fileName }) { done, total ->
                _uiState.update { it.copy(backingUpFileName = if (done < total) packages.getOrNull(done)?.fileName else null) }
            }
            _uiState.update {
                it.copy(
                    backupBusy = false,
                    backingUpFileName = null,
                    message = when (result) {
                        is TsslBackupService.BackupResult.Success -> "Backed up ${result.uploaded} TSSL packages"
                        is TsslBackupService.BackupResult.Error -> result.message
                    }
                )
            }
        }
    }

    fun restoreBackup() {
        if (_uiState.value.backupBusy || _uiState.value.restoring) return
        viewModelScope.launch {
            val target = buildTarget() ?: return@launch
            _uiState.update { it.copy(restoring = true, restoreProgress = 0f, message = null) }
            val result = backupService.restore(target) { done, total ->
                _uiState.update { it.copy(restoreProgress = if (total > 0) done.toFloat() / total else 1f) }
            }
            val message = when (result) {
                is TsslBackupService.RestoreResult.Success -> {
                    val summary = result.summary
                    "Restored ${summary.restored} packages (${summary.alreadyExists} already existed, ${summary.failed} failed)" +
                        (summary.firstError?.let { ": $it" } ?: "")
                }
                is TsslBackupService.RestoreResult.Error -> result.message
            }
            _uiState.update {
                it.copy(
                    restoring = false,
                    restoreProgress = null,
                    message = message,
                    packages = tsslStore.list()
                )
            }
        }
    }

    fun delete(pkg: TsslPackage) {
        viewModelScope.launch {
            tsslStore.delete(pkg.fileName)
            _uiState.update { it.copy(packages = tsslStore.list(), message = "Deleted ${pkg.fileName}") }
        }
    }

    /** Exports a validated package's bytes to the caller (e.g. share / SAF). */
    fun exportBytes(pkg: TsslPackage, onBytes: (ByteArray) -> Unit) {
        viewModelScope.launch {
            val bytes = tsslStore.exportBytes(pkg.fileName)
            if (bytes != null) onBytes(bytes) else {
                _uiState.update { it.copy(message = "Package unreadable") }
            }
        }
    }

    /** Backs a package up to the chosen WebDAV target. */
    fun backup(target: ServerConfig, pkg: TsslPackage) {
        if (_uiState.value.backupBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(backupBusy = true, backingUpFileName = pkg.fileName, message = null) }
            val bytes = tsslStore.read(pkg.fileName)
            val result = if (bytes == null) {
                TsslBackupService.BackupResult.Error("Package unreadable")
            } else {
                backupService.backup(
                    TsslBackupTarget.WebDav(target, _uiState.value.backupSettings.webDavPath),
                    listOf(pkg.fileName)
                )
            }
            val message = when (result) {
                is TsslBackupService.BackupResult.Success -> "Backed up ${result.uploaded} TSSL package(s) to ${target.name}"
                is TsslBackupService.BackupResult.Error -> result.message
            }
            _uiState.update {
                it.copy(backupBusy = false, backingUpFileName = null, message = message)
            }
        }
    }

    /**
     * Packages an HLS asset from the user-chosen SAF [treeUri] folder. The
     * entry playlist is located among the tree root's children; segments are
     * read relative to that folder.
     */
    fun packageFromTree(treeUri: Uri) {
        if (_uiState.value.packagingBusy) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(packagingBusy = true, packagingProgress = 0f, message = null)
            }
            try {
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val manifestName = findManifest(treeUri, treeDocId)
                val displayName = try {
                    com.vibeplayer.app.util.SafNames.displayName(context, treeUri) ?: "HLS source"
                } catch (_: Exception) {
                    "HLS source"
                }
                if (manifestName == null) {
                    _uiState.update {
                        it.copy(
                            message = "No HLS playlist (.m3u8) found in the selected folder",
                            packagingBusy = false, packagingProgress = null
                        )
                    }
                    return@launch
                }
                val source = SafHlsSource(context, treeUri, treeDocId)
                val result = packager.packageFromHls(
                    source = source,
                    manifestFileName = manifestName,
                    sourceDisplayName = displayName,
                    onProgress = { progress ->
                        _uiState.update { it.copy(packagingProgress = progress) }
                    }
                )
                val message = when (result) {
                    is EncryptedHlsPackager.PackageResult.Success ->
                        "Packaged M3U8SP v4: ${result.packageDirName}"
                    is EncryptedHlsPackager.PackageResult.Error -> result.message
                }
                _uiState.update {
                    it.copy(
                        message = message,
                        packagingBusy = false,
                        packagingProgress = null,
                        packages = tsslStore.list()
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        message = e.message ?: "Packaging failed",
                        packagingBusy = false,
                        packagingProgress = null
                    )
                }
            }
        }
    }

    /** Finds a `.m3u8` playlist among the tree root's immediate children. */
    private fun findManifest(treeUri: Uri, treeDocId: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    if (name.substringAfterLast('.').lowercase() == "m3u8") return name
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun updateBackupSettings(transform: (TsslBackupSettings) -> TsslBackupSettings) {
        viewModelScope.launch { backupSettingsStore.update(transform) }
    }

    private fun buildTarget(): TsslBackupTarget? {
        val settings = _uiState.value.backupSettings
        return when (settings.target) {
            "webdav" -> {
                val server = _uiState.value.webDavTargets.firstOrNull { it.id == settings.webDavServiceId }
                    ?: _uiState.value.webDavTargets.firstOrNull()
                if (server == null) {
                    _uiState.update { it.copy(message = "Select an available WebDAV service") }
                    null
                } else TsslBackupTarget.WebDav(server, settings.webDavPath)
            }
            "s3" -> {
                val secret = backupSettingsStore.s3Secret()
                if (secret.isNullOrEmpty()) {
                    _uiState.update { it.copy(message = "Save the S3 secret key before backing up") }
                    null
                } else TsslBackupTarget.S3(
                    endpoint = settings.s3Endpoint,
                    bucket = settings.s3Bucket,
                    region = settings.s3Region,
                    prefix = settings.s3Prefix,
                    accessKey = settings.s3AccessKey,
                    secretKey = secret,
                    trustSelfSignedCertificate = settings.trustSelfSignedCertificate
                )
            }
            else -> {
                _uiState.update { it.copy(message = "Choose a backup target first") }
                null
            }
        }
    }
}
