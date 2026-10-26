package ru.bodro.music.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.downloads.DownloadSource
import ru.bodro.music.core.downloads.DownloadSources
import ru.bodro.music.core.downloads.ExportArtist
import ru.bodro.music.core.downloads.ExportStatus
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.EmptyState
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.util.UiError
import ru.bodro.music.ui.util.text
import ru.bodro.music.ui.util.toUiError

data class SourcesUi(
    val loading: Boolean = true,
    val sources: DownloadSources? = null,
    val export: ExportStatus? = null,
    /** Почему выгрузка недоступна (например, не задан LIBRARY_DIR). */
    val exportError: UiError? = null,
    val busy: Boolean = false,
    val error: UiError? = null,
)

/**
 * «Источники загрузки» (только администраторы): цепочка bodrod — порядок, переключатели и статусы источников,
 * «Яндекс: метаданные», проверка подписки, обновление yt-dlp и массовая выгрузка из Яндекса.
 */
@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val downloads: DownloadsRepository,
    private val playlists: ru.bodro.music.data.repository.PlaylistRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SourcesUi())
    val state: StateFlow<SourcesUi> = _state.asStateFlow()
    private var pollJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.sources == null, error = null) }
            runCatching { downloads.sources() }
                .onSuccess { sources -> _state.update { it.copy(loading = false, sources = sources) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toUiError()) } }
            loadExport()
        }
    }

    private suspend fun loadExport() {
        runCatching { downloads.exportStatus() }
            .onSuccess { export ->
                _state.update { it.copy(export = export, exportError = null) }
                if (export.isActive) poll()
            }
            .onFailure { e -> _state.update { it.copy(exportError = e.toUiError()) } }
    }

    /**
     * Плейлисты выгрузки Яндекса — в аккаунт текущего пользователя, приватными. Уже существующие (по названию)
     * не трогаются; плейлисты без скачанных треков пропускаются.
     */
    fun importYandexPlaylists(onDone: (created: Int, skipped: Int) -> Unit, onError: (Throwable) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val mine = playlists.myPlaylists().map { it.name.trim().lowercase() }.toSet()
                var created = 0
                var skipped = 0
                for (item in downloads.exportPlaylists()) {
                    if (item.songIds.isEmpty() || item.title.trim().lowercase() in mine) {
                        skipped++
                        continue
                    }
                    playlists.createWithIds(item.title, item.songIds)
                    created++
                }
                created to skipped
            }.onSuccess { (created, skipped) -> onDone(created, skipped) }.onFailure(onError)
        }
    }

    private fun poll() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            var active = true
            while (active) {
                delay(POLL_MS)
                // Сетевой сбой — пробуем снова; остановка только когда выгрузка закончилась или на паузе.
                runCatching { downloads.exportStatus() }.getOrNull()?.let { export ->
                    _state.update { it.copy(export = export) }
                    active = export.isActive
                }
            }
        }
    }

    /** Изменение источников: показываем ответ сервера (в нём уже новые статусы). */
    private fun change(onError: (Throwable) -> Unit, onDone: () -> Unit = {}, call: suspend () -> DownloadSources) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { call() }
                .onSuccess { sources ->
                    _state.update { it.copy(sources = sources) }
                    onDone()
                }
                .onFailure(onError)
            _state.update { it.copy(busy = false) }
        }
    }

    fun setEnabled(name: String, enabled: Boolean, onError: (Throwable) -> Unit) =
        change(onError) { downloads.updateSources(enabled = mapOf(name to enabled)) }

    fun setOrder(order: List<String>, onError: (Throwable) -> Unit) = change(onError) { downloads.updateSources(order = order) }

    fun setYandexMetadata(enabled: Boolean, onError: (Throwable) -> Unit) =
        change(onError) { downloads.updateSources(yandexMetadata = enabled) }

    fun checkYandex(onError: (Throwable) -> Unit) = change(onError) { downloads.checkYandex() }

    fun updateYtDlp(onError: (Throwable) -> Unit, onDone: () -> Unit) = change(onError, onDone) { downloads.updateYtDlp() }

    fun startExport(likes: Boolean, playlists: Boolean, artists: Boolean, onError: (Throwable) -> Unit) {
        viewModelScope.launch {
            runCatching { downloads.startExport(likes, playlists, artists) }
                .onSuccess { export ->
                    _state.update { it.copy(export = export) }
                    poll()
                }
                .onFailure(onError)
        }
    }

    fun pauseExport(onError: (Throwable) -> Unit) {
        viewModelScope.launch {
            runCatching { downloads.pauseExport() }
                .onSuccess { export -> _state.update { it.copy(export = export) } }
                .onFailure(onError)
        }
    }

    private companion object {
        const val POLL_MS = 5_000L
    }
}

