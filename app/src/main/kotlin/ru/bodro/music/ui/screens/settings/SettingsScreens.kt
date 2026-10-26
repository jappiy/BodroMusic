package ru.bodro.music.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.domain.player.StreamQuality
import ru.bodro.music.core.domain.player.TranscodeCodec
import ru.bodro.music.core.domain.wave.WaveCharacter
import ru.bodro.music.core.domain.wave.WaveMood
import ru.bodro.music.core.downloads.WaveReportMode
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.data.settings.BackgroundAnimation
import ru.bodro.music.data.settings.ThemeMode
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.LoadState
import ru.bodro.music.ui.util.formatBytes

@Composable
fun SettingsScreen(
    onOpenAccounts: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenWave: () -> Unit,
    onOpenCharts: () -> Unit = {},
    onOpenAppearance: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenCleanup: () -> Unit,
    onOpenBlocklist: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenPlaylistAudit: () -> Unit = {},
    onOpenSources: () -> Unit,
    onOpenReco: () -> Unit = {},
    onOpenVersions: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().testTag("settings-screen")) {
        item {
            Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(16.dp))
        }
        item {
            SettingsEntry(
                Icons.Rounded.AccountCircle,
                R.string.settings_accounts,
                R.string.settings_accounts_summary,
                onOpenAccounts,
                "settings-accounts",
            )
        }
        item {
            SettingsEntry(
                Icons.Rounded.PlayCircle,
                R.string.settings_playback,
                R.string.settings_playback_summary,
                onOpenPlayback,
                "settings-playback",
            )
        }
        item { SettingsEntry(Icons.Rounded.Waves, R.string.settings_wave, R.string.settings_wave_summary, onOpenWave, "settings-wave") }
        item {
            SettingsEntry(
                Icons.Rounded.Leaderboard,
                R.string.charts_settings_title,
                R.string.settings_charts_summary,
                onOpenCharts,
                "settings-charts",
            )
        }
        item {
            SettingsEntry(
                Icons.Rounded.Palette,
                R.string.settings_appearance,
                R.string.settings_appearance_summary,
                onOpenAppearance,
                "settings-appearance",
            )
        }
        item {
            SettingsEntry(
                Icons.Rounded.Download,
                R.string.settings_downloads,
                R.string.settings_downloads_summary,
                onOpenDownloads,
                "settings-downloads",
            )
        }
        item {
            SettingsEntry(
                Icons.Rounded.DeleteSweep,
                R.string.cleanup_title,
                R.string.settings_cleanup_summary,
                onOpenCleanup,
                "settings-cleanup",
            )
        }
        item {
            SettingsEntry(
                Icons.Rounded.Block,
                R.string.blocklist_title,
                R.string.settings_blocklist_summary,
                onOpenBlocklist,
                "settings-blocklist",
            )
        }
        item {
            SettingsEntry(
                Icons.Rounded.AutoFixHigh,
                R.string.maintenance_title,
                R.string.settings_maintenance_summary,
                onOpenMaintenance,
                "settings-maintenance",
            )
        }
        item {
            SettingsEntry(
                Icons.AutoMirrored.Rounded.QueueMusic,
                R.string.playlist_audit_title,
                R.string.settings_playlist_audit_summary,
                onOpenPlaylistAudit,
                "settings-playlist-audit",
            )
        }
        item {
            SettingsEntry(
                Icons.Rounded.CloudDownload,
                R.string.sources_title,
                R.string.settings_sources_summary,
                onOpenSources,
                "settings-sources",
            )
        }
        item {
            SettingsEntry(
                Icons.Rounded.AutoAwesome,
                R.string.reco_title,
                R.string.settings_reco_summary,
                onOpenReco,
                "settings-reco",
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            SettingsEntry(Icons.Rounded.ReceiptLong, R.string.settings_logs, R.string.settings_logs_summary, onOpenLogs, "settings-logs")
        }
        item {
            SettingsEntry(
                Icons.Rounded.History,
                R.string.settings_versions,
                R.string.settings_versions_summary,
                onOpenVersions,
                "settings-versions",
            )
        }
        item { SettingsEntry(Icons.Rounded.Info, R.string.settings_about, null, onOpenAbout, "settings-about") }
    }
}

@Composable
private fun SettingsEntry(icon: ImageVector, title: Int, summary: Int?, onClick: () -> Unit, tag: String) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = summary?.let { { Text(stringResource(it)) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick).testTag(tag),
    )
}

