package com.vibeplayer.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import com.vibeplayer.app.model.MessageTone
import com.vibeplayer.app.util.UserErrorKind
import com.vibeplayer.app.util.UserMessageFormatter

/** Snackbar visuals carrying the tone that should be used by [AppSnackbarHost]. */
data class AppSnackbarVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = true,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
    val tone: MessageTone = MessageTone.INFO
) : SnackbarVisuals

/** Shows one of the app's consistently styled, accessible transient messages. */
suspend fun SnackbarHostState.showAppSnackbar(
    message: String,
    tone: MessageTone = MessageTone.INFO,
    actionLabel: String? = null,
    duration: SnackbarDuration = SnackbarDuration.Short
) {
    showSnackbar(
        AppSnackbarVisuals(
            message = message,
            actionLabel = actionLabel,
            duration = duration,
            tone = tone
        )
    )
}

/**
 * Material 3 snackbar with a leading status icon, a readable title/body pair,
 * and enough contrast in both light and dark themes.
 */
@Composable
fun AppSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val visuals = data.visuals as? AppSnackbarVisuals
        val tone = visuals?.tone ?: MessageTone.ERROR
        val body = if (tone == MessageTone.ERROR) {
            userReadableError(data.visuals.message)
        } else {
            data.visuals.message
        }
        val colors = messageColors(tone)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            color = colors.container,
            contentColor = colors.content,
            tonalElevation = 4.dp,
            shadowElevation = 3.dp
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = toneIcon(tone),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(messageTitle(tone)),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.content
                    )
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.content.copy(alpha = 0.92f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                data.visuals.actionLabel?.let { label ->
                    TextButton(onClick = data::performAction) {
                        Text(label, color = colors.accent)
                    }
                }
                if (data.visuals.withDismissAction) {
                    IconButton(onClick = data::dismiss) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.message_dismiss),
                            tint = colors.content.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

/** A persistent message surface for errors that block a page from rendering. */
@Composable
fun AppMessageCard(
    message: String,
    modifier: Modifier = Modifier,
    tone: MessageTone = MessageTone.ERROR,
    onDismiss: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = messageColors(tone)
    val body = if (tone == MessageTone.ERROR) userReadableError(message) else message
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.container,
            contentColor = colors.content
        ),
        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = toneIcon(tone),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.padding(top = 2.dp).size(22.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(messageTitle(tone)),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.content
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.content.copy(alpha = 0.92f)
                )
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction, modifier = Modifier.padding(start = 0.dp)) {
                        Text(actionLabel, color = colors.accent)
                    }
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.message_dismiss),
                        tint = colors.content.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

/** Compact inline variant for validation errors inside an input card or row. */
@Composable
fun AppInlineError(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = userReadableError(message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/** Resolves a technical error string to a localized, actionable sentence. */
@Composable
fun userReadableError(raw: String?, fallbackRes: Int = R.string.message_error_generic): String {
    val normalized = raw?.trim()?.lowercase().orEmpty()
    // Keep the more specific copy that already exists for local/playback
    // failures. These strings are emitted by the ViewModels intentionally and
    // should not be reduced to a generic network category.
    when {
        "invalid tssl package" in normalized -> return stringResource(R.string.tssl_invalid_package)
        "invalid playback address" in normalized -> return stringResource(R.string.player_invalid_url)
        "this file cannot be found" in normalized -> return stringResource(R.string.local_file_missing)
        "no permission to read" in normalized -> return stringResource(R.string.local_permission_lost)
        "unsupported location" in normalized -> return stringResource(R.string.local_unsupported_location)
        "unable to prepare playback" in normalized -> return stringResource(R.string.player_load_failed)
        "no hls playlist" in normalized -> return stringResource(R.string.message_error_format)
    }
    val classification = UserMessageFormatter.classify(raw)
    return when (classification.kind) {
        UserErrorKind.NETWORK -> stringResource(R.string.message_error_network)
        UserErrorKind.AUTHENTICATION -> stringResource(R.string.message_error_authentication)
        UserErrorKind.PERMISSION -> stringResource(R.string.message_error_permission)
        UserErrorKind.NOT_FOUND -> stringResource(R.string.message_error_not_found)
        UserErrorKind.TIMEOUT -> stringResource(R.string.message_error_timeout)
        UserErrorKind.INVALID_INPUT -> stringResource(R.string.message_error_invalid_input)
        UserErrorKind.REQUEST_REJECTED -> stringResource(R.string.message_error_request_rejected)
        UserErrorKind.SERVER -> stringResource(R.string.message_error_server)
        UserErrorKind.FORMAT -> stringResource(R.string.message_error_format)
        UserErrorKind.UNKNOWN -> classification.detail
            ?: stringResource(fallbackRes)
    }
}

private data class MessageColors(val container: Color, val content: Color, val accent: Color)

@Composable
private fun messageColors(tone: MessageTone): MessageColors {
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        MessageTone.ERROR -> MessageColors(scheme.errorContainer, scheme.onErrorContainer, scheme.error)
        MessageTone.WARNING -> MessageColors(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary)
        MessageTone.SUCCESS -> MessageColors(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
        MessageTone.INFO -> MessageColors(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary)
    }
}

private fun toneIcon(tone: MessageTone) = when (tone) {
    MessageTone.ERROR -> Icons.Outlined.ErrorOutline
    MessageTone.WARNING -> Icons.Outlined.Warning
    MessageTone.SUCCESS -> Icons.Outlined.CheckCircle
    MessageTone.INFO -> Icons.Outlined.Info
}

private fun messageTitle(tone: MessageTone): Int = when (tone) {
    MessageTone.ERROR -> R.string.message_error_title
    MessageTone.WARNING -> R.string.message_warning_title
    MessageTone.SUCCESS -> R.string.message_success_title
    MessageTone.INFO -> R.string.message_info_title
}