@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    onOpenArtists: () -> Unit,
    onOpenLikesImport: () -> Unit = {},
    viewModel: SourcesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    val onError: (Throwable) -> Unit = { e -> actions.showMessage(e.toUiError().text(context)) }

    Scaffold(topBar = { BodroTopBar(stringResource(R.string.sources_title), onBack) }) { padding ->
        val error = state.error
        val sources = state.sources
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            // Цепочка источников — только администраторам; перенос лайков — каждому пользователю для себя.
            error != null && sources == null -> Column(Modifier.fillMaxSize().padding(padding)) {
                LikesImportEntry(onOpenLikesImport)
                HorizontalDivider()
                ErrorState(error, onRetry = viewModel::load, modifier = Modifier.weight(1f))
            }
            sources != null -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("sources-screen")) {
                item(key = "likes") {
                    LikesImportEntry(onOpenLikesImport)
                    HorizontalDivider()
                }
                item(key = "hint") {
                    Text(
                        stringResource(R.string.sources_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item(key = "chain") {
                    SourceChain(
                        sources.sources,
                        enabled = !state.busy,
                        onToggle = { name, on -> viewModel.setEnabled(name, on, onError) },
                        onReorder = { order -> viewModel.setOrder(order, onError) },
                    )
                }
                item(key = "yandex") {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Header(stringResource(R.string.sources_yandex))
                    SwitchRow(
                        title = stringResource(R.string.sources_yandex_metadata),
                        summary = listOfNotNull(
                            stringResource(R.string.sources_yandex_metadata_summary),
                            sources.yandexMetadataReason,
                        ).joinToString(" "),
                        checked = sources.yandexMetadata,
                        onChange = { viewModel.setYandexMetadata(it, onError) },
                        enabled = !state.busy,
                    )
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.checkYandex(onError) }, enabled = !state.busy) {
                            Text(stringResource(R.string.sources_yandex_check))
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.updateYtDlp(onError) { actions.showMessage(context.getString(R.string.sources_ytdlp_updated)) }
                            },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("sources-update-ytdlp"),
                        ) { Text(stringResource(R.string.sources_youtube_update)) }
                    }
                }
                item(key = "export") {
                    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 8.dp))
                    Header(stringResource(R.string.export_title))
                    ExportCard(
                        state.export,
                        state.exportError,
                        onStart = { likes, playlists, artists -> viewModel.startExport(likes, playlists, artists, onError) },
                        onPause = { viewModel.pauseExport(onError) },
                        onOpenArtists = onOpenArtists,
                        onImportPlaylists = {
                            viewModel.importYandexPlaylists(onDone = { created, skipped ->
                                actions.showMessage(context.getString(R.string.export_playlists_imported, created, skipped))
                            }, onError = onError)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LikesImportEntry(onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.likes_import_entry)) },
        supportingContent = { Text(stringResource(R.string.likes_import_entry_summary)) },
        leadingContent = { Icon(Icons.Rounded.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick).testTag("sources-likes-import"),
    )
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

private val SOURCE_ROW_HEIGHT = 92.dp