@Composable
fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.selectable(selected = checked, enabled = enabled, role = Role.Switch) { onChange(!checked) },
    )
}

@Composable
fun <T> ChoiceGroup(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
    options.forEach { option ->
        Row(
            Modifier.fillMaxWidth().selectable(selected = option == selected, role = Role.RadioButton) {
                onSelect(option)
            }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option == selected, onClick = null)
            Text(label(option), modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
fun qualityLabel(quality: StreamQuality): String = when (quality) {
    StreamQuality.Original -> stringResource(R.string.quality_original)
    else -> stringResource(R.string.quality_slow, quality.maxBitRate ?: 0)
}

@Composable
fun PlaybackSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val used by viewModel.cacheUsed.collectAsStateWithLifecycle()
    val offline by viewModel.offlineUsage.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshCacheSize() }
    LaunchedEffect(settings.downloadLiked) {
        // Скачивание идёт в фоне — обновлять счётчик, пока экран открыт.
        while (true) {
            viewModel.refreshOfflineUsage()
            delay(OFFLINE_REFRESH_MS)
        }
    }
    // Размер кэша редактируется в поле; несохранённое значение сохраняется по «Сохранить», «Готово» на клавиатуре
    // и при выходе с экрана (кнопка или свайп «назад»). Неверное значение при выходе — диалог.
    var cacheText by rememberSaveable(settings.cacheSizeMb) { mutableStateOf(settings.cacheSizeMb.toString()) }
    val cacheValue = cacheText.toIntOrNull()
    val cacheValid = cacheValue != null && cacheValue in AppSettings.MIN_CACHE_MB..AppSettings.MAX_CACHE_MB
    val cacheDirty = cacheText != settings.cacheSizeMb.toString()
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    val saveCache = {
        if (cacheValid && cacheDirty && cacheValue != null) viewModel.update { it.copy(cacheSizeMb = cacheValue) }
    }
    val leave = {
        when {
            !cacheDirty -> onBack()
            cacheValid -> {
                saveCache()
                onBack()
            }
            else -> confirmExit = true
        }
    }
    BackHandler(enabled = cacheDirty, onBack = leave)
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.settings_unsaved_title)) },
            text = { Text(stringResource(R.string.settings_unsaved_invalid, AppSettings.MIN_CACHE_MB, AppSettings.MAX_CACHE_MB)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    onBack()
                }) { Text(stringResource(R.string.settings_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.settings_playback), leave) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            ChoiceGroup(stringResource(R.string.settings_quality), AppSettings.QUALITIES, settings.quality, {
                qualityLabel(it)
            }) { q ->
                viewModel.update { it.copy(quality = q) }
            }
            if (!settings.quality.isOriginal) {
                ChoiceGroup(stringResource(R.string.settings_codec), TranscodeCodec.entries, settings.transcodeCodec, {
                    it.format.uppercase()
                }) { c ->
                    viewModel.update { it.copy(transcodeCodec = c) }
                }
            }
            val offlineSummary = offline?.takeIf { it.count > 0 }?.let {
                "\n" + stringResource(R.string.settings_download_liked_used, it.count, formatBytes(it.bytes))
            }.orEmpty()
            SwitchRow(
                stringResource(R.string.settings_download_liked),
                stringResource(R.string.settings_download_liked_summary) + offlineSummary,
                settings.downloadLiked,
                { v -> viewModel.update { it.copy(downloadLiked = v) } },
            )
            CacheSizeField(cacheText, cacheValue, cacheValid, onChange = { cacheText = it }, onDone = saveCache)
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = saveCache, enabled = cacheDirty && cacheValid, modifier = Modifier.testTag("cache-size-save")) {
                    Text(stringResource(R.string.settings_save))
                }
                if (!cacheDirty) {
                    Text(
                        stringResource(R.string.settings_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            Text(
                stringResource(R.string.settings_cache_restart_hint) +
                    (used?.let { "\n" + stringResource(R.string.settings_cache_used, formatBytes(it)) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedButton(onClick = viewModel::clearCache, modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_cache_clear))
            }
            SwitchRow(
                stringResource(R.string.settings_resume),
                stringResource(R.string.settings_resume_summary),
                settings.resumeOnStart,
                { v -> viewModel.update { it.copy(resumeOnStart = v) } },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_gapless)) },
                supportingContent = { Text(stringResource(R.string.settings_gapless_summary)) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_scrobble_threshold)) },
                supportingContent = { Text(stringResource(R.string.settings_scrobble_threshold_summary)) },
            )
        }
    }
}

