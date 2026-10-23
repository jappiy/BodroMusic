package ru.bodro.music.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import ru.bodro.music.R
import ru.bodro.music.core.domain.player.StreamQuality
import ru.bodro.music.core.domain.player.TranscodeCodec
import ru.bodro.music.core.domain.wave.WaveCharacter
import ru.bodro.music.core.domain.wave.WaveMood
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.data.settings.ThemeMode
import ru.bodro.music.ui.components.BodroTopBar
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
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.settings_playback), onBack) }) { padding ->
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
            CacheSizeField(settings.cacheSizeMb) { size -> viewModel.update { it.copy(cacheSizeMb = size) } }
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

/** Размер кэша вводится вручную (в мегабайтах); сохраняется по «Готово» на клавиатуре или при уходе с поля. */
@Composable
private fun CacheSizeField(currentMb: Int, onSave: (Int) -> Unit) {
    var text by remember(currentMb) { mutableStateOf(currentMb.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in AppSettings.MIN_CACHE_MB..AppSettings.MAX_CACHE_MB
    val focus = LocalFocusManager.current
    val save = {
        if (value != null && valid && value != currentMb) onSave(value)
        if (!valid) text = currentMb.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input -> text = input.filter(Char::isDigit).take(6) },
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
            save()
            focus.clearFocus()
        }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .onFocusChanged { if (!it.isFocused) save() }
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
}

private fun waveCharacterLabel(character: WaveCharacter): Int = when (character) {
    WaveCharacter.Favorites -> R.string.wave_character_favorites
    WaveCharacter.Mix -> R.string.wave_character_mix
    WaveCharacter.Discover -> R.string.wave_character_discover
}

@Composable
fun WaveSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.settings_wave), onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Text(stringResource(R.string.settings_wave_character), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                WaveCharacter.entries.forEach { character ->
                    FilterChip(
                        selected = settings.waveCharacter == character,
                        onClick = { viewModel.update { it.copy(waveCharacter = character) } },
                        label = { Text(stringResource(waveCharacterLabel(character))) },
                        modifier = Modifier.testTag("wave-character-${character.name}"),
                    )
                }
            }
            Text(
                stringResource(R.string.settings_wave_character_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
            Text(stringResource(R.string.settings_wave_mood), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                WaveMood.entries.forEach { mood ->
                    FilterChip(
                        selected = settings.waveMood == mood,
                        onClick = { viewModel.update { it.copy(waveMood = mood) } },
                        label = { Text(stringResource(waveMoodLabel(mood))) },
                        modifier = Modifier.testTag("wave-mood-${mood.name}"),
                    )
                }
            }
            Text(
                stringResource(R.string.settings_wave_mood_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
            Text(stringResource(R.string.settings_wave_discovery), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_wave_discovery_value, (settings.waveDiscoveryShare * 100).roundToInt()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = settings.waveDiscoveryShare,
                onValueChange = { v -> viewModel.update { it.copy(waveDiscoveryShare = (v * 20).roundToInt() / 20f) } },
                valueRange = 0f..0.6f,
                steps = 11,
                modifier = Modifier.testTag("discovery-slider"),
            )
            Text(
                stringResource(R.string.settings_wave_discovery_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = viewModel::resetSkipStats, modifier = Modifier.padding(top = 24.dp)) {
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
        }
    }
}

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
            SwitchRow(
                stringResource(R.string.settings_background_animation),
                stringResource(R.string.settings_background_animation_summary),
                settings.backgroundAnimation,
                { v -> viewModel.update { it.copy(backgroundAnimation = v) } },
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
