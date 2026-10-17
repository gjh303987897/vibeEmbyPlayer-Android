package com.vibeplayer.app.domain.tssl

import com.vibeplayer.app.data.local.tssl.TsslStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TsslImportWorkflowTest {
    @get:Rule val temporary = TemporaryFolder()
    private val storage get() = File(temporary.root, "store")
    private fun file(name: String) = File(temporary.root, name)

    @Test fun `mixed ZIP legacy and duplicate backups restore without replacing local packages`() = runBlocking {
        val first = backupPackage(); val second = backupPackage("22".repeat(32), 3)
        val store = TsslStore(storage)
        val zip = backupZip(file("snapshot.zip"), backupEntries(first, second))
        val legacy = file("legacy.tssl").apply { writeBytes(first) }
        val damaged = file("tssl-backup-damaged.zip").apply { writeText("bad zip") }
        val result = TsslImportWorkflow(store).run(listOf(zip, legacy, damaged), file("staging"))
        assertEquals(2, result.restored); assertEquals(1, result.alreadyExists); assertEquals(1, result.failed)
        assertArrayEquals(first, store.exportBytes("11".repeat(32) + ".tssl"))
        assertArrayEquals(second, store.exportBytes("22".repeat(32) + ".tssl"))
        assertFalse(file("staging").exists())
        val again = TsslImportWorkflow(store).run(listOf(zip, legacy), file("staging"))
        assertEquals(TsslRestoreSummary(0, 3, 0), again)
    }

    @Test fun `corrupt second ZIP entry imports nothing from that ZIP and continues other files`() = runBlocking {
        val entries = backupEntries(backupPackage(), backupPackage("22".repeat(32))).toMutableList()
        val manifest = JSONObject(String(entries.last().second))
        manifest.getJSONArray("packages").getJSONObject(1).put("sha256", "00".repeat(32))
        entries[entries.lastIndex] = "manifest.json" to manifest.toString().toByteArray()
        val zip = backupZip(file("corrupt.zip"), entries)
        val legacy = file("legacy.tssl").apply { writeBytes(backupPackage("33".repeat(32))) }
        val store = TsslStore(storage)
        val result = TsslImportWorkflow(store).run(listOf(zip, legacy), file("staging"))
        assertEquals(1, result.restored); assertEquals(1, result.failed); assertEquals(0, result.alreadyExists)
        assertEquals(listOf("33".repeat(32) + ".tssl"), store.list().map { it.fileName })
    }

    @Test fun `existing root retains original bytes even when backup content differs`() = runBlocking {
        val original = backupPackage(note = "existing")
        val incoming = backupPackage(note = "do not replace")
        val store = TsslStore(storage)
        assertTrue(store.restore(original) is TsslStore.RestoreResult.Stored)
        val zip = backupZip(file("new.zip"), backupEntries(incoming))
        assertEquals(TsslRestoreSummary(0, 1, 0), TsslImportWorkflow(store).run(listOf(zip), file("staging")))
        assertArrayEquals(original, store.exportBytes("11".repeat(32) + ".tssl"))
    }

    @Test fun `cancelled import keeps prior imports and removes extraction workspace`() = runBlocking {
        val zip = backupZip(file("cancel.zip"), backupEntries(backupPackage(), backupPackage("22".repeat(32))))
        val store = TsslStore(storage)
        var importing = false
        try {
            TsslImportWorkflow(store).run(listOf(zip), file("staging"), checkCanceled = {
                if (importing && storage.listFiles()?.count { it.extension == "tssl" } == 1) throw CancellationException("test")
            }, onStage = { if (it == TsslBackupStage.IMPORTING) importing = true })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, store.list().size)
        assertFalse(file("staging").exists())
    }

    @Test fun `concurrent restores publish a root once without temporary leftovers`() = runBlocking {
        val store = TsslStore(storage); val bytes = backupPackage()
        val results = (1..8).map { async { store.restore(bytes) } }.awaitAll()
        assertEquals(1, results.count { it is TsslStore.RestoreResult.Stored })
        assertEquals(7, results.count { it is TsslStore.RestoreResult.AlreadyExists })
        assertEquals(1, storage.listFiles()!!.size)
        assertArrayEquals(bytes, store.exportBytes("11".repeat(32) + ".tssl"))
    }
}
