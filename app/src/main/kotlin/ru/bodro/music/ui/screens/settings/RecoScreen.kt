package ru.bodro.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.downloads.AnalysisStatus
import ru.bodro.music.core.downloads.EngineSource
import ru.bodro.music.core.downloads.EngineStatus
import ru.bodro.music.core.downloads.HarvestAccount
import ru.bodro.music.core.downloads.HarvestStatus
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.util.UiError
import ru.bodro.music.ui.util.formatTime
import ru.bodro.music.ui.util.toUiError

data class RecoUi(
    val loading: Boolean = true,
    val engine: EngineStatus? = null,
    val harvest: HarvestStatus? = null,
    val analysis: AnalysisStatus? = null,
    val error: UiError? = null,
)

/** «Рекомендации» (только администраторы): источники движка кандидатов и состояние архива Яндекса. */
@HiltViewModel
class RecoViewModel @Inject constructor(private val downloads: DownloadsRepository) : ViewModel() {
    private val _state = MutableStateFlow(RecoUi())
    val state: StateFlow<RecoUi> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.engine == null, error = null) }
            runCatching { downloads.engine() }
                .onSuccess { engine -> _state.update { it.copy(engine = engine) } }
                .onFailure { e -> _state.update { it.copy(error = e.toUiError()) } }
            runCatching { downloads.harvest() }.onSuccess { harvest -> _state.update { it.copy(harvest = harvest) } }
            runCatching { downloads.analysisStatus() }.onSuccess { analysis -> _state.update { it.copy(analysis = analysis) } }
            _state.update { it.copy(loading = false) }
        }
    }

    private fun change(block: suspend () -> EngineStatus) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { engine -> _state.update { it.copy(engine = engine) } }
                .onFailure { e -> _state.update { it.copy(error = e.toUiError()) } }
        }
    }

    fun setEnabled(source: EngineSource, enabled: Boolean) = change { downloads.updateEngine(enabled = mapOf(source.name to enabled)) }

    fun setWeight(source: EngineSource, weight: Float) = change { downloads.updateEngine(weights = mapOf(source.name to weight)) }

    fun setNewArtistShare(share: Float) = change { downloads.updateEngine(newArtistShare = share) }

    fun refill() {
        viewModelScope.launch { runCatching { downloads.refillPool() } }
    }

    fun runHarvest() {
        viewModelScope.launch {
            runCatching { downloads.runHarvest() }.onSuccess { harvest -> _state.update { it.copy(harvest = harvest) } }
        }
    }
}

@Composable
fun RecoScreen(onBack: () -> Unit, viewModel: RecoViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val refillDone = stringResource(R.string.reco_refill_started)
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.reco_title), onBack) }) { padding ->
        val engine = state.engine
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            engine == null -> ErrorState(state.error ?: UiError(R.string.error_unknown), viewModel::load, Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("reco-screen")) {
                item {
                    Text(
                        stringResource(R.string.reco_engine_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(engine.sources, key = { it.name }) { source ->
                    EngineSourceRow(source, viewModel::setEnabled, viewModel::setWeight)
                    HorizontalDivider()
                }
                item {
                    var share by remember(engine.newArtistShare) { mutableFloatStateOf(engine.newArtistShare) }
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.reco_new_artists, (share * 100).roundToInt()),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Slider(
                            value = share,
                            onValueChange = { share = (it * 20).roundToInt() / 20f },
                            onValueChangeFinished = { viewModel.setNewArtistShare(share) },
                            modifier = Modifier.testTag("reco-new-artists"),
                        )
                        OutlinedButton(onClick = {
                            viewModel.refill()
                            actions.showMessage(refillDone)
                        }) { Text(stringResource(R.string.reco_refill)) }
                    }
                }
                state.analysis?.let { analysis ->
                    item {
                        HorizontalDivider()
                        Column(Modifier.padding(16.dp).testTag("reco-analysis")) {
                            Text(stringResource(R.string.reco_analysis_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (analysis.enabled) {
                                    stringResource(R.string.reco_analysis_summary, analysis.analyzed, analysis.library, analysis.errors)
                                } else {
                                    stringResource(R.string.reco_analysis_off)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                state.harvest?.takeIf { it.enabled }?.let { harvest ->
                    item {
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.reco_harvest_title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = viewModel::runHarvest) { Text(stringResource(R.string.reco_harvest_run)) }
                        }
                    }
                    items(harvest.accounts, key = { "h-" + it.username }) { HarvestCard(it) }
                }
            }
        }
    }
}

@Composable
private fun EngineSourceRow(source: EngineSource, onEnabled: (EngineSource, Boolean) -> Unit, onWeight: (EngineSource, Float) -> Unit) {
    var weight by remember(source.weight) { mutableFloatStateOf(source.weight) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("reco-source-${source.name}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(source.title, style = MaterialTheme.typography.titleSmall)
                val status = if (source.available) {
                    stringResource(R.string.reco_source_stats, source.suggested, source.promoted, source.rejected, source.learnedWeight)
                } else {
                    source.reason.orEmpty()
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (source.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            Switch(checked = source.enabled, onCheckedChange = { onEnabled(source, it) })
        }
        if (source.enabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reco_weight, weight), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = weight,
                    onValueChange = { weight = (it * 10).roundToInt() / 10f },
                    onValueChangeFinished = { onWeight(source, weight) },
                    valueRange = 0f..3f,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HarvestCard(account: HarvestAccount) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("harvest-${account.username}")) {
        Text(account.username, style = MaterialTheme.typography.titleSmall)
        val plus = when (account.plus) {
            true -> stringResource(R.string.reco_harvest_plus_yes)
            false -> stringResource(R.string.reco_harvest_plus_no)
            null -> stringResource(R.string.reco_harvest_plus_unknown)
        }
        Text(
            stringResource(
                R.string.reco_harvest_summary,
                account.likes["track:1"] ?: 0,
                account.similarDone,
                account.edges.values.sum(),
                account.reserve,
                plus,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        account.collectors.forEach { c ->
            val line = when {
                c.disabled != null -> c.disabled
                c.lastAt != null -> stringResource(R.string.reco_harvest_last, formatTime(c.lastAt ?: 0, "dd.MM HH:mm"))
                else -> stringResource(R.string.reco_harvest_never)
            }
            Text(
                "${c.title}: $line" + if (account.running == c.name) " · ${stringResource(R.string.reco_harvest_running)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (c.disabled != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        account.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
