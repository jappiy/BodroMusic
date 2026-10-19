package ru.bodro.music.ui.screens.pool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Song
import ru.bodro.music.data.reco.PoolRepository
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.EmptyState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.components.SongRow

data class PoolUi(
    val loading: Boolean = true,
    val supported: Boolean = true,
    val songs: List<Song> = emptyList(),
    val total: Int = 0,
)

@HiltViewModel
class PoolViewModel @Inject constructor(private val pool: PoolRepository) : ViewModel() {
    private val _state = MutableStateFlow(PoolUi())
    val state: StateFlow<PoolUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            pool.items.collectLatest { items ->
                val songs = pool.songs()
                _state.update { it.copy(loading = false, songs = songs, total = items.size) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val supported = pool.isSupported()
            _state.update { it.copy(supported = supported, loading = supported && it.songs.isEmpty()) }
            if (supported) pool.refresh()
            _state.update { it.copy(loading = false) }
        }
    }
}

/** Сколько треков в пуле — для пункта «На пробу» в «Коллекции» (без загрузки самих треков). */
@HiltViewModel
class PoolCountViewModel @Inject constructor(pool: PoolRepository) : ViewModel() {
    val count: StateFlow<Int> = pool.items
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), pool.items.value.size)
}

/** «На пробу»: треки discovery-пула. Лайк, два полных прослушивания или плейлист переводят трек в библиотеку. */
@Composable
fun PoolScreen(onBack: () -> Unit, viewModel: PoolViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val title = stringResource(R.string.pool_title)
    val source = QueueSource.CustomSource(title)
    Scaffold(topBar = { BodroTopBar(title, onBack) }) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            !state.supported -> Box(Modifier.padding(padding)) { EmptyState(stringResource(R.string.pool_unsupported)) }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag("pool-screen"),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            pluralStringResource(R.plurals.tracks, state.total, state.total),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.pool_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PoolButton(
                                Icons.Rounded.PlayArrow,
                                stringResource(R.string.action_play),
                                primary = true,
                                modifier = Modifier.testTag("pool-play"),
                            ) {
                                actions.play(state.songs, 0, source)
                            }
                            PoolButton(Icons.Rounded.Shuffle, stringResource(R.string.action_shuffle)) {
                                actions.play(state.songs, 0, source, shuffle = true)
                            }
                            PoolButton(Icons.Rounded.Refresh, stringResource(R.string.action_refresh), onClick = viewModel::refresh)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (state.songs.isEmpty()) {
                    item { Box(Modifier.height(300.dp)) { EmptyState(stringResource(R.string.pool_empty)) } }
                }
                itemsIndexed(state.songs, key = { _, song -> song.id }) { index, song ->
                    SongRow(song, onClick = { actions.play(state.songs, index, source) })
                }
            }
        }
    }
}

@Composable
private fun PoolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val tint = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            Icon(icon, contentDescription = if (primary) null else label, tint = tint, modifier = Modifier.size(24.dp))
            if (primary) {
                Spacer(Modifier.size(8.dp))
                Text(label, color = tint, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
