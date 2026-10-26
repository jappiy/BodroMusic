package ru.bodro.music.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import ru.bodro.music.core.downloads.LikedTrack
import ru.bodro.music.core.downloads.LikesImportStatus
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.CoverImage
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.util.UiError
import ru.bodro.music.ui.util.formatTime
import ru.bodro.music.ui.util.text
import ru.bodro.music.ui.util.toUiError

data class LikesImportUi(
    val loading: Boolean = true,
    val status: LikesImportStatus? = null,
    val error: UiError? = null,
    val busy: Boolean = false,
)

/**
 * Перенос лайков из Яндекс Музыки (bodrod 0.26.0+). Вся тяжёлая работа — на сервере: лайки берутся токеном
 * Яндекса этого пользователя и ставятся от его учётки Navidrome. Экран запускает перенос, опрашивает прогресс,
 * показывает отчёт, конфликты с «Не нравится» и ненайденные с загрузкой.
 */
@HiltViewModel
class LikesImportViewModel @Inject constructor(private val downloads: DownloadsRepository) : ViewModel() {
    private val _state = MutableStateFlow(LikesImportUi())
    val state: StateFlow<LikesImportUi> = _state.asStateFlow()
    private var pollJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.status == null, error = null) }
            runCatching { downloads.likesImport() }
                .onSuccess(::show)
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toUiError()) } }
        }
    }

    private fun show(status: LikesImportStatus) {
        _state.update { it.copy(loading = false, status = status, error = null) }
        if (status.needsPolling()) poll()
    }

    private fun LikesImportStatus.needsPolling(): Boolean =
        running || notFound.any { it.download == "queued" || it.download == "downloading" || it.download == "done" }

    private fun poll() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            var active = true
            while (active) {
                delay(if (_state.value.status?.running == true) RUN_POLL_MS else DOWNLOAD_POLL_MS)
                runCatching { downloads.likesImport() }.getOrNull()?.let { status ->
                    _state.update { it.copy(status = status) }
                    active = status.needsPolling()
                }
            }
        }
    }

    private fun act(onError: (Throwable) -> Unit, call: suspend () -> LikesImportStatus) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { call() }.onSuccess(::show).onFailure(onError)
            _state.update { it.copy(busy = false) }
        }
    }

    fun run(onError: (Throwable) -> Unit) = act(onError) { downloads.runLikesImport() }

    fun downloadAll(onError: (Throwable) -> Unit) = act(onError) { downloads.downloadLikesMissing() }

    fun download(track: LikedTrack, onError: (Throwable) -> Unit) = act(onError) { downloads.downloadLikesMissing(listOf(track.id)) }

    private companion object {
        const val RUN_POLL_MS = 1_500L
        const val DOWNLOAD_POLL_MS = 5_000L
    }
}

@Composable
fun LikesImportScreen(onBack: () -> Unit, viewModel: LikesImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    val onError: (Throwable) -> Unit = { e -> actions.showMessage(e.toUiError().text(context)) }
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.likes_import_title), onBack) }) { padding ->
        val status = state.status
        val error = state.error
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            error != null && status == null -> ErrorState(error, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            status != null -> LikesImportContent(
                status,
                busy = state.busy,
                onRun = { viewModel.run(onError) },
                onDownloadAll = { viewModel.downloadAll(onError) },
                onDownload = { viewModel.download(it, onError) },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun LikesImportContent(
    status: LikesImportStatus,
    busy: Boolean,
    onRun: () -> Unit,
    onDownloadAll: () -> Unit,
    onDownload: (LikedTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    val downloadable = status.notFound.count { it.download in DOWNLOADABLE }
    LazyColumn(modifier.fillMaxSize().testTag("likes-import-screen")) {
        item(key = "hint") {
            Text(
                stringResource(R.string.likes_import_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "run") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRun,
                    enabled = !busy && !status.running,
                    modifier = Modifier.fillMaxWidth().testTag("likes-import-run"),
                ) {
                    Text(
                        stringResource(
                            if (status.lastRunAt == null) R.string.likes_import_run else R.string.likes_import_run_again,
                        ),
                    )
                }
                if (status.running) {
                    status.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (status.total > 0) {
                        LinearProgressIndicator(
                            progress = { status.done.toFloat() / status.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.likes_import_progress, status.done, status.total),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                } else if (status.state == "failed") {
                    Text(
                        status.message ?: stringResource(R.string.likes_import_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                status.lastRunAt?.let {
                    Text(
                        stringResource(R.string.likes_import_last_run, formatTime(it, "dd.MM.yyyy HH:mm")),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        status.report?.let { report ->
            item(key = "report") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("likes-import-report")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ReportLine(R.string.likes_import_total, report.total)
                        ReportLine(R.string.likes_import_already, report.already)
                        ReportLine(R.string.likes_import_starred, report.starred)
                        ReportLine(R.string.likes_import_conflicts, report.conflicts)
                        ReportLine(R.string.likes_import_not_found, report.notFound)
                    }
                }
            }
        }
        if (status.notFound.isNotEmpty()) {
            item(key = "missing-header") {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.likes_import_missing_title, status.notFound.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = onDownloadAll,
                        enabled = !busy && downloadable > 0,
                        modifier = Modifier.testTag("likes-import-download-all"),
                    ) { Text(stringResource(R.string.likes_import_download_all, downloadable)) }
                }
                Text(
                    stringResource(R.string.likes_import_missing_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(status.notFound, key = { "nf-${it.id}" }) { track ->
                LikedRow(track, busy, onDownload)
            }
        }
        if (status.conflicts.isNotEmpty()) {
            item(key = "conflicts-header") {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.likes_import_conflicts_title, status.conflicts.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    stringResource(R.string.likes_import_conflicts_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(status.conflicts, key = { "c-${it.id}" }) { track -> LikedRow(track, busy = true, onDownload = {}) }
        }
    }
}

private val DOWNLOADABLE = setOf("none", "failed", "notFound")

@Composable
private fun ReportLine(label: Int, value: Int) {
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("$value", style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun LikedRow(track: LikedTrack, busy: Boolean, onDownload: (LikedTrack) -> Unit) {
    ListItem(
        leadingContent = {
            CoverImage(emptyList(), Modifier.size(44.dp), sizeHint = 44.dp, shape = RoundedCornerShape(6.dp), remoteUrl = track.coverUrl)
        },
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (track.songId != null) {
            null
        } else {
            { DownloadState(track, busy, onDownload) }
        },
        modifier = Modifier.testTag("liked-${track.id}"),
    )
}

@Composable
private fun DownloadState(track: LikedTrack, busy: Boolean, onDownload: (LikedTrack) -> Unit) {
    when (track.download) {
        "queued" -> StateText(R.string.download_state_queued)
        "downloading" -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        "done" -> StateText(R.string.download_state_done)
        "notFound" -> RetryButton(R.string.download_state_not_found, busy) { onDownload(track) }
        "failed" -> RetryButton(R.string.download_state_failed, busy) { onDownload(track) }
        else -> TextButton(onClick = { onDownload(track) }, enabled = !busy) { Text(stringResource(R.string.download_action)) }
    }
}

@Composable
private fun StateText(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RetryButton(label: Int, busy: Boolean, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.End) {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.download_retry)) }
    }
}
