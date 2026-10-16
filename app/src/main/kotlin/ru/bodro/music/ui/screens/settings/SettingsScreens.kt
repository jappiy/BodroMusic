package ru.bodro.music.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import ru.bodro.music.R
import ru.bodro.music.core.domain.player.StreamQuality
import ru.bodro.music.core.domain.player.TranscodeCodec
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.data.settings.ThemeMode
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.util.formatBytes

@Composable
fun SettingsScreen(
    onOpenAccounts: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenWave: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenCleanup: () -> Unit,
    onOpenBlocklist: () -> Unit,
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
    else -> stringResource(R.string.quality_kbps, quality.maxBitRate ?: 0)
}

@Composable
fun PlaybackSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val used by viewModel.cacheUsed.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshCacheSize() }
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.settings_playback), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            ChoiceGroup(stringResource(R.string.settings_quality_wifi), StreamQuality.entries, settings.qualityWifi, {
                qualityLabel(it)
            }) { q ->
                viewModel.update { it.copy(qualityWifi = q) }
            }
            ChoiceGroup(stringResource(R.string.settings_quality_mobile), StreamQuality.entries, settings.qualityMobile, {
                qualityLabel(it)
            }) { q ->
                viewModel.update { it.copy(qualityMobile = q) }
            }
            ChoiceGroup(stringResource(R.string.settings_codec), TranscodeCodec.entries, settings.transcodeCodec, {
                it.format.uppercase()
            }) { c ->
                viewModel.update { it.copy(transcodeCodec = c) }
            }
            ChoiceGroup(
                stringResource(R.string.settings_cache_size),
                AppSettings.CACHE_SIZES_MB,
                settings.cacheSizeMb,
                { formatBytes(it.toLong() * 1024 * 1024) },
            ) { size -> viewModel.update { it.copy(cacheSizeMb = size) } }
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

@Composable
fun WaveSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.settings_wave), onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
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
                stringResource(R.string.settings_dynamic_color),
                stringResource(R.string.settings_dynamic_color_summary),
                settings.dynamicColor,
                { v -> viewModel.update { it.copy(dynamicColor = v) } },
                enabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
            )
        }
    }
}
