package com.vibeplayer.app.search

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.MainActivity
import com.vibeplayer.app.R
import com.vibeplayer.app.ui.components.topEdgeInsets
import com.vibeplayer.app.ui.search.SearchScreen
import com.vibeplayer.app.ui.search.SearchViewModel
import com.vibeplayer.app.ui.theme.VibePlayerTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Renders the production search page in its real edge-to-edge activity, without submitting a query. */
@RunWith(AndroidJUnit4::class)
class SearchInsetsLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun searchControlsClearActualSystemBarsInBothOrientations() {
        val originalOrientation = compose.activity.requestedOrientation
        try {
            showSearch()
            for ((requested, orientation) in listOf(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE
            )) {
                compose.runOnUiThread { compose.activity.requestedOrientation = requested }
                compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == orientation }
                compose.waitForIdle()
                // Window configuration settles before SurfaceFlinger's rotation animation ends.
                // Capture the final composed screen, not the outgoing portrait layer mid-rotation.
                SystemClock.sleep(1_000)
                assertSearchWithinActualInsets()
                capture("search-insets-$orientation.png")
            }
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = originalOrientation }
        }
    }

    @Test fun ancestorConsumptionDoesNotInsetSearchTwice() {
        val ancestorConsumesInsets = mutableStateOf(false)
        showSearch { if (ancestorConsumesInsets.value) Modifier.topEdgeInsets() else Modifier }
        assertSearchWithinActualInsets()
        val before = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInWindow

        compose.runOnIdle { ancestorConsumesInsets.value = true }

        assertSearchWithinActualInsets()
        val after = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInWindow
        assertEquals("Already-consumed top inset must not add a second blank strip", before.top, after.top, 1f)
        assertEquals(before.left, after.left, 1f)
        assertEquals(before.right, after.right, 1f)
    }

    private fun showSearch(ancestorModifier: @androidx.compose.runtime.Composable () -> Modifier = { Modifier }) {
        compose.runOnUiThread {
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
            val viewModel = ViewModelProvider(compose.activity)[SearchViewModel::class.java]
            compose.activity.setContent {
                VibePlayerTheme(darkTheme = true, dynamicColor = false) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().then(ancestorModifier())) {
                            SearchScreen(navController = rememberNavController(), viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    private fun assertSearchWithinActualInsets() {
        val field = compose.onNode(hasSetTextAction()).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val back = compose.onNodeWithContentDescription(compose.activity.getString(R.string.back))
            .assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        compose.onNodeWithText(compose.activity.getString(R.string.search_prompt)).assertIsDisplayed()
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
                .getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("The regression must run with the real status bar visible", insets.top > 0)
            // The field begins after the top safe area, 8dp outer spacing and 4dp inner spacing.
            // Exact placement also catches applying the safe area twice.
            assertEquals(insets.top + 12f * density, field.top, 1f)
            assertTrue("Back action must clear the status bar/cutout", back.top >= insets.top)
            assertTrue("Back action must clear a left landscape cutout", back.left >= insets.left)
            assertTrue("Search action must clear a right landscape cutout", field.right <= decor.width - insets.right)
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "search-insets").apply { mkdirs() }
        try {
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
