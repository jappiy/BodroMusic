package ru.bodro.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.bodro.music.R
import ru.bodro.music.ui.util.UiError

/** Состояние загрузки экрана. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>

    data class Error(val error: UiError) : LoadState<Nothing>

    data class Content<T>(val data: T) : LoadState<T>
}

val LocalOpenLogs = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().testTag("loading"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorState(error: UiError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val openLogs = LocalOpenLogs.current
    MessageState(
        icon = if (error.isOffline) Icons.Rounded.CloudOff else Icons.Rounded.ReportProblem,
        title = stringResource(if (error.isOffline) R.string.state_offline_title else R.string.state_error_title),
        message = stringResource(error.message) + (error.details?.let { "\n$it" } ?: ""),
        modifier = modifier,
    ) {
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        TextButton(onClick = openLogs) { Text(stringResource(R.string.action_open_logs)) }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.LibraryMusic,
    action: (@Composable () -> Unit)? = null,
) {
    MessageState(icon, title, message, modifier) { action?.invoke() }
}

@Composable
private fun MessageState(
    icon: ImageVector,
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            actions()
            Spacer(Modifier.weight(1f))
        }
    }
}
