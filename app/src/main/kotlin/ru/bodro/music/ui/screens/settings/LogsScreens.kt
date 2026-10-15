package ru.bodro.music.ui.screens.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.bodro.music.BuildConfig
import ru.bodro.music.R
import ru.bodro.music.core.subsonic.NetworkLogLevel
import ru.bodro.music.core.subsonic.RequestCategory
import ru.bodro.music.core.subsonic.prettyJsonOrRaw
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.local.LogEntryEntity
import ru.bodro.music.data.local.LogType
import ru.bodro.music.data.log.LogFilter
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.repository.ReactionRepository
import ru.bodro.music.data.repository.ScrobbleRepository
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.sync.SyncScheduler
import ru.bodro.music.ui.app.LogDetailRoute
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.EmptyState
import ru.bodro.music.ui.util.formatBytes
import ru.bodro.music.ui.util.formatTime

@HiltViewModel
class LogsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: LogRepository,
    private val settings: SettingsRepository,
    private val connection: ConnectionManager,
    private val syncScheduler: SyncScheduler,
    scrobbles: ScrobbleRepository,
    reactions: ReactionRepository,
) : ViewModel() {
    private val _filter = MutableStateFlow(LogFilter())
    val filter: StateFlow<LogFilter> = _filter.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val entries: StateFlow<List<LogEntryEntity>> = _filter.debounce(150).flatMapLatest { log.observe(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val endpoints: StateFlow<List<String>> = log.observeEndpoints().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val pendingScrobbles: StateFlow<Int> = scrobbles.observePendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val pendingReactions: StateFlow<Int> = reactions.observePendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val level: StateFlow<NetworkLogLevel> = settings.settings.map { it.logLevel }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.current.logLevel)

    private val _share = Channel<File>(Channel.BUFFERED)
    val share = _share.receiveAsFlow()

    fun update(transform: (LogFilter) -> LogFilter) = _filter.update(transform)

    fun setLevel(level: NetworkLogLevel) {
        viewModelScope.launch { settings.update { it.copy(logLevel = level) } }
    }

    fun syncNow() = syncScheduler.syncNow()

    fun clear() {
        viewModelScope.launch { log.clear() }
    }

    fun export() {
        viewModelScope.launch {
            val selection = connection.selection.value
            val header = buildString {
                appendLine("BodroMusic ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.GIT_HASH}")
                appendLine(
                    "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}), " +
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                )
                appendLine("Учётка: ${connection.activeAccount?.username}; адрес: ${selection?.kind} ${selection?.baseUrl}")
                append(context.getString(R.string.logs_export_note))
            }
            _share.send(log.exportToFile(header))
        }
    }
}

@Composable
fun LogsScreen(onBack: () -> Unit, onOpenEntry: (Long) -> Unit, viewModel: LogsViewModel = hiltViewModel()) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val endpoints by viewModel.endpoints.collectAsStateWithLifecycle()
    val pendingScrobbles by viewModel.pendingScrobbles.collectAsStateWithLifecycle()
    val pendingReactions by viewModel.pendingReactions.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var levelMenu by rememberSaveable { mutableStateOf(false) }
    var endpointMenu by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.share.collect { file -> shareFile(context, file) } }

    Scaffold(
        topBar = {
            BodroTopBar(stringResource(R.string.settings_logs), onBack, actions = {
                IconButton(onClick = {
                    levelMenu = true
                }) { Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.logs_level)) }
                DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                    NetworkLogLevel.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text((if (option == level) "✓ " else "") + logLevelLabel(option)) },
                            onClick = {
                                viewModel.setLevel(option)
                                levelMenu = false
                            },
                        )
                    }
                }
                IconButton(onClick = viewModel::export, modifier = Modifier.testTag("logs-share")) {
                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.logs_share))
                }
                IconButton(onClick = {
                    confirmClear = true
                }) { Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.logs_clear)) }
            })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("logs-screen")) {
            if (pendingScrobbles > 0 || pendingReactions > 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("pending-card"),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.logs_pending, pendingScrobbles, pendingReactions), modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::syncNow) { Text(stringResource(R.string.logs_send_now)) }
                    }
                }
            }
            TabRow(selectedTabIndex = if (filter.type == LogType.Network) 0 else 1) {
                Tab(selected = filter.type == LogType.Network, onClick = {
                    viewModel.update { it.copy(type = LogType.Network, category = null, endpoint = null) }
                }, text = {
                    Text(stringResource(R.string.logs_tab_requests))
                })
                Tab(selected = filter.type == LogType.Player, onClick = {
                    viewModel.update { it.copy(type = LogType.Player, category = null, endpoint = null) }
                }, text = {
                    Text(stringResource(R.string.logs_tab_player))
                }, modifier = Modifier.testTag("logs-tab-player"))
            }
            OutlinedTextField(
                value = filter.query.orEmpty(),
                onValueChange = { q -> viewModel.update { it.copy(query = q) } },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.logs_search)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(filter.errorsOnly, {
                        viewModel.update { it.copy(errorsOnly = !it.errorsOnly) }
                    }, label = { Text(stringResource(R.string.logs_errors_only)) }, modifier = Modifier.testTag("logs-errors-only"))
                }
                if (filter.type == LogType.Network) {
                    items(RequestCategory.entries) { category ->
                        FilterChip(
                            selected = filter.category == category.name,
                            onClick = {
                                viewModel.update {
                                    it.copy(
                                        category = if (it.category ==
                                            category.name
                                        ) {
                                            null
                                        } else {
                                            category.name
                                        },
                                    )
                                }
                            },
                            label = { Text(categoryLabel(category)) },
                        )
                    }
                    item {
                        FilterChip(
                            selected = filter.endpoint != null,
                            onClick = {
                                if (filter.endpoint !=
                                    null
                                ) {
                                    viewModel.update { it.copy(endpoint = null) }
                                } else {
                                    endpointMenu = true
                                }
                            },
                            label = { Text(filter.endpoint ?: stringResource(R.string.logs_endpoint)) },
                        )
                        DropdownMenu(expanded = endpointMenu, onDismissRequest = { endpointMenu = false }) {
                            endpoints.forEach { endpoint ->
                                DropdownMenuItem(text = { Text(endpoint) }, onClick = {
                                    viewModel.update { it.copy(endpoint = endpoint) }
                                    endpointMenu = false
                                })
                            }
                        }
                    }
                }
            }
            if (entries.isEmpty()) {
                EmptyState(stringResource(R.string.logs_empty))
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag("logs-list"), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(entries, key = { it.id }) { entry -> LogRow(entry) { onOpenEntry(entry.id) } }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.logs_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    confirmClear = false
                }) { Text(stringResource(R.string.logs_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun LogRow(entry: LogEntryEntity, onClick: () -> Unit) {
    val errorColor = MaterialTheme.colorScheme.error
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp).testTag("log-${entry.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                formatTime(entry.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                entry.endpoint,
                style = MaterialTheme.typography.titleSmall,
                color = if (entry.isError) errorColor else MaterialTheme.colorScheme.onSurface,
            )
            entry.serverKind?.let {
                Text(
                    if (it ==
                        "Local"
                    ) {
                        "LAN"
                    } else {
                        "WAN"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        val details = listOfNotNull(
            entry.httpCode?.let { "HTTP $it" },
            entry.subsonicStatus,
            entry.errorCode?.let { "code $it" },
            entry.durationMs?.let { "$it мс" },
            entry.size?.let { formatBytes(it) },
        ).joinToString(" · ")
        if (details.isNotEmpty()) {
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entry.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.isError) errorColor else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun categoryLabel(category: RequestCategory): String = stringResource(
    when (category) {
        RequestCategory.Api -> R.string.logs_category_api
        RequestCategory.Stream -> R.string.logs_category_stream
        RequestCategory.Cover -> R.string.logs_category_cover
        RequestCategory.Scrobble -> R.string.logs_category_scrobble
    },
)

@Composable
private fun logLevelLabel(level: NetworkLogLevel): String = stringResource(
    when (level) {
        NetworkLogLevel.ErrorsOnly -> R.string.logs_level_errors
        NetworkLogLevel.Normal -> R.string.logs_level_normal
        NetworkLogLevel.Verbose -> R.string.logs_level_verbose
    },
)

private fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.logs_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// region Детали записи

@HiltViewModel
class LogDetailViewModel @Inject constructor(savedState: SavedStateHandle, log: LogRepository) : ViewModel() {
    private val _entry = MutableStateFlow<LogEntryEntity?>(null)
    val entry: StateFlow<LogEntryEntity?> = _entry.asStateFlow()

    init {
        val id = savedState.toRoute<LogDetailRoute>().id
        viewModelScope.launch { _entry.value = log.get(id) }
    }
}

@Composable
fun LogDetailScreen(onBack: () -> Unit, viewModel: LogDetailViewModel = hiltViewModel()) {
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    Scaffold(topBar = { BodroTopBar(entry?.endpoint ?: "", onBack) }) { padding ->
        val e = entry ?: return@Scaffold
        SelectionContainer {
            Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag("log-detail")) {
                Field(stringResource(R.string.logs_field_time), formatTime(e.timestamp, "dd.MM.yyyy HH:mm:ss.SSS"))
                Field(stringResource(R.string.logs_field_type), "${e.type} / ${e.category}")
                e.serverKind?.let { Field(stringResource(R.string.logs_field_server), it) }
                e.method?.let { Field(stringResource(R.string.logs_field_method), it) }
                e.url?.let { Field("URL", it, mono = true) }
                e.httpCode?.let { Field("HTTP", it.toString()) }
                e.subsonicStatus?.let { Field("Subsonic status", it) }
                e.errorCode?.let { Field(stringResource(R.string.logs_field_error_code), it.toString()) }
                e.message?.let { Field(stringResource(R.string.logs_field_message), it) }
                e.durationMs?.let { Field(stringResource(R.string.logs_field_duration), "$it мс") }
                e.size?.let { Field(stringResource(R.string.logs_field_size), formatBytes(it)) }
                e.requestHeaders?.takeIf {
                    it.isNotBlank()
                }?.let { Field(stringResource(R.string.logs_field_request_headers), it, mono = true) }
                e.responseHeaders?.takeIf {
                    it.isNotBlank()
                }?.let { Field(stringResource(R.string.logs_field_response_headers), it, mono = true) }
                Field(
                    stringResource(R.string.logs_field_body),
                    e.body?.let(::prettyJsonOrRaw) ?: stringResource(R.string.logs_body_hint),
                    mono = e.body != null,
                    scrollable = true,
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false, scrollable: Boolean = false) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        value,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        modifier = if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier,
    )
}

// endregion
