package ru.bodro.music.ui.screens.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import ru.bodro.music.core.downloads.CoverStats
import ru.bodro.music.core.downloads.EncodingFix
import ru.bodro.music.core.downloads.MaintenanceCount
import ru.bodro.music.core.downloads.MaintenanceRun
import ru.bodro.music.core.downloads.MaintenanceStatus
import ru.bodro.music.core.downloads.MaintenanceSummary
import ru.bodro.music.core.downloads.QuarantineItem
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.util.UiError
import ru.bodro.music.ui.util.formatTime
import ru.bodro.music.ui.util.text
import ru.bodro.music.ui.util.toUiError

data class MaintenanceUi(
    val loading: Boolean = true,
    val status: MaintenanceStatus? = null,
    val summary: MaintenanceSummary = MaintenanceSummary(),
    /** Аудит обложек; `null` — bodrod старше 0.25.0 или нет базы Navidrome. */
    val covers: CoverStats? = null,
    val starting: Boolean = false,
    val error: UiError? = null,
)

/**
 * «Обслуживание библиотеки» (только администраторы): сводка последнего запуска регламента bodrod — сколько
 * исправлено (по типам) и сколько не исправлено (по причинам); списком — только карантин с возвратом; запуск вручную.
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

    fun runNow(onError: (Throwable) -> Unit) {
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            runCatching { downloads.runMaintenance() }.onFailure(onError)
            runCatching { reload() }
            _state.update { it.copy(starting = false) }
        }
    }

    fun revertEncoding(fix: EncodingFix, onDone: (Throwable?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { downloads.revertEncodingFix(fix.id) }
            onDone(result.exceptionOrNull())
            runCatching { reload() }
        }
    }

    fun restore(item: QuarantineItem, onDone: (Throwable?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { downloads.restoreFromQuarantine(item.id) }
            onDone(result.exceptionOrNull())
            runCatching { reload() }
        }
    }

    private suspend fun reload() {
        val status = downloads.maintenanceStatus()
        val summary = downloads.maintenanceSummary()
        val covers = runCatching {
            downloads.coverStats()
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
        _state.update { it.copy(loading = false, status = status, summary = summary, covers = covers, error = null) }
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
        const val POLL_MS = 3_000L
    }
}

@Composable
fun MaintenanceScreen(onBack: () -> Unit, viewModel: MaintenanceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    var pending by remember { mutableStateOf<QuarantineItem?>(null) }

    Scaffold(topBar = { BodroTopBar(stringResource(R.string.maintenance_title), onBack) }) { padding ->
        val error = state.error
        val status = state.status
        val summary = state.summary
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            error != null && status == null -> ErrorState(error, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            status != null -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("maintenance-screen")) {
                item(key = "summary") {
                    Summary(status, starting = state.starting) {
                        viewModel.runNow { e -> actions.showMessage(e.toUiError().text(context)) }
                    }
                }
                summary.run?.let { run ->
                    item(key = "fixed") {
                        Counts(
                            stringResource(if (run.dryRun) R.string.maintenance_fixed_planned else R.string.maintenance_fixed),
                            summary.fixed,
                            stringResource(R.string.maintenance_fixed_none),
                            tag = "fixed",
                        )
                    }
                    item(key = "unresolved") {
                        Counts(
                            stringResource(R.string.maintenance_unresolved),
                            summary.unresolved,
                            stringResource(R.string.maintenance_unresolved_none),
                            tag = "unresolved",
                        )
                    }
                }
                // Кодировка тегов: «было → стало» с откатом и список на ручную проверку.
                if (summary.encoding.isNotEmpty()) {
                    item(key = "encoding-header") { SectionTitle(stringResource(R.string.maintenance_encoding, summary.encoding.size)) }
                    items(summary.encoding, key = { "enc-${it.id}" }) { fix ->
                        EncodingRow(fix) {
                            viewModel.revertEncoding(fix) { e ->
                                actions.showMessage(
                                    e?.toUiError()?.text(context) ?: context.getString(R.string.maintenance_encoding_revert_done),
                                )
                            }
                        }
                    }
                }
                if (summary.encodingManual.isNotEmpty()) {
                    item(key = "encoding-manual-header") {
                        SectionTitle(stringResource(R.string.maintenance_encoding_manual, summary.encodingManual.size))
                    }
                    items(summary.encodingManual, key = { "enc-manual-$it" }) { path -> Hint(path) }
                }
                state.covers?.let { covers ->
                    item(key = "covers") {
                        Column {
                            SectionTitle(stringResource(R.string.maintenance_covers_title))
                            Hint(
                                stringResource(
                                    R.string.maintenance_covers_summary,
                                    covers.albumsWithoutCover,
                                    covers.albums,
                                    covers.songsWithoutCover,
                                    covers.songs,
                                    covers.artistsWithoutImage,
                                    covers.artists,
                                ),
                            )
                            covers.byFolder.entries.firstOrNull()?.let { (folder, count) ->
                                Hint(stringResource(R.string.maintenance_covers_folders, folder, count))
                            }
                        }
                    }
                }
                item(key = "quarantine-header") {
                    Column {
                        SectionTitle(stringResource(R.string.maintenance_quarantine, summary.quarantine.size, status.quarantineDays))
                        if (summary.quarantine.isEmpty()) Hint(stringResource(R.string.maintenance_quarantine_empty))
                    }
                }
                items(summary.quarantine, key = { it.id }) { item ->
                    QuarantineRow(item, onRestore = { pending = item })
                }
            }
        }
    }

    pending?.let { item ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.maintenance_restore_title)) },
            text = { Text(stringResource(R.string.maintenance_restore_text, item.path)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    viewModel.restore(item) { e ->
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
                run.stats["songs"]?.let {
                    Text(
                        stringResource(R.string.maintenance_songs, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Блок сводки: заголовок и строки «подпись — число» (подписи присылает сервер); пусто — [empty]. */
@Composable
private fun Counts(title: String, counts: List<MaintenanceCount>, empty: String, tag: String) {
    Column(Modifier.testTag("maintenance-$tag")) {
        SectionTitle(title)
        if (counts.isEmpty()) Hint(empty)
        counts.forEach { count ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(count.title.ifBlank { count.key }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(12.dp))
                Text(count.count.toString(), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** Файл с исправленной кодировкой: путь, «было → стало» по полям, «Отменить». */
@Composable
private fun EncodingRow(fix: EncodingFix, onRevert: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).testTag("encoding-${fix.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(fix.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            fix.changes.forEach { change ->
                Text("${change.field}: ${change.before} → ${change.after}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (fix.reverted) {
            Text(
                stringResource(R.string.maintenance_encoding_reverted),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        } else {
            TextButton(onClick = onRevert) { Text(stringResource(R.string.maintenance_encoding_revert)) }
        }
    }
}

@Composable
private fun QuarantineRow(item: QuarantineItem, onRestore: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.title ?: item.album ?: item.path, maxLines = 2) },
        supportingContent = {
            Column {
                val who = listOfNotNull(item.artist, item.album?.takeIf { item.title != null }).joinToString(" · ")
                if (who.isNotBlank()) Text(who, maxLines = 1)
                Text(item.reason, style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.maintenance_quarantine_until, formatTime(item.expiresAt, "dd.MM"), item.path),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        },
        trailingContent = {
            TextButton(onClick = onRestore, modifier = Modifier.testTag("restore-${item.id}")) {
                Text(stringResource(R.string.maintenance_restore))
            }
        },
        modifier = Modifier.testTag("quarantine-${item.id}"),
    )
}
