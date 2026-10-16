package ru.bodro.music.ui.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.downloads.MaintenanceAction
import ru.bodro.music.core.downloads.MaintenanceRun
import ru.bodro.music.core.downloads.MaintenanceStatus
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.EmptyState
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.util.UiError
import ru.bodro.music.ui.util.formatTime
import ru.bodro.music.ui.util.text
import ru.bodro.music.ui.util.toUiError

/** Виды записей отчёта (как их называет bodrod). `null` — все. */
val MaintenanceKinds = listOf(null, "duplicate", "album", "short", "tags", "cover")

data class MaintenanceUi(
    val loading: Boolean = true,
    val status: MaintenanceStatus? = null,
    val kind: String? = null,
    val total: Int = 0,
    val actions: List<MaintenanceAction> = emptyList(),
    val starting: Boolean = false,
    val error: UiError? = null,
)

/**
 * «Обслуживание библиотеки» (только администраторы): отчёт регламента bodrod — что исправлено
 * (теги, обложки, дубли, альбомы, короткие файлы) и что только найдено; запуск вручную; возврат из карантина.
 */
@HiltViewModel
class MaintenanceViewModel @Inject constructor(private val downloads: DownloadsRepository) : ViewModel() {
    private val _state = MutableStateFlow(MaintenanceUi())
    val state: StateFlow<MaintenanceUi> = _state.asStateFlow()
    private var pollJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.status == null, error = null) }
            runCatching { reload() }.onFailure { e -> _state.update { it.copy(loading = false, error = e.toUiError()) } }
        }
    }

    fun setKind(kind: String?) {
        _state.update { it.copy(kind = kind) }
        load()
    }

    fun runNow(onError: (Throwable) -> Unit) {
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            runCatching { downloads.runMaintenance() }.onFailure(onError)
            runCatching { reload() }
            _state.update { it.copy(starting = false) }
        }
    }

    fun restore(action: MaintenanceAction, onDone: (Throwable?) -> Unit) {
        val id = action.quarantineId ?: return
        viewModelScope.launch {
            val result = runCatching { downloads.restoreFromQuarantine(id) }
            onDone(result.exceptionOrNull())
            runCatching { reload() }
        }
    }

    private suspend fun reload() {
        val status = downloads.maintenanceStatus()
        val page = downloads.maintenanceActions(runId = null, kind = _state.value.kind, limit = PAGE, offset = 0)
        _state.update { it.copy(loading = false, status = status, total = page.total, actions = page.items, error = null) }
        if (status.running) pollWhileRunning()
    }

    private fun pollWhileRunning() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            var running = true
            while (running) {
                delay(POLL_MS)
                runCatching { downloads.maintenanceStatus() }.getOrNull()?.let { status ->
                    _state.update { it.copy(status = status) }
                    running = status.running
                }
            }
            runCatching { reload() }
        }
    }

    private companion object {
        const val PAGE = 300
        const val POLL_MS = 3_000L
    }
}

@Composable
fun MaintenanceScreen(onBack: () -> Unit, viewModel: MaintenanceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    var pending by remember { mutableStateOf<MaintenanceAction?>(null) }

    Scaffold(topBar = { BodroTopBar(stringResource(R.string.maintenance_title), onBack) }) { padding ->
        val error = state.error
        val status = state.status
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            error != null && status == null -> ErrorState(error, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            status != null -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("maintenance-screen")) {
                item(key = "summary") {
                    Summary(status, starting = state.starting) {
                        viewModel.runNow { e -> actions.showMessage(e.toUiError().text(context)) }
                    }
                }
                item(key = "kinds") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MaintenanceKinds.forEach { kind ->
                            FilterChip(
                                selected = state.kind == kind,
                                onClick = { viewModel.setKind(kind) },
                                label = { Text(kindLabel(kind)) },
                            )
                        }
                    }
                }
                if (state.actions.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            stringResource(R.string.maintenance_empty),
                            stringResource(R.string.maintenance_empty_hint),
                            modifier = Modifier.height(320.dp),
                            icon = Icons.Rounded.AutoFixHigh,
                        )
                    }
                }
                items(state.actions, key = { it.id }) { action ->
                    ActionRow(action, onRestore = { pending = action })
                }
            }
        }
    }

    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.maintenance_restore_title)) },
            text = { Text(stringResource(R.string.maintenance_restore_text, action.path.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    viewModel.restore(action) { e ->
                        actions.showMessage(e?.toUiError()?.text(context) ?: context.getString(R.string.maintenance_restored))
                    }
                }) { Text(stringResource(R.string.maintenance_restore)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Summary(status: MaintenanceStatus, starting: Boolean, onRun: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.maintenance_hint), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            val last = status.lastRun
            Text(
                if (last == null) stringResource(R.string.maintenance_never) else runLine(last),
                style = MaterialTheme.typography.titleSmall,
            )
            last?.let { run ->
                statsLine(run)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                run.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            status.nextRunAt?.let {
                Text(
                    stringResource(R.string.maintenance_next, formatTime(it, "dd.MM HH:mm"), status.quarantineDays),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status.dryRun) {
                Text(
                    stringResource(R.string.maintenance_dry_run),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(12.dp))
            val running = status.running || starting
            Button(onClick = onRun, enabled = !running, modifier = Modifier.testTag("maintenance-run")) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.maintenance_running))
                } else {
                    Text(stringResource(R.string.maintenance_run))
                }
            }
        }
    }
}

