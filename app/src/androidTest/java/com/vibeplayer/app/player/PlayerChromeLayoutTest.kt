package com.vibeplayer.app.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibeplayer.app.ui.player.PlayerTopBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerChromeLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tallerDiagnosticsDoNotMoveTitleOrTopActions() {
        val diagnostics = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    PlayerTopBar(title = "Fixed header", subtitle = "Fixed subtitle", audioDecode = null,
                        onDismissAudioDecodeNotice = {}, onBack = {}, performance = {
                            if (diagnostics.value) Column {
                                repeat(4) { Text("Metric $it", Modifier.height(24.dp)) }
                            }
                        }, actions = { TextButton(onClick = {}) { Text("Fixed action") } })
                }
            }
        }
        val beforeTitle = compose.onNodeWithText("Fixed header").fetchSemanticsNode().boundsInRoot
        val beforeAction = compose.onNodeWithText("Fixed action").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { diagnostics.value = true }
        val afterTitle = compose.onNodeWithText("Fixed header").fetchSemanticsNode().boundsInRoot
        val afterAction = compose.onNodeWithText("Fixed action").fetchSemanticsNode().boundsInRoot
        assertEquals(beforeTitle.top, afterTitle.top, .5f)
        assertEquals(beforeAction.top, afterAction.top, .5f)
    }
}