/** Размер кэша в мегабайтах. Сохраняет экран ([onDone] — «Готово» на клавиатуре, кнопка «Сохранить», выход). */
@Composable
private fun CacheSizeField(text: String, value: Int?, valid: Boolean, onChange: (String) -> Unit, onDone: () -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = { input -> onChange(input.filter(Char::isDigit).take(6)) },
        label = { Text(stringResource(R.string.settings_cache_size)) },
        suffix = { Text(stringResource(R.string.unit_mb)) },
        supportingText = {
            Text(
                if (value != null && valid) {
                    formatBytes(value.toLong() * 1024 * 1024)
                } else {
                    stringResource(R.string.settings_cache_size_range, AppSettings.MIN_CACHE_MB, AppSettings.MAX_CACHE_MB)
                },
            )
        },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            onDone()
            focus.clearFocus()
        }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .testTag("cache-size-field"),
    )
}

private const val OFFLINE_REFRESH_MS = 5_000L

private fun waveMoodLabel(mood: WaveMood): Int = when (mood) {
    WaveMood.Any -> R.string.wave_mood_any
    WaveMood.Energetic -> R.string.wave_mood_energetic
    WaveMood.Calm -> R.string.wave_mood_calm
    WaveMood.Happy -> R.string.wave_mood_happy
    WaveMood.Sad -> R.string.wave_mood_sad
    WaveMood.Loud -> R.string.wave_mood_loud
}

/** Чипы с переносом на следующую строку: на узком экране и крупном шрифте ничего не сжимается. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaveChips(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
private fun WaveHint(text: Int, top: Dp = 8.dp) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = top, bottom = 24.dp),
    )
}

fun waveCharacterLabel(character: WaveCharacter): Int = when (character) {
    WaveCharacter.Favorites -> R.string.wave_character_favorites
    WaveCharacter.Mix -> R.string.wave_character_mix
    WaveCharacter.Discover -> R.string.wave_character_discover
    WaveCharacter.Popular -> R.string.wave_character_popular
}

/** Доля «открытий», которую выставляет пресет характера (та же, что доля пула «На пробу»), шаг 5 %. */
internal fun WaveCharacter.discoveryPreset(): Float = (poolShare * 20).roundToInt() / 20f

