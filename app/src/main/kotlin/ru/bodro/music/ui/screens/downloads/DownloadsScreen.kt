package ru.bodro.music.ui.screens.downloads

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.downloads.DownloadRequest
import ru.bodro.music.core.downloads.RequestKind
import ru.bodro.music.core.downloads.RequestStatus
import ru.bodro.music.core.downloads.ServiceInfo
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Song
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.SectionHeader
import ru.bodro.music.ui.screens.search.RemoteImage
import ru.bodro.music.ui.screens.search.RequestProgress
import ru.bodro.music.ui.screens.search.statusText
import ru.bodro.music.ui.util.text
import ru.bodro.music.ui.util.toUiError

/** Результат проверки адреса сервиса. */
sealed interface CheckState {
    data object Idle : CheckState

    data object Checking : CheckState

    data class Ok(val info: ServiceInfo) : CheckState

    data class Failed(val message: String) : CheckState
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: DownloadsRepository,
    private val library: LibraryRepository,
) : ViewModel() {
    val serviceUrl: StateFlow<String?> = downloads.serviceUrl

    val requests: StateFlow<List<DownloadRequest>?> = downloads.serviceUrl
        .map { it != null }
        .flatMapLatest { on -> if (on) downloads.observe() else emptyFlow() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _check = MutableStateFlow<CheckState>(CheckState.Idle)
    val check: StateFlow<CheckState> = _check.asStateFlow()

    private val _play = Channel<Song>(Channel.BUFFERED)
    val play = _play.receiveAsFlow()

    init {
        if (downloads.isConfigured) refreshInfo()
    }

    private fun refreshInfo() {
        viewModelScope.launch {
            _check.value = CheckState.Checking
            _check.value = runCatching { downloads.info() }
                .fold({ CheckState.Ok(it) }, { CheckState.Failed(it.toUiError().text(context)) })
        }
    }

    /** Проверяет адрес и сохраняет его, только если сервис ответил. */
    fun save(rawUrl: String) {
        viewModelScope.launch {
            _check.value = CheckState.Checking
            runCatching { downloads.check(rawUrl) }
                .onSuccess {
                    downloads.setServiceUrl(rawUrl)
                    _check.value = CheckState.Ok(it)
                }
                .onFailure { _check.value = CheckState.Failed(it.toUiError().text(context)) }
        }
    }

    fun disable() {
        viewModelScope.launch {
            downloads.setServiceUrl(null)
            _check.value = CheckState.Idle
        }
    }

    fun delete(request: DownloadRequest) {
        viewModelScope.launch { runCatching { downloads.delete(request) } }
    }

    fun play(songId: String) {
        viewModelScope.launch { runCatching { library.song(songId) }.onSuccess { _play.send(it) } }
    }
}

@Composable
fun DownloadsScreen(onBack: () -> Unit, viewModel: DownloadsViewModel = hiltViewModel()) {
    val savedUrl by viewModel.serviceUrl.collectAsStateWithLifecycle()
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    var url by rememberSaveable(savedUrl) { mutableStateOf(savedUrl.orEmpty()) }
    val actions = LocalAppActions.current
    LaunchedEffect(Unit) { viewModel.play.collect { actions.play(listOf(it), 0, QueueSource.CustomSource()) } }

    Scaffold(topBar = { BodroTopBar(stringResource(R.string.settings_downloads), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("downloads-screen")) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.downloads_url)) },
                        placeholder = { Text(stringResource(R.string.downloads_url_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().testTag("downloads-url"),
                    )
                    Text(
                        stringResource(R.string.downloads_url_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.save(url) },
                            enabled = url.isNotBlank() && check != CheckState.Checking,
                            modifier = Modifier.testTag("downloads-save"),
                        ) { Text(stringResource(R.string.downloads_save)) }
                        if (savedUrl != null) {
                            OutlinedButton(onClick = viewModel::disable) { Text(stringResource(R.string.downloads_disable)) }
                        }
                    }
                    CheckStatus(check)
                }
            }
            val list = requests
            if (savedUrl != null && list != null) {
                item { SectionHeader(stringResource(R.string.downloads_requests)) }
                if (list.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.downloads_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(list, key = { it.id }) { request ->
                    RequestRow(request, onDelete = { viewModel.delete(request) }, onPlay = viewModel::play)
                }
            }
        }
    }
}

@Composable
private fun CheckStatus(check: CheckState) {
    when (check) {
        CheckState.Idle -> Unit
        CheckState.Checking -> RequestProgressRow()
        is CheckState.Ok -> Text(
            stringResource(
                R.string.downloads_ok,
                check.info.version,
                stringResource(if (check.info.trackDownloads) R.string.downloads_yes else R.string.downloads_no),
                stringResource(if (check.info.albumDownloads) R.string.downloads_yes else R.string.downloads_no),
                stringResource(if (check.info.artistDownloads) R.string.downloads_yes else R.string.downloads_no),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("downloads-ok"),
        )
        is CheckState.Failed -> Text(check.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun RequestProgressRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun RequestRow(request: DownloadRequest, onDelete: () -> Unit, onPlay: (String) -> Unit) {
    val songId = request.songId?.takeIf { request.status == RequestStatus.Completed }
    val isArtist = request.kind == RequestKind.Artist
    ListItem(
        headlineContent = {
            Text(request.title ?: request.album ?: request.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(request.artist.takeIf { !isArtist }, statusText(request)).joinToString(" · "),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (request.status == RequestStatus.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                SourceLine(request)
            }
        },
        leadingContent = {
            RemoteImage(
                request.coverUrl,
                Modifier.size(48.dp),
                if (isArtist) CircleShape else RoundedCornerShape(10.dp),
                when (request.kind) {
                    RequestKind.Artist -> Icons.Rounded.Person
                    RequestKind.Album -> Icons.Rounded.Album
                    RequestKind.Track -> Icons.Rounded.MusicNote
                },
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (request.status.isActive) RequestProgress(request)
                IconButton(onClick = onDelete) {
                    Icon(
                        if (request.status.isActive) Icons.Rounded.Close else Icons.Rounded.Delete,
                        contentDescription = stringResource(
                            if (request.status.isActive) R.string.downloads_cancel else R.string.downloads_remove,
                        ),
                    )
                }
            }
        },
        modifier = Modifier
            .let { if (songId != null) it.clickable { onPlay(songId) } else it }
            .testTag("download-${request.id}"),
    )
}

/** Итоговый источник («Яндекс Музыка, FLAC») и неудачные попытки цепочки до него. */
@Composable
private fun SourceLine(request: DownloadRequest) {
    val misses = request.attempts.filter { it.result != "ok" }.map { attempt ->
        val label = stringResource(
            if (attempt.result == "skip") R.string.downloads_attempt_skip else R.string.downloads_attempt_fail,
            attempt.title,
        )
        attempt.reason?.let { "$label ($it)" } ?: label
    }
    val lines = listOfNotNull(
        request.source?.let { stringResource(R.string.downloads_source, it) },
        misses.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.downloads_attempts, it.joinToString("; ")) },
    )
    if (lines.isEmpty()) return
    Text(
        lines.joinToString("\n"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("download-source-${request.id}"),
    )
}