/** Источники по порядку; порядок меняется перетаскиванием за ручку и сохраняется, когда палец отпущен. */
@Composable
private fun SourceChain(
    sources: List<DownloadSource>,
    enabled: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    val byName = sources.associateBy { it.name }
    var order by remember(sources) { mutableStateOf(sources.map { it.name }) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { SOURCE_ROW_HEIGHT.toPx() }
    Column(Modifier.testTag("sources-chain")) {
        order.forEachIndexed { index, name ->
            val source = byName[name] ?: return@forEachIndexed
            key(name) {
                val isDragged = dragging == name
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) offset else 0f }
                        .let { if (isDragged) it.shadow(8.dp) else it }
                        .height(SOURCE_ROW_HEIGHT)
                        .testTag("source-${source.name}"),
                ) {
                    SourceRow(
                        position = index + 1,
                        source = source,
                        enabled = enabled,
                        onToggle = { onToggle(name, it) },
                        handle = Modifier.pointerInput(name, enabled) {
                            if (!enabled) return@pointerInput
                            detectDragGestures(
                                onDragStart = {
                                    dragging = name
                                    offset = 0f
                                },
                                onDragEnd = {
                                    dragging = null
                                    offset = 0f
                                    if (order != sources.map { it.name }) onReorder(order)
                                },
                                onDragCancel = {
                                    dragging = null
                                    offset = 0f
                                },
                            ) { change, amount ->
                                change.consume()
                                offset += amount.y
                                val current = order.indexOf(name)
                                val target = (current + (offset / rowPx).roundToInt()).coerceIn(0, order.lastIndex)
                                if (target != current) {
                                    order = order.toMutableList().apply { add(target, removeAt(current)) }
                                    offset -= (target - current) * rowPx
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceRow(position: Int, source: DownloadSource, enabled: Boolean, onToggle: (Boolean) -> Unit, handle: Modifier) {
    val stateText = when (source.state) {
        "ok" -> stringResource(R.string.sources_state_ok)
        "disabled" -> stringResource(R.string.sources_state_disabled)
        else -> stringResource(R.string.sources_state_unavailable)
    }
    val kinds = source.kinds.mapNotNull { kind ->
        when (kind) {
            "track" -> R.string.sources_kind_track
            "album" -> R.string.sources_kind_album
            "artist" -> R.string.sources_kind_artist
            else -> null
        }?.let { stringResource(it) }
    }.joinToString(", ")
    ListItem(
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.DragHandle,
                    contentDescription = stringResource(R.string.sources_drag),
                    modifier = handle.size(32.dp).padding(4.dp).testTag("source-handle-${source.name}"),
                )
                Text("$position", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
            }
        },
        headlineContent = {
            Column {
                Text(source.title, maxLines = 1)
                // Яндекс: чей токен используется для вас — ваш или общий (bodrod 0.13.0+).
                val tokenSource = when (source.tokenSource) {
                    "user" -> stringResource(R.string.sources_token_user)
                    "global" -> stringResource(R.string.sources_token_global)
                    else -> source.tokenSourceTitle
                }
                tokenSource?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.testTag("source-token-${source.name}"),
                    )
                }
            }
        },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(stateText, source.reason?.takeIf { source.state != "ok" }).joinToString(": "),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (source.state) {
                        "ok" -> MaterialTheme.colorScheme.primary
                        "disabled" -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(kinds.ifBlank { null }, source.details).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            Switch(
                checked = source.enabled,
                onCheckedChange = onToggle,
                enabled = enabled,
                modifier = Modifier.testTag("source-switch-${source.name}"),
            )
        },
    )
}

@Composable
private fun ExportCard(
    export: ExportStatus?,
    error: UiError?,
    onStart: (likes: Boolean, playlists: Boolean, artists: Boolean) -> Unit,
    onPause: () -> Unit,
    onOpenArtists: () -> Unit,
    onImportPlaylists: () -> Unit,
) {
    val context = LocalContext.current
    var likes by rememberSaveable(export?.likes) { mutableStateOf(export?.likes ?: true) }
    var playlists by rememberSaveable(export?.playlists) { mutableStateOf(export?.playlists ?: true) }
    var artists by rememberSaveable { mutableStateOf(true) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("export-card"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.export_hint), style = MaterialTheme.typography.bodyMedium)
            if (export == null) {
                error?.let {
                    Text(
                        stringResource(R.string.export_unavailable, it.text(context)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                return@Column
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    when (export.state) {
                        "collecting" -> R.string.export_state_collecting
                        "running" -> R.string.export_state_running
                        "paused" -> R.string.export_state_paused
                        "finished" -> R.string.export_state_finished
                        else -> R.string.export_state_idle
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            val counts = export.counts
            if (counts.total > 0) {
                val processed = counts.done + counts.skipped + counts.failed
                LinearProgressIndicator(
                    progress = { processed.toFloat() / counts.total },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Text(
                    stringResource(R.string.export_counts, counts.done, counts.skipped, counts.failed, counts.pending, counts.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            listOfNotNull(export.current?.takeIf { export.isActive }, export.message).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            CheckRow(stringResource(R.string.export_likes), likes, enabled = !export.isActive) { likes = it }
            CheckRow(stringResource(R.string.export_playlists), playlists, enabled = !export.isActive) { playlists = it }
            CheckRow(
                stringResource(R.string.export_artists, export.artistsSelected, export.artistsTotal),
                artists,
                enabled = !export.isActive,
            ) { artists = it }
            TextButton(onClick = onOpenArtists) { Text(stringResource(R.string.export_artists_choose)) }
            if (!export.isActive) {
                TextButton(onClick = onImportPlaylists, modifier = Modifier.testTag("export-import-playlists")) {
                    Text(stringResource(R.string.export_playlists_import))
                }
                Text(
                    stringResource(R.string.export_playlists_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (export.isActive) {
                OutlinedButton(onClick = onPause, modifier = Modifier.testTag("export-pause")) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.export_pause))
                }
            } else {
                Button(onClick = { onStart(likes, playlists, artists) }, modifier = Modifier.testTag("export-start")) {
                    Text(stringResource(if (counts.total > 0) R.string.export_continue else R.string.export_start))
                }
            }
        }
    }
}

@Composable
private fun CheckRow(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = checked, enabled = enabled, role = Role.Checkbox) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

// region Артисты для выгрузки

data class ExportArtistsUi(
    val loading: Boolean = true,
    val artists: List<ExportArtist> = emptyList(),
    val refreshing: Boolean = false,
    val error: UiError? = null,
)

@HiltViewModel
class ExportArtistsViewModel @Inject constructor(private val downloads: DownloadsRepository) : ViewModel() {
    private val _state = MutableStateFlow(ExportArtistsUi())
    val state: StateFlow<ExportArtistsUi> = _state.asStateFlow()
    private var saveJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.artists.isEmpty(), error = null) }
            runCatching { downloads.exportArtists() }
                .onSuccess { list -> _state.update { it.copy(loading = false, artists = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toUiError()) } }
        }
    }

    fun refresh(onError: (Throwable) -> Unit) {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            runCatching { downloads.refreshExportArtists() }
                .onSuccess { list -> _state.update { it.copy(artists = list) } }
                .onFailure(onError)
            _state.update { it.copy(refreshing = false, loading = false) }
        }
    }

    /** Отметка меняется сразу, на сервер уходит весь список отмеченных (с небольшой задержкой — пачкой). */
    fun toggle(artist: ExportArtist, onError: (Throwable) -> Unit) {
        _state.update { ui -> ui.copy(artists = ui.artists.map { if (it.id == artist.id) it.copy(selected = !it.selected) else it }) }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            runCatching { downloads.selectExportArtists(_state.value.artists.filter { it.selected }.map { it.id }) }
                .onFailure(onError)
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 600L
    }
}

@Composable
fun ExportArtistsScreen(onBack: () -> Unit, viewModel: ExportArtistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    val onError: (Throwable) -> Unit = { e -> actions.showMessage(e.toUiError().text(context)) }
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(topBar = { BodroTopBar(stringResource(R.string.export_artists_title), onBack) }) { padding ->
        val error = state.error
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            error != null && state.artists.isEmpty() -> ErrorState(error, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("export-artists-screen")) {
                item(key = "head") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.export_artists_hint), style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { viewModel.refresh(onError) }, enabled = !state.refreshing) {
                                if (state.refreshing) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text(stringResource(R.string.export_artists_refresh))
                            }
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            placeholder = { Text(stringResource(R.string.export_artists_search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                val visible = state.artists.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                if (state.artists.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(stringResource(R.string.export_artists_empty), modifier = Modifier.height(240.dp))
                    }
                }
                items(visible, key = { it.id }) { artist ->
                    ListItem(
                        headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(stringResource(R.string.export_artist_liked, artist.liked)) },
                        trailingContent = { Checkbox(checked = artist.selected, onCheckedChange = null) },
                        modifier = Modifier
                            .selectable(selected = artist.selected, role = Role.Checkbox) { viewModel.toggle(artist, onError) }
                            .testTag("export-artist-${artist.id}"),
                    )
                }
            }
        }
    }
}

// endregion
