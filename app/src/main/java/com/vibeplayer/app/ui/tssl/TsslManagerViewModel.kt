package com.vibeplayer.app.ui.tssl

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.R
import com.vibeplayer.app.data.local.tssl.TsslStore
import com.vibeplayer.app.data.local.datastore.TsslBackupSettings
import com.vibeplayer.app.data.local.datastore.TsslBackupSettingsStore
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.domain.tssl.EncryptedHlsPackager
import com.vibeplayer.app.domain.tssl.TsslBackupService
import com.vibeplayer.app.domain.tssl.TsslBackupTarget
import com.vibeplayer.app.domain.tssl.TsslBackupFormat
import com.vibeplayer.app.domain.tssl.TsslBackupStage
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.MessageTone
import com.vibeplayer.app.model.ServiceType
import com.vibeplayer.app.model.TsslPackage
import com.vibeplayer.app.player.hls.SafHlsSource
import com.vibeplayer.app.util.normalizeUrlInput
import com.vibeplayer.app.security.PrivacyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TsslManagerUiState(
    val packages: List<TsslPackage> = emptyList(),
    val webDavTargets: List<ServerConfig> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val messageTone: MessageTone = MessageTone.INFO,
    val backupBusy: Boolean = false,
    val packagingBusy: Boolean = false,
    /** Progress 0..1 while packaging, null when idle. */
    val packagingProgress: Float? = null,
    val backupSettings: TsslBackupSettings = TsslBackupSettings(),
    val s3SecretConfigured: Boolean = false,
    val restoring: Boolean = false,
    val restoreProgress: Float? = null,
    val backupStage: TsslBackupStage? = null
)

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TsslManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tsslStore: TsslStore,
    private val mediaServerRepository: MediaServerRepository,
    private val backupService: TsslBackupService,
    private val backupSettingsStore: TsslBackupSettingsStore,
    private val packager: EncryptedHlsPackager,
    private val privacyManager: PrivacyManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(TsslManagerUiState())
    val uiState: StateFlow<TsslManagerUiState> = _uiState.asStateFlow()
    private var operationJob: Job? = null

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
            privacyManager.privacyMode
                .flatMapLatest { includePrivate ->
                    mediaServerRepository.observeVisibleServices(includePrivate)
                }
                .collect { servers ->
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

    fun setBackupTarget(target: String) = updateBackupSettings { it.copy(target = target) }
    fun setBackupFormat(format: TsslBackupFormat) = updateBackupSettings { it.copy(format = format) }
    fun cancelBackup() { operationJob?.cancel() }

    fun setLocalFolder(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.takePersistableUriPermission(uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                    val name = com.vibeplayer.app.util.SafNames.displayName(context, document)
                        ?: DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/')
                    backupSettingsStore.update { it.copy(target = "local", localTreeUri = uri.toString(), localFolderName = name) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _uiState.update { it.copy(message = context.getString(R.string.message_error_generic), messageTone = MessageTone.ERROR) }
            }
        }
    }
    fun setWebDavServiceId(id: String) = updateBackupSettings { it.copy(webDavServiceId = id) }
    fun setWebDavPath(path: String) = updateBackupSettings { it.copy(webDavPath = path) }
    fun setS3Endpoint(value: String) = updateBackupSettings {
        it.copy(s3Endpoint = normalizeUrlInput(value))
    }
    fun setS3Bucket(value: String) = updateBackupSettings { it.copy(s3Bucket = value) }
    fun setS3Region(value: String) = updateBackupSettings { it.copy(s3Region = value) }
    fun setS3Prefix(value: String) = updateBackupSettings { it.copy(s3Prefix = value) }
    fun setS3AccessKey(value: String) = updateBackupSettings { it.copy(s3AccessKey = value) }
    fun setTrustSelfSigned(value: Boolean) = updateBackupSettings { it.copy(trustSelfSignedCertificate = value) }

    fun saveS3Secret(secret: String) {
        val saved = backupSettingsStore.saveS3Secret(secret)
        _uiState.update {
            it.copy(
                message = if (saved) context.getString(R.string.tssl_s3_secret_saved)
                else context.getString(R.string.tssl_message_secret_save_failed),
                messageTone = if (saved) MessageTone.SUCCESS else MessageTone.ERROR
            )
        }
    }

    private fun busy() = _uiState.value.backupBusy || _uiState.value.restoring || _uiState.value.packagingBusy || operationJob?.isActive == true

    fun backupAll() = startBackup()
    fun backup(target: ServerConfig, pkg: TsslPackage) = startBackup(target, pkg)
    fun restoreBackup() = startRestore()
    fun importUri(uri: Uri) = startRestore(uri)

    private fun startBackup(override: ServerConfig? = null, pkg: TsslPackage? = null) {
        if (busy()) return
        val format = _uiState.value.backupSettings.format
        _uiState.update { it.copy(backupBusy = true, backupStage = TsslBackupStage.SCANNING, message = null) }
        operationJob = viewModelScope.launch {
            try {
                val target = override?.let { TsslBackupTarget.WebDav(it, _uiState.value.backupSettings.webDavPath) }
                    ?: buildTarget() ?: return@launch
                val names = pkg?.let { listOf(it.fileName) } ?: tsslStore.list().filter { it.isValid }.map { it.fileName }
                if (names.isEmpty()) {
                    _uiState.update { it.copy(message = context.getString(R.string.tssl_message_no_valid_packages), messageTone = MessageTone.WARNING) }
                    return@launch
                }
                val result = backupService.backup(target, names, format,
                    onStage = { stage -> _uiState.update { it.copy(backupStage = stage) } })
                _uiState.update { it.copy(
                    message = when (result) {
                        is TsslBackupService.BackupResult.Success -> context.getString(R.string.tssl_message_backup_count, result.uploaded)
                        is TsslBackupService.BackupResult.Error -> result.message
                    },
                    messageTone = if (result is TsslBackupService.BackupResult.Success) MessageTone.SUCCESS else MessageTone.ERROR
                ) }
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(message = context.getString(R.string.tssl_operation_canceled), messageTone = MessageTone.INFO) }
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(message = context.getString(R.string.message_error_generic), messageTone = MessageTone.ERROR) }
            } finally {
                _uiState.update { it.copy(backupBusy = false, backupStage = null) }
            }
        }
    }

    private fun startRestore(uri: Uri? = null) {
        if (busy()) return
        _uiState.update { it.copy(restoring = true, restoreProgress = 0f, backupStage = TsslBackupStage.LISTING, message = null) }
        operationJob = viewModelScope.launch {
            try {
                val stage: (TsslBackupStage) -> Unit = { value -> _uiState.update { it.copy(backupStage = value) } }
                val result = if (uri != null) backupService.importUri(uri, stage)
                    else {
                        val target = buildTarget() ?: return@launch
                        backupService.restore(target, stage) { done, total ->
                            _uiState.update { it.copy(restoreProgress = if (total > 0) done.toFloat() / total else 1f) }
                        }
                    }
                _uiState.update { it.copy(
                    message = when (result) {
                        is TsslBackupService.RestoreResult.Success -> context.getString(R.string.tssl_message_restore_summary,
                            result.summary.restored, result.summary.alreadyExists, result.summary.failed)
                        is TsslBackupService.RestoreResult.Error -> result.message
                    },
                    messageTone = when (result) {
                        is TsslBackupService.RestoreResult.Success -> if (result.summary.failed > 0) MessageTone.WARNING else MessageTone.SUCCESS
                        is TsslBackupService.RestoreResult.Error -> MessageTone.ERROR
                    }
                ) }
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(message = context.getString(R.string.tssl_operation_canceled), messageTone = MessageTone.INFO) }
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(message = context.getString(R.string.message_error_generic), messageTone = MessageTone.ERROR) }
            } finally {
                _uiState.update { it.copy(restoring = false, restoreProgress = null, backupStage = null) }
                refresh()
            }
        }
    }

    fun delete(pkg: TsslPackage) {
        if (busy()) return
        viewModelScope.launch {
            tsslStore.delete(pkg.fileName)
            _uiState.update {
                it.copy(
                    packages = tsslStore.list(),
                    message = context.getString(R.string.tssl_message_deleted, pkg.fileName),
                    messageTone = MessageTone.SUCCESS
                )
            }
        }
    }

    /** Exports a validated package through SAF on the IO worker. */
    fun exportToUri(pkg: TsslPackage, uri: Uri) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = tsslStore.exportBytes(pkg.fileName) ?: error("Unreadable TSSL package")
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Unable to export TSSL package")
                }.isSuccess
            }
            if (!saved) {
                _uiState.update {
                    it.copy(
                        message = context.getString(R.string.tssl_message_unreadable),
                        messageTone = MessageTone.ERROR
                    )
                }
            }
        }
    }

    /**
     * Packages an HLS asset from the user-chosen SAF [treeUri] folder. The
     * entry playlist is located among the tree root's children; segments are
     * read relative to that folder.
     */
    fun packageFromTree(treeUri: Uri) {
        if (busy()) return
        _uiState.update { it.copy(packagingBusy = true, packagingProgress = 0f, message = null) }
        viewModelScope.launch {
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
                            message = context.getString(R.string.tssl_message_no_playlist),
                            messageTone = MessageTone.ERROR,
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
                        context.getString(R.string.tssl_message_packaged, result.packageDirName)
                    is EncryptedHlsPackager.PackageResult.Error -> result.message
                }
                val messageTone = when (result) {
                    is EncryptedHlsPackager.PackageResult.Success -> MessageTone.SUCCESS
                    is EncryptedHlsPackager.PackageResult.Error -> MessageTone.ERROR
                }
                _uiState.update {
                    it.copy(
                        message = message,
                        messageTone = messageTone,
                        packagingBusy = false,
                        packagingProgress = null,
                        packages = tsslStore.list()
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        message = e.message ?: context.getString(R.string.message_error_generic),
                        messageTone = MessageTone.ERROR,
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
            "local" -> if (settings.localTreeUri.isBlank()) {
                _uiState.update { it.copy(message = context.getString(R.string.tssl_choose_folder), messageTone = MessageTone.WARNING) }
                null
            } else TsslBackupTarget.Local(settings.localTreeUri)
            "webdav" -> {
                val server = _uiState.value.webDavTargets.firstOrNull { it.id == settings.webDavServiceId }
                    ?: _uiState.value.webDavTargets.firstOrNull()
                if (server == null) {
                    _uiState.update {
                        it.copy(
                            message = context.getString(R.string.tssl_message_select_webdav),
                            messageTone = MessageTone.WARNING
                        )
                    }
                    null
                } else TsslBackupTarget.WebDav(server, settings.webDavPath)
            }
            "s3" -> {
                val secret = backupSettingsStore.s3Secret()
                if (secret.isNullOrEmpty()) {
                    _uiState.update {
                        it.copy(
                            message = context.getString(R.string.tssl_message_save_secret_first),
                            messageTone = MessageTone.WARNING
                        )
                    }
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
                _uiState.update {
                    it.copy(
                        message = context.getString(R.string.tssl_message_choose_target),
                        messageTone = MessageTone.WARNING
                    )
                }
                null
            }
        }
    }
}
