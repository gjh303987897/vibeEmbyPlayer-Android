package com.vibeplayer.app.tssl

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.domain.tssl.TsslBackupArchive
import com.vibeplayer.app.domain.tssl.TsslBackupFormat
import com.vibeplayer.app.domain.tssl.TsslBackupService
import com.vibeplayer.app.domain.tssl.TsslBackupTarget
import com.vibeplayer.app.domain.tssl.TsslRestoreSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Select an empty dedicated folder in the real system picker before supplying tsslTreeUri. */
@RunWith(AndroidJUnit4::class)
class TsslSafDeviceTest {
    @Test fun realDocumentProviderBacksUpAndRestoresMixedQtAndLegacyFiles() = runBlocking {
        val selected = InstrumentationRegistry.getArguments().getString("tsslTreeUri")
        assumeTrue("A system-picker-granted test tree URI is required", !selected.isNullOrBlank())
        val tree = Uri.parse(selected!!)
        TsslDeviceHarness().use { h ->
            val original = h.local.list(tree) {}
            assumeTrue("Use an empty dedicated test folder", original.isEmpty())
            try {
                val target = TsslBackupTarget.Local(tree.toString())
                val source = h.store("source"); h.seed(source)
                val service = h.service(source)
                val names = source.list().map { it.fileName }
                assertEquals(TsslBackupService.BackupResult.Success(3), service.backup(target, names))
                val snapshot = h.local.list(tree) {}.single()
                assertTrue(TsslBackupArchive.isArchiveName(snapshot.name))
                val copy = h.file("tssl-backup-device-saf.zip")
                h.local.download(snapshot.uri, copy, TsslBackupArchive.MAX_ARCHIVE_BYTES) {}
                assertEquals(3, TsslBackupArchive.extract(copy, h.file("saf-check")).size)
                h.keepResult(copy, copy.name)

                val restored = h.store("restored")
                val restore = h.service(restored)
                assertEquals(TsslRestoreSummary(3, 0, 0), (restore.restore(target) as TsslBackupService.RestoreResult.Success).summary)
                assertEquals(TsslRestoreSummary(0, 3, 0), (restore.restore(target) as TsslBackupService.RestoreResult.Success).summary)
                assertEquals(TsslBackupService.BackupResult.Success(3), service.backup(target, names, TsslBackupFormat.FILES))
                // Identical legacy files can be backed up repeatedly without truncating them.
                assertEquals(TsslBackupService.BackupResult.Success(3), service.backup(target, names, TsslBackupFormat.FILES))
                h.local.upload(tree, h.fixture("tssl-backup-qt-fixture.zip")) {}
                h.local.upload(tree, h.fixture("tssl-backup-damaged.zip")) {}
                val mixed = h.service(h.store("mixed")).restore(target) as TsslBackupService.RestoreResult.Success
                assertEquals(3, mixed.summary.restored); assertEquals(4, mixed.summary.alreadyExists); assertEquals(1, mixed.summary.failed)

                val unchanged = source.exportBytes(names.first())!!
                val changed = h.file(names.first()).apply { writeBytes(unchanged + "\n".toByteArray()) }
                assertThrows(Exception::class.java) { h.local.upload(tree, changed) {} }
                val entry = h.local.list(tree) {}.first { it.name == changed.name }
                val downloaded = h.file("unchanged.tssl")
                h.local.download(entry.uri, downloaded, TsslBackupArchive.MAX_PACKAGE_BYTES) {}
                assertArrayEquals(unchanged, downloaded.readBytes())
            } finally {
                // Only documents created in this initially empty, dedicated test folder are removed.
                h.local.list(tree) {}.filter { item -> original.none { it.uri == item.uri } }.forEach {
                    assertTrue(DocumentsContract.deleteDocument(h.app.contentResolver, it.uri))
                }
            }
        }
    }
}
