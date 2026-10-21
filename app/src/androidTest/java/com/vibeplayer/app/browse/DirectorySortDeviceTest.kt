package com.vibeplayer.app.browse

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.MainActivity
import com.vibeplayer.app.R
import com.vibeplayer.app.VibePlayerApp
import com.vibeplayer.app.data.local.db.entity.LocalMediaRootEntity
import com.vibeplayer.app.model.BrowseSortOrder
import com.vibeplayer.app.model.BrowseSource
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import com.vibeplayer.app.player.PlaybackQueueItem
import com.vibeplayer.app.player.PlaybackQueueScope
import com.vibeplayer.app.ui.local.LocalBrowseScreen
import com.vibeplayer.app.ui.local.LocalBrowseViewModel
import com.vibeplayer.app.ui.services.ServicesViewModel
import com.vibeplayer.app.ui.services.ServerForm
import com.vibeplayer.app.ui.theme.VibePlayerTheme
import com.vibeplayer.app.ui.webdav.WebDavBrowseScreen
import com.vibeplayer.app.ui.webdav.WebDavBrowseViewModel
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectorySortDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realBrowseScreensSortSaveIndependentlyAndStageVisibleQueueWithoutRefetch() {
        val app = compose.activity.application as VibePlayerApp
        val settings = app.settingsDataStore
        val originalLocal = runBlocking { settings.browseSortOrder(BrowseSource.LOCAL).first() }
        val originalDav = runBlocking { settings.browseSortOrder(BrowseSource.WEBDAV).first() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixtureUri = Uri.parse("content://${instrumentation.context.packageName}.sort-fixture")
        val tree = DocumentsContract.buildTreeDocumentUri(fixtureUri.authority!!, "root")
        // Huawei blocks a cold background launch of another APK's provider. Start
        // only our synthetic test provider via shell before the real app queries it.
        instrumentation.uiAutomation.executeShellCommand("content call --uri $fixtureUri --method count").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
        val remote = MockWebServer()
        val propfinds = AtomicInteger()
        remote.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method != "PROPFIND") return MockResponse().setResponseCode(404)
                propfinds.incrementAndGet()
                assertEquals("1", request.getHeader("Depth"))
                val rows = listOf(Triple("gamma.mp4", 100, 3), Triple("FolderZ/", 0, 1),
                    Triple("beta.mp4", 300, 2), Triple("FolderA/", 0, 3), Triple("Alpha.mkv", 600, 1))
                val xml = rows.joinToString("") { (name, size, date) ->
                    """<d:response><d:href>/$name</d:href><d:propstat><d:prop>
                        <d:resourcetype>${if (name.endsWith('/')) "<d:collection/>" else ""}</d:resourcetype>
                        <d:getcontentlength>$size</d:getcontentlength>
                        <d:getlastmodified>${java.time.ZonedDateTime.of(2026, 10, date, 0, 0, 0, 0, java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)}</d:getlastmodified>
                        </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
                }
                return MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml")
                    .setBody("<d:multistatus xmlns:d=\"DAV:\">$xml</d:multistatus>")
            }
        }
        remote.start()
        var server = ServerConfig(UUID.randomUUID().toString(), "Sort device fixture ${UUID.randomUUID()}", remote.url("/").toString(), "fixture", ServiceType.WEBDAV)
        val davVisible = mutableStateOf(false)
        val dark = mutableStateOf(false)
        lateinit var local: LocalBrowseViewModel
        lateinit var dav: WebDavBrowseViewModel
        lateinit var services: ServicesViewModel
        val extraStore = ViewModelStore()
        try {
            runBlocking {
                settings.setBrowseSortOrder(BrowseSource.LOCAL, BrowseSortOrder.NAME_ASC)
                settings.setBrowseSortOrder(BrowseSource.WEBDAV, BrowseSortOrder.NAME_ASC)
            }
            compose.runOnUiThread {
                local = ViewModelProvider(compose.activity)[LocalBrowseViewModel::class.java]
                dav = ViewModelProvider(compose.activity)[WebDavBrowseViewModel::class.java]
                services = ViewModelProvider(compose.activity)[ServicesViewModel::class.java]
            }
            // The service is added/removed through the existing production repository entry point.
            compose.runOnUiThread {
                services.addServer(ServerForm(name = server.name, host = "127.0.0.1", port = remote.port.toString(),
                    username = "fixture", serviceType = ServiceType.WEBDAV), "fixture-password")
            }
            compose.waitUntil(10_000) { services.uiState.value.items.any { it.server.name == server.name } && !services.uiState.value.addingServer }
            server = services.uiState.value.items.single { it.server.name == server.name }.server
            compose.runOnUiThread {
                local.openRoot(LocalMediaRootEntity(tree.toString(), "Sort fixture", tree.toString(), 0, true))
                compose.activity.setContent {
                    val showDav by davVisible
                    val darkTheme by dark
                    VibePlayerTheme(darkTheme = darkTheme, dynamicColor = false) {
                        val nav = rememberNavController()
                        if (showDav) WebDavBrowseScreen(server.id, nav, dav) else LocalBrowseScreen(nav, local)
                    }
                }
            }
            compose.waitUntil(10_000) { local.uiState.value.items.size == 5 && !local.uiState.value.loading }
            fun localQueries() = app.contentResolver.call(fixtureUri, "count", null, null)!!.getInt("queries")
            val count = localQueries()
            for (order in BrowseSortOrder.entries) {
                choose(order)
                compose.waitUntil { local.uiState.value.sortOrder == order && local.uiState.value.items.map { it.name } == expected(order) }
                assertNull(local.uiState.value.error)
                assertEquals(count, localQueries())
                assertEquals(order, runBlocking { settings.browseSortOrder(BrowseSource.LOCAL).first() })
                assertEquals(BrowseSortOrder.NAME_ASC, runBlocking { settings.browseSortOrder(BrowseSource.WEBDAV).first() })
                assertVisibleRows(expected(order))
                compose.runOnUiThread {
                    val files = local.uiState.value.items.filterNot { it.isDirectory }
                    local.preparePlayback(files[1])
                    app.playerManager.queue.attach(this, PlaybackQueueScope(PlaybackSource.LOCAL),
                        PlaybackQueueItem(files[1].uri, files[1].name), {}, {})
                    assertEquals(files.map { it.name }, app.playerManager.queue.state.value.items.map { it.title })
                    assertEquals(1, app.playerManager.queue.state.value.index)
                    app.playerManager.queue.detach(this)
                }
            }
            capture("sort-local.png")
            compose.runOnUiThread { davVisible.value = true; dark.value = true }
            compose.waitUntil(10_000) { dav.uiState.value.items.size == 5 && !dav.uiState.value.loading }
            val requests = propfinds.get()
            for (order in BrowseSortOrder.entries) {
                choose(order)
                compose.waitUntil { dav.uiState.value.sortOrder == order && dav.uiState.value.items.map { it.name } == expected(order) }
                assertNull(dav.uiState.value.error)
                assertEquals(requests, propfinds.get())
                assertEquals(order, runBlocking { settings.browseSortOrder(BrowseSource.WEBDAV).first() })
                assertEquals(BrowseSortOrder.MODIFIED_OLDEST, runBlocking { settings.browseSortOrder(BrowseSource.LOCAL).first() })
                assertVisibleRows(expected(order))
                compose.runOnUiThread {
                    val files = dav.uiState.value.items.filter { it.isVideo && !it.isDirectory }
                    dav.preparePlayback(files[1])
                    app.playerManager.queue.attach(this, PlaybackQueueScope(PlaybackSource.WEBDAV, server.id),
                        PlaybackQueueItem(files[1].path, files[1].name), {}, {})
                    assertEquals(files.map { it.path }, app.playerManager.queue.state.value.items.map { it.id })
                    assertEquals(1, app.playerManager.queue.state.value.index)
                    app.playerManager.queue.detach(this)
                }
            }
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.browse_sort)).performClick()
            capture("sort-webdav-dark-menu.png")
            compose.onNodeWithText(compose.activity.getString(R.string.browse_sort_modified_oldest)).assertIsSelected().performClick()
            // Fresh ViewModels collect persisted settings rather than retaining in-memory state.
            compose.runOnUiThread {
                val provider = ViewModelProvider(extraStore, compose.activity.defaultViewModelProviderFactory,
                    compose.activity.defaultViewModelCreationExtras)
                val freshLocal = provider.get("sort-recreated-local", LocalBrowseViewModel::class.java)
                val freshDav = provider.get("sort-recreated-dav", WebDavBrowseViewModel::class.java)
                freshLocal.load()
                freshDav.load(server.id)
                recreatedLocal = freshLocal
                recreatedDav = freshDav
            }
            compose.waitUntil(10_000) {
                recreatedLocal!!.uiState.value.sortOrder == BrowseSortOrder.MODIFIED_OLDEST &&
                    recreatedDav!!.uiState.value.sortOrder == BrowseSortOrder.MODIFIED_OLDEST &&
                    recreatedDav!!.uiState.value.items.size == 5
            }
            compose.runOnUiThread { davVisible.value = false; dark.value = false }
            compose.onNodeWithText("Alpha.mkv").assertIsDisplayed()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.browse_sort)).performClick()
            capture("sort-local-light-menu.png")
        } finally {
            compose.runOnUiThread { app.playerManager.queue.detach(this); extraStore.clear() }
            compose.runOnUiThread { services.removeServer(server) }
            compose.waitUntil(10_000) { services.uiState.value.items.none { it.server.id == server.id } }
            runBlocking {
                settings.setBrowseSortOrder(BrowseSource.LOCAL, originalLocal)
                settings.setBrowseSortOrder(BrowseSource.WEBDAV, originalDav)
            }
            remote.shutdown()
        }
    }

    private var recreatedLocal: LocalBrowseViewModel? = null
    private var recreatedDav: WebDavBrowseViewModel? = null

    private fun choose(order: BrowseSortOrder) {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.browse_sort)).performClick()
        compose.onNodeWithText(compose.activity.getString(when (order) {
            BrowseSortOrder.NAME_ASC -> R.string.browse_sort_name_asc
            BrowseSortOrder.NAME_DESC -> R.string.browse_sort_name_desc
            BrowseSortOrder.SIZE_ASC -> R.string.browse_sort_size_asc
            BrowseSortOrder.SIZE_DESC -> R.string.browse_sort_size_desc
            BrowseSortOrder.MODIFIED_NEWEST -> R.string.browse_sort_modified_newest
            BrowseSortOrder.MODIFIED_OLDEST -> R.string.browse_sort_modified_oldest
        })).performClick()
    }

    private fun expected(order: BrowseSortOrder): List<String> {
        val folders = if (order == BrowseSortOrder.NAME_DESC || order == BrowseSortOrder.MODIFIED_OLDEST)
            listOf("FolderZ", "FolderA") else listOf("FolderA", "FolderZ")
        val files = if (order == BrowseSortOrder.NAME_DESC || order == BrowseSortOrder.SIZE_ASC || order == BrowseSortOrder.MODIFIED_NEWEST)
            listOf("gamma.mp4", "beta.mp4", "Alpha.mkv") else listOf("Alpha.mkv", "beta.mp4", "gamma.mp4")
        return folders + files
    }

    private fun assertVisibleRows(names: List<String>) {
        val tops = names.map { name -> compose.onNodeWithText(name).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertTrue("Rows must follow selected order on screen: $names, $tops", tops.zipWithNext().all { (a, b) -> a < b })
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "directory-sort-results").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(output, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