@Composable
private fun runLine(run: MaintenanceRun): String {
    val time = formatTime(run.finishedAt ?: run.startedAt, "dd.MM HH:mm")
    val result = stringResource(
        when (run.status) {
            "running" -> R.string.maintenance_status_running
            "failed" -> R.string.maintenance_status_failed
            else -> R.string.maintenance_status_completed
        },
    )
    return stringResource(R.string.maintenance_last, time, result)
}

@Composable
private fun statsLine(run: MaintenanceRun): String? {
    val parts = listOf(
        "duplicates_removed" to R.string.maintenance_stat_duplicates,
        "albums_merged" to R.string.maintenance_stat_albums,
        "short_removed" to R.string.maintenance_stat_short,
        "tags_fixed" to R.string.maintenance_stat_tags,
        "covers_added" to R.string.maintenance_stat_covers,
    ).mapNotNull { (key, label) -> run.stats[key]?.takeIf { it > 0 }?.let { stringResource(label, it) } }
    val found = listOf("short_found", "tags_problems", "covers_missing").sumOf { run.stats[it] ?: 0 }
    val all = parts + listOfNotNull(found.takeIf { it > 0 }?.let { stringResource(R.string.maintenance_stat_found, it) })
    val songs = run.stats["songs"]
    return when {
        all.isNotEmpty() -> all.joinToString(" · ")
        songs != null -> stringResource(R.string.maintenance_stat_clean, songs)
        else -> null
    }
}

@Composable
private fun ActionRow(action: MaintenanceAction, onRestore: () -> Unit) {
    ListItem(
        leadingContent = {
            Icon(
                kindIcon(action.kind),
                contentDescription = kindLabel(action.kind),
                tint = if (action.applied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            )
        },
        headlineContent = { Text(action.title ?: action.album ?: action.path.orEmpty(), maxLines = 2) },
        supportingContent = {
            Column {
                val who = listOfNotNull(action.artist, action.album?.takeIf { action.title != null }).joinToString(" · ")
                if (who.isNotBlank()) Text(who, maxLines = 1)
                Text(action.details, style = MaterialTheme.typography.bodySmall)
                Text(
                    listOfNotNull(formatTime(action.createdAt, "dd.MM HH:mm"), action.path).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        },
        trailingContent = if (action.restorable) {
            {
                TextButton(onClick = onRestore, modifier = Modifier.testTag("restore-${action.id}")) {
                    Text(stringResource(R.string.maintenance_restore))
                }
            }
        } else {
            null
        },
        modifier = Modifier.testTag("maintenance-action-${action.id}"),
    )
}

@Composable
private fun kindLabel(kind: String?): String = stringResource(
    when (kind) {
        null -> R.string.maintenance_kind_all
        "duplicate" -> R.string.maintenance_kind_duplicate
        "album" -> R.string.maintenance_kind_album
        "short" -> R.string.maintenance_kind_short
        "tags" -> R.string.maintenance_kind_tags
        "cover" -> R.string.maintenance_kind_cover
        else -> R.string.maintenance_kind_all
    },
)

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "duplicate" -> Icons.Rounded.ContentCopy
    "album" -> Icons.Rounded.Album
    "short" -> Icons.Rounded.Timer
    "tags" -> Icons.AutoMirrored.Rounded.Label
    "cover" -> Icons.Rounded.Image
    else -> Icons.Rounded.AutoFixHigh
}
