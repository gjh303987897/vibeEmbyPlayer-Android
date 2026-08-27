package com.vibeplayer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import kotlinx.coroutines.delay

/**
 * Loading / failure feedback shared by every player screen.
 *
 * A player can fail without anything reaching the UI (lost storage permission,
 * unsupported container, unreachable host). Because the old screens only ever
 * drew a spinner while `buffering` was true, such a failure looked like an
 * endless "loading" — the reported "local video is stuck loading" symptom. This
 * overlay instead
 *
 * - shows a spinner plus, after a while, an explicit "still loading" hint, and
 * - shows the failure reason with a way back as soon as the player reports one.
 */
@Composable
fun PlaybackStatusOverlay(
    buffering: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val failure = error?.takeIf { it.isNotBlank() }
    var slow by remember(buffering) { mutableStateOf(false) }

    LaunchedEffect(buffering) {
        if (buffering) {
            delay(SLOW_HINT_DELAY_MS)
            slow = true
        } else {
            slow = false
        }
    }

    if (failure == null && !buffering) return

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (failure != null) {
            Text(
                text = stringResource(R.string.player_load_failed),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )
            Text(
                text = playbackErrorText(failure),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp)
            )
            if (onBack != null) {
                Button(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = stringResource(
                        if (slow) R.string.player_still_loading else R.string.player_loading
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/**
 * Turns a Media3 `PlaybackException.errorCodeName` (or a message we produced
 * ourselves) into text a user can act on. Only the error *code* ever reaches the
 * UI - the exception message is deliberately not published because it can
 * contain a stream URL with credentials.
 */
@Composable
private fun playbackErrorText(code: String): String = when {
    // Anything we produced ourselves is already user-facing text.
    !code.startsWith("ERROR_CODE_") -> code
    code == "ERROR_CODE_IO_FILE_NOT_FOUND" -> stringResource(R.string.local_file_missing)
    code == "ERROR_CODE_IO_NO_PERMISSION" -> stringResource(R.string.local_permission_lost)
    code == "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED" ||
        code == "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" ->
        stringResource(R.string.player_error_network)
    code == "ERROR_CODE_IO_BAD_HTTP_STATUS" ||
        code == "ERROR_CODE_IO_INVALID_HTTP_CONTENT" ->
        stringResource(R.string.player_error_server)
    code == "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED" ->
        stringResource(R.string.player_error_cleartext)
    code == "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" ||
        code == "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED" ||
        code == "ERROR_CODE_PARSING_CONTAINER_MALFORMED" ||
        code == "ERROR_CODE_DECODER_INIT_FAILED" ||
        code == "ERROR_CODE_DECODING_FAILED" -> stringResource(R.string.player_error_format)
    code == "ERROR_CODE_TIMEOUT" -> stringResource(R.string.player_error_timeout)
    else -> stringResource(R.string.player_load_failed)
}

private const val SLOW_HINT_DELAY_MS = 12_000L
