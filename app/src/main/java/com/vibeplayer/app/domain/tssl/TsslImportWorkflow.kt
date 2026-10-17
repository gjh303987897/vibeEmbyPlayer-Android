package com.vibeplayer.app.domain.tssl

import com.vibeplayer.app.data.local.tssl.TsslStore
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Shared local/remote, ZIP/legacy restore. A malformed ZIP contributes one failure and no imports. */
class TsslImportWorkflow @Inject constructor(private val store: TsslStore) {
    suspend fun run(files: List<File>, staging: File, checkCanceled: () -> Unit = {},
        onStage: (TsslBackupStage) -> Unit = {}): TsslRestoreSummary = withContext(Dispatchers.IO) {
        val job = coroutineContext
        val check = { job.ensureActive(); checkCanceled() }
        var result = TsslRestoreSummary(0, 0, 0)
        try {
            files.forEachIndexed { index, file ->
                check()
                try {
                    val packages = if (file.extension.equals("zip", true)) {
                        onStage(TsslBackupStage.EXTRACTING)
                        TsslBackupArchive.extract(file, File(staging, "archive-$index"), check)
                    } else listOf(file)
                    onStage(TsslBackupStage.IMPORTING)
                    for (item in packages) {
                        check()
                        when (val imported = store.restore(TsslBackupArchive.readPackage(item, check))) {
                            is TsslStore.RestoreResult.Stored -> result = result.copy(restored = result.restored + 1)
                            is TsslStore.RestoreResult.AlreadyExists -> result = result.copy(alreadyExists = result.alreadyExists + 1)
                            is TsslStore.RestoreResult.Invalid -> result = result.copy(failed = result.failed + 1, firstError = result.firstError ?: imported.reason)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { result = result.copy(failed = result.failed + 1, firstError = result.firstError ?: "Invalid or unreadable TSSL backup") }
            }
        } finally { staging.deleteRecursively() }
        result
    }
}
