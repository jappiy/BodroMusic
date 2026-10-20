package ru.bodro.music.ui.screens.charts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.FiberNew
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
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
import ru.bodro.music.core.downloads.Chart
import ru.bodro.music.core.downloads.ChartEntry
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Song
import ru.bodro.music.data.reco.ChartsRepository
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.ui.app.ChartRoute
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.EmptyState
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.screens.search.RemoteImage
import ru.bodro.music.ui.util.UiError
import ru.bodro.music.ui.util.formatTime
import ru.bodro.music.ui.util.toUiError

/** Карточка чарта на главной: коллаж из четырёх обложек, название и дата обновления. */
@Composable
fun ChartCard(chart: Chart, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(148.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).testTag("chart-${chart.id}")) {
        Column(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))) {
            for (row in 0 until 2) {
                Row(Modifier.weight(1f)) {
                    for (col in 0 until 2) {
                        RemoteImage(
                            chart.covers.getOrNull(row * 2 + col),
                            Modifier.weight(1f).fillMaxSize(),
                            RoundedCornerShape(0.dp),
                            Icons.Rounded.MusicNote,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(chart.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        chart.updatedAt?.let {
            Text(
                stringResource(R.string.chart_updated, formatTime(it, "dd.MM")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

data class ChartUi(
    val loading: Boolean = true,
    val entries: List<ChartEntry> = emptyList(),
    val songs: Map<String, Song> = emptyMap(),
    val error: UiError? = null,
) {
    /** Доступные треки по порядку чарта. */
    val playable: List<Song> get() = entries.mapNotNull { e -> e.songId?.let { songs[it] } }
}

@HiltViewModel
class ChartViewModel @Inject constructor(savedState: SavedStateHandle, private val charts: ChartsRepository) : ViewModel() {
    private val route = savedState.toRoute<ChartRoute>()
    val title: String = route.title
    private val _state = MutableStateFlow(ChartUi())
    val state: StateFlow<ChartUi> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var pollJob: Job? = null

    init {
        load()
    }

    /**
     * Позиции с доступностью, посчитанной сервером сейчас (без кэша): при открытии, при возврате на экран и,
     * пока что-то качается, раз в [POLL_MS].
     */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = it.entries.isEmpty(), error = null) }
            runCatching { charts.entries(route.id) }
                .onSuccess { entries ->
                    _state.update { it.copy(loading = false, entries = entries) }
                    val known = _state.value.songs
                    val fresh = charts.songs(entries.filter { e -> e.songId != null && e.songId !in known })
                    _state.update { it.copy(songs = known + fresh) }
                    schedulePoll(entries)
                }
                .onFailure { e ->
                    // Фоновое обновление не прячет уже показанный чарт за ошибкой.
                    _state.update {
                        if (it.entries.isEmpty()) it.copy(loading = false, error = e.toUiError()) else it.copy(loading = false)
                    }
                }
        }
    }

    fun onResume() {
        if (_state.value.entries.isNotEmpty()) load()
    }

    fun onPause() {
        pollJob?.cancel()
    }

    private fun schedulePoll(entries: List<ChartEntry>) {
        pollJob?.cancel()
        if (entries.none { it.isPending || it.isReady }) return
        pollJob = viewModelScope.launch {
            delay(POLL_MS)
            load()
        }
    }

    fun download(entry: ChartEntry) {
        viewModelScope.launch {
            runCatching { charts.download(route.id, entry) }.onSuccess { request ->
                _state.update { s ->
                    s.copy(
                        entries = s.entries.map {
                            if (it.position == entry.position) {
                                it.copy(state = "queued", requestId = request.id, requestStatus = "queued")
                            } else {
                                it
                            }
                        },
                    )
                }
                schedulePoll(_state.value.entries)
            }
        }
    }

    private companion object {
        const val POLL_MS = 10_000L
    }
}

/** Экран чарта: позиции со стрелками движения, состояние каждого трека, «Слушать» — доступные по порядку. */
@Composable
fun ChartScreen(onBack: () -> Unit, viewModel: ChartViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val source = QueueSource.CustomSource(viewModel.title)
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }
    Scaffold(topBar = { BodroTopBar(viewModel.title, onBack) }) { padding ->
        val error = state.error
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            error != null -> ErrorState(error, viewModel::load, Modifier.padding(padding))
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag("chart-screen"),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Row(
                        Modifier.padding(16.dp, 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val playable = state.playable
                        Button(onClick = {
                            actions.play(playable, 0, source)
                        }, enabled = playable.isNotEmpty(), modifier = Modifier.testTag("chart-play")) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_play))
                        }
                        Text(
                            stringResource(R.string.chart_available, playable.size, state.entries.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                itemsIndexed(state.entries, key = { _, e -> e.position }) { _, entry ->
                    ChartRow(
                        entry = entry,
                        onPlay = {
                            val playable = state.playable
                            val song = entry.songId?.let { state.songs[it] }
                            if (song != null) actions.play(playable, playable.indexOf(song).coerceAtLeast(0), source)
                        },
                        onDownload = { viewModel.download(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartRow(entry: ChartEntry, onPlay: () -> Unit, onDownload: () -> Unit) {
    val unavailable = entry.state == "unavailable" && !entry.isReady
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                if (entry.isPlayable) {
                    onPlay()
                } else if (unavailable) {
                    onDownload()
                }
            }
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .alpha(if (entry.isPlayable) 1f else 0.5f)
            .testTag("chart-entry-${entry.position}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(entry.position.toString(), style = MaterialTheme.typography.titleMedium)
            Movement(entry)
        }
        RemoteImage(entry.coverUrl, Modifier.size(48.dp), RoundedCornerShape(10.dp), Icons.Rounded.MusicNote)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                entry.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            entry.state == "queued" ->
                Icon(Icons.Rounded.Schedule, contentDescription = stringResource(R.string.chart_state_queued))
            entry.state == "downloading" ->
                Icon(Icons.Rounded.Downloading, contentDescription = stringResource(R.string.chart_state_downloading))
            entry.isReady -> Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = stringResource(R.string.chart_state_ready),
                tint = MaterialTheme.colorScheme.primary,
            )
            entry.state == "unavailable" -> Icon(Icons.Rounded.CloudDownload, contentDescription = stringResource(R.string.chart_download))
            entry.state == "pool" -> Text(
                stringResource(R.string.player_new_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Box(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Movement(entry: ChartEntry) {
    val (icon, tint) = when (entry.movement) {
        "up" -> Icons.Rounded.ArrowUpward to Color(0xFF43A047)
        "down" -> Icons.Rounded.ArrowDownward to Color(0xFFE53935)
        "new" -> Icons.Rounded.FiberNew to MaterialTheme.colorScheme.primary
        else -> Icons.Rounded.Remove to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = entry.movement, tint = tint, modifier = Modifier.size(16.dp))
}

data class ChartsSettingsUi(val loading: Boolean = true, val charts: List<Chart> = emptyList(), val hidden: Set<String> = emptySet())

@HiltViewModel
class ChartsSettingsViewModel @Inject constructor(
    private val charts: ChartsRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ChartsSettingsUi())
    val state: StateFlow<ChartsSettingsUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val all = charts.all()
            _state.value = ChartsSettingsUi(loading = false, charts = all, hidden = settings.current.chartsHidden)
        }
    }

    fun toggle(chart: Chart) {
        val hidden = _state.value.hidden
        save(_state.value.charts, if (chart.id in hidden) hidden - chart.id else hidden + chart.id)
    }

    fun move(chart: Chart, delta: Int) {
        val list = _state.value.charts.toMutableList()
        val from = list.indexOf(chart)
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from < 0 || from == to) return
        list.add(to, list.removeAt(from))
        save(list, _state.value.hidden)
    }

    private fun save(list: List<Chart>, hidden: Set<String>) {
        _state.update { it.copy(charts = list, hidden = hidden) }
        viewModelScope.launch { charts.saveOrder(list.map { it.id }, hidden) }
    }
}

/** «Чарты на главной»: какие показывать и в каком порядке. */
@Composable
fun ChartsSettingsScreen(onBack: () -> Unit, viewModel: ChartsSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.charts_settings_title), onBack) }) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.charts.isEmpty() -> Box(Modifier.padding(padding)) { EmptyState(stringResource(R.string.charts_settings_empty)) }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("charts-settings")) {
                itemsIndexed(state.charts, key = { _, c -> c.id }) { index, chart ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = chart.id !in state.hidden, onCheckedChange = { viewModel.toggle(chart) })
                        Text(chart.title, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.move(chart, -1) }, enabled = index > 0) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.action_move_up))
                        }
                        IconButton(onClick = { viewModel.move(chart, 1) }, enabled = index < state.charts.lastIndex) {
                            Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.action_move_down))
                        }
                    }
                }
            }
        }
    }
}
