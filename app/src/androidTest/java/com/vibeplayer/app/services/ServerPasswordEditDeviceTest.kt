package com.vibeplayer.app.services

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.rememberNavController
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibeplayer.app.MainActivity
import com.vibeplayer.app.R
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import com.vibeplayer.app.ui.services.ServicesScreen
import com.vibeplayer.app.ui.services.ServicesViewModel
import com.vibeplayer.app.ui.theme.VibePlayerTheme
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a UUID-owned fixture only; never reads or edits the user's configured services. */
@RunWith(AndroidJUnit4::class)
class ServerPasswordEditDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeEditorReplacesDavPasswordAndBlankOrCancelledEditsKeepIt() {
        val context = compose.activity.applicationContext
        lateinit var services: ServicesViewModel
        compose.runOnUiThread { services = ViewModelProvider(compose.activity)[ServicesViewModel::class.java] }
        val repository = ServicesViewModel::class.java.getDeclaredField("repository")
            .apply { isAccessible = true }.get(services) as MediaServerRepository
        val dav = ServicesViewModel::class.java.getDeclaredField("webDavRepository")
            .apply { isAccessible = true }.get(services) as WebDavRepository
        val id = "password-edit-${UUID.randomUUID()}"
        val name = "Password edit fixture ${id.takeLast(8)}"
        val replacement = " replacement-fixture-password "
        val expectedAuthorization = Credentials.basic("fixture", replacement)
        val editLabel = context.getString(R.string.edit_server)
        val passwordLabel = context.getString(R.string.password)

        MockWebServer().use { remote ->
            remote.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.getHeader("Authorization") == expectedAuthorization) {
                        MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml")
                            .setBody("<d:multistatus xmlns:d=\"DAV:\"/>")
                    } else MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=fixture")
            }
            remote.start()
            val server = ServerConfig(id, name, remote.url("/").toString(), "fixture", ServiceType.WEBDAV,
                allowInsecureHttp = true)
            try {
                runBlocking(Dispatchers.IO) {
                    repository.addServer(server)
                    check(repository.savePassword(server, "old-fixture-password"))
                }
                compose.waitUntil(15_000) { services.uiState.value.items.any { it.server.id == id } }
                compose.runOnUiThread {
                    compose.activity.setContent {
                        VibePlayerTheme(dynamicColor = false) {
                            ServicesScreen(rememberNavController(), services)
                        }
                    }
                }

                fun openEditor(title: String) {
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
                    compose.onNode(hasContentDescription(editLabel) and hasAnyAncestor(hasText(title))).performClick()
                    compose.onNodeWithText(editLabel).assertIsDisplayed()
                }
                fun passwordField(): SemanticsNodeInteraction =
                    compose.onNode(hasSetTextAction() and hasText(passwordLabel)).performScrollTo()
                fun awaitClosed() {
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithText(editLabel).fetchSemanticsNodes().isEmpty()
                    }
                    assertEquals(null, services.uiState.value.savingServerId)
                }
                fun assertSecretUnchanged() = runBlocking(Dispatchers.IO) {
                    assertEquals(replacement, repository.savedPassword(server))
                }

                openEditor(name)
                assertTrue(passwordField().fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty())
                passwordField().performTextInput(replacement)
                val renamed = "$name updated"
                compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.server_name)))
                    .performScrollTo().performTextReplacement(renamed)
                closeSoftKeyboard()
                compose.onNodeWithText(context.getString(R.string.save)).performClick()
                awaitClosed()
                compose.waitUntil(15_000) { services.uiState.value.items.any { it.server.id == id && it.server.name == renamed } }
                assertSecretUnchanged()
                runBlocking(Dispatchers.IO) {
                    val saved = repository.getServer(id)!!
                    assertEquals(id, saved.id)
                    dav.list(saved, "").getOrThrow()
                }
                val request = remote.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("PROPFIND", request.method)
                assertEquals(expectedAuthorization, request.getHeader("Authorization"))

                openEditor(renamed)
                assertTrue(passwordField().fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty())
                closeSoftKeyboard()
                compose.onNodeWithText(context.getString(R.string.save)).performClick()
                awaitClosed()
                assertSecretUnchanged()

                openEditor(renamed)
                passwordField().performTextInput("cancelled-fixture-password")
                closeSoftKeyboard()
                compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
                awaitClosed()
                assertSecretUnchanged()
            } finally {
                runBlocking(Dispatchers.IO) {
                    repository.logout(server)
                    repository.removeServer(id)
                }
            }
        }
    }
}