/** Чип характера выделен, только если слайдер стоит на его значении: ручное значение снимает выделение. */
internal fun selectedCharacter(character: WaveCharacter, share: Float): WaveCharacter? =
    character.takeIf { abs(it.discoveryPreset() - share) < 0.001f }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WaveSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val selected = selectedCharacter(settings.waveCharacter, settings.waveDiscoveryShare)
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.settings_wave), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.settings_wave_character), style = MaterialTheme.typography.titleMedium)
            WaveChips(Modifier.padding(top = 8.dp)) {
                WaveCharacter.entries.forEach { character ->
                    FilterChip(
                        selected = selected == character,
                        onClick = {
                            viewModel.update { it.copy(waveCharacter = character, waveDiscoveryShare = character.discoveryPreset()) }
                        },
                        label = { Text(stringResource(waveCharacterLabel(character)), maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("wave-character-${character.name}"),
                    )
                }
            }
            WaveHint(R.string.settings_wave_character_hint)
            Text(stringResource(R.string.settings_wave_mood), style = MaterialTheme.typography.titleMedium)
            WaveChips(Modifier.padding(top = 8.dp)) {
                WaveMood.entries.forEach { mood ->
                    FilterChip(
                        selected = settings.waveMood == mood,
                        onClick = { viewModel.update { it.copy(waveMood = mood) } },
                        label = { Text(stringResource(waveMoodLabel(mood)), maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("wave-mood-${mood.name}"),
                    )
                }
            }
            WaveHint(R.string.settings_wave_mood_hint)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_wave_discovery),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.settings_wave_discovery_value, (settings.waveDiscoveryShare * 100).roundToInt()),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Slider(
                value = settings.waveDiscoveryShare,
                onValueChange = { v ->
                    val share = (v * 20).roundToInt() / 20f
                    // Значение пресета — выбрать его характер; иначе характер остаётся, а чип гаснет.
                    val preset = WaveCharacter.entries.firstOrNull { abs(it.discoveryPreset() - share) < 0.001f }
                    viewModel.update { it.copy(waveDiscoveryShare = share, waveCharacter = preset ?: it.waveCharacter) }
                },
                valueRange = 0f..0.6f,
                track = { state -> SliderDefaults.Track(sliderState = state, drawStopIndicator = null, thumbTrackGapSize = 0.dp) },
                modifier = Modifier.padding(top = 4.dp).testTag("discovery-slider"),
            )
            WaveHint(R.string.settings_wave_discovery_hint, top = 0.dp)
            Text(stringResource(R.string.settings_skip_cooldown), style = MaterialTheme.typography.titleMedium)
            WaveChips(Modifier.padding(top = 8.dp)) {
                AppSettings.SKIP_COOLDOWN_OPTIONS.forEach { days ->
                    FilterChip(
                        selected = settings.waveSkipCooldownDays == days,
                        onClick = { viewModel.update { it.copy(waveSkipCooldownDays = days) } },
                        label = { Text(stringResource(R.string.settings_skip_cooldown_days, days), maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("skip-cooldown-$days"),
                    )
                }
            }
            WaveHint(R.string.settings_skip_cooldown_summary, top = 0.dp)
            OutlinedButton(onClick = viewModel::resetSkipStats) {
                Text(stringResource(R.string.settings_wave_reset_skips))
            }
            if (settings.skipStatsResetAt > 0) {
                Text(
                    stringResource(
                        R.string.settings_wave_reset_done,
                        ru.bodro.music.ui.util.formatTime(settings.skipStatsResetAt, "dd.MM.yyyy HH:mm"),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WaveReportSection()
        }
    }
}

/** Отчёт bodrod (0.19.0+): сколько треков проанализировано, какие признаки есть и сколько треков в каждом режиме. */
@HiltViewModel
class WaveReportViewModel @Inject constructor(private val wave: ru.bodro.music.data.repository.WaveRepository) : ViewModel() {
    private val _report = MutableStateFlow<LoadState<ru.bodro.music.core.downloads.WaveReport?>>(LoadState.Loading)
    val report: StateFlow<LoadState<ru.bodro.music.core.downloads.WaveReport?>> = _report.asStateFlow()

    init {
        viewModelScope.launch { _report.value = LoadState.Content(wave.report()) }
    }
}

/**
 * «Анализ звучания и режимы» понятными словами: сколько проанализировано и не удалось (с причинами), сколько треков в
 * каждом настроении и режиме — названиями, как на главной; язык вокала. Технические признаки — под «Подробнее».
 */
@Composable
private fun WaveReportSection(viewModel: WaveReportViewModel = hiltViewModel()) {
    val state by viewModel.report.collectAsStateWithLifecycle()
    val report = (state as? LoadState.Content)?.data
    Text(stringResource(R.string.wave_report_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 32.dp))
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        state is LoadState.Loading -> Unit
        report == null -> Text(stringResource(R.string.wave_report_unavailable), style = small, color = muted)
        else -> WaveReportContent(report)
    }
}

/** Содержимое отчёта (без загрузки) — отдельно, чтобы снимать его в тестах. */
@Composable
internal fun WaveReportContent(report: ru.bodro.music.core.downloads.WaveReport) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.testTag("wave-report")) {
        Text(
            stringResource(R.string.wave_report_analyzed, report.analyzed, report.library),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (report.library > 0) {
            LinearProgressIndicator(
                progress = { report.analyzed.toFloat() / report.library },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                drawStopIndicator = {},
            )
        }
        if (report.failed > 0) {
            Text(stringResource(R.string.wave_report_failed, report.failed), style = MaterialTheme.typography.bodyMedium)
            report.failures.forEach { failure ->
                Text(stringResource(R.string.wave_report_reason, failure.reason, failure.count), style = small, color = muted)
            }
            if (report.excluded > 0) Text(stringResource(R.string.wave_report_excluded, report.excluded), style = small, color = muted)
        }
        report.pending?.takeIf { it > 0 }?.let {
            Text(stringResource(R.string.wave_report_pending, it), style = MaterialTheme.typography.bodyMedium)
        }
        val modes = report.modeList.ifEmpty { report.modes.map { (id, n) -> WaveReportMode(id, id, n, id in MOOD_IDS) } }
        val moods = modes.filter { it.mood }
        if (moods.isNotEmpty()) {
            ReportHeader(R.string.wave_report_moods)
            moods.forEach { ReportRow(it.title, it.count) }
            report.noMood?.let { ReportRow(stringResource(R.string.wave_report_no_mood), it) }
            Text(stringResource(R.string.wave_report_moods_hint), style = small, color = muted, modifier = Modifier.padding(top = 4.dp))
        }
        val others = modes.filterNot { it.mood }
        if (others.isNotEmpty()) {
            ReportHeader(R.string.wave_report_modes_title)
            others.forEach { ReportRow(it.title, it.count) }
        }
        if (report.languages.isNotEmpty()) {
            ReportHeader(R.string.wave_report_languages_title)
            LANGUAGE_ORDER.forEach { (code, label) -> report.languages[code]?.let { ReportRow(stringResource(label), it) } }
            val other = report.languages.filterKeys { k -> LANGUAGE_ORDER.none { it.first == k } }.values.sum()
            if (other > 0) ReportRow(stringResource(R.string.wave_report_lang_other), other)
            Text(stringResource(R.string.wave_report_languages_hint), style = small, color = muted, modifier = Modifier.padding(top = 4.dp))
        }
        var details by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { details = !details }, modifier = Modifier.padding(top = 8.dp).testTag("wave-report-details")) {
            Text(stringResource(if (details) R.string.wave_report_details_hide else R.string.wave_report_details_show))
        }
        if (details) {
            Text(stringResource(R.string.wave_report_details_hint), style = small, color = muted)
            FEATURE_LABELS.forEach { (key, label) -> report.features[key]?.let { ReportRow(stringResource(label), it) } }
            if (report.loudnessEstimated > 0) {
                Text(
                    stringResource(R.string.wave_report_loudness_estimated, report.loudnessEstimated),
                    style = small,
                    color = muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ReportHeader(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun ReportRow(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.wave_report_count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Режимы-настроения у сервера до 0.24.0 (он не помечал их сам). */
private val MOOD_IDS = setOf("energetic", "calm", "happy", "sad", "loud")

private val LANGUAGE_ORDER = listOf(
    "ru" to R.string.wave_report_lang_ru,
    "en" to R.string.wave_report_lang_en,
    "none" to R.string.wave_report_lang_none,
    "unknown" to R.string.wave_report_lang_unknown,
)

private val FEATURE_LABELS = listOf(
    "bpm" to R.string.wave_feature_bpm,
    "energy" to R.string.wave_feature_energy,
    "valence" to R.string.wave_feature_valence,
    "aggressive" to R.string.wave_feature_aggressive,
    "relaxed" to R.string.wave_feature_relaxed,
    "danceability" to R.string.wave_feature_danceability,
    "instrumental" to R.string.wave_feature_instrumental,
    "acoustic" to R.string.wave_feature_acoustic,
    "electronic" to R.string.wave_feature_electronic,
    "camelot" to R.string.wave_feature_camelot,
    "loudness" to R.string.wave_feature_loudness,
)

@Composable
fun AppearanceScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.settings_appearance), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            ChoiceGroup(stringResource(R.string.settings_theme), ThemeMode.entries, settings.themeMode, { mode ->
                stringResource(
                    when (mode) {
                        ThemeMode.System -> R.string.theme_system
                        ThemeMode.Light -> R.string.theme_light
                        ThemeMode.Dark -> R.string.theme_dark
                    },
                )
            }) { mode -> viewModel.update { it.copy(themeMode = mode) } }
            SwitchRow(
                stringResource(R.string.settings_cover_colors),
                stringResource(R.string.settings_cover_colors_summary),
                settings.coverColors,
                { v -> viewModel.update { it.copy(coverColors = v) } },
            )
            ChoiceGroup(
                stringResource(
                    R.string.settings_background_animation,
                ),
                listOf(BackgroundAnimation.Off, BackgroundAnimation.Eco, BackgroundAnimation.Full),
                settings.backgroundAnimation,
                { mode ->
                    stringResource(
                        when (mode) {
                            BackgroundAnimation.Full -> R.string.background_animation_full
                            BackgroundAnimation.Eco -> R.string.background_animation_eco
                            BackgroundAnimation.Off -> R.string.background_animation_off
                        },
                    )
                },
            ) { mode -> viewModel.update { it.copy(backgroundAnimation = mode) } }
            Text(
                stringResource(R.string.settings_background_animation_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SwitchRow(
                stringResource(R.string.settings_dynamic_color),
                stringResource(R.string.settings_dynamic_color_summary),
                settings.dynamicColor,
                { v -> viewModel.update { it.copy(dynamicColor = v) } },
                enabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
            )
        }
    }
}
