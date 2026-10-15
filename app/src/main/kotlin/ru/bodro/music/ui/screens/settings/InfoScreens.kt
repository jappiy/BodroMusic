package ru.bodro.music.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.bodro.music.BuildConfig
import ru.bodro.music.R
import ru.bodro.music.core.domain.changelog.ChangelogRelease
import ru.bodro.music.core.model.ServerInfo
import ru.bodro.music.core.model.ServerKind
import ru.bodro.music.core.subsonic.ServerSelection
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.repository.ChangelogRepository
import ru.bodro.music.ui.components.BodroTopBar

// region История версий

@HiltViewModel
class VersionsViewModel @Inject constructor(changelog: ChangelogRepository) : ViewModel() {
    private val _releases = MutableStateFlow<List<ChangelogRelease>>(emptyList())
    val releases: StateFlow<List<ChangelogRelease>> = _releases.asStateFlow()

    init {
        viewModelScope.launch { _releases.value = changelog.releases() }
    }
}

@Composable
fun VersionsScreen(onBack: () -> Unit, viewModel: VersionsViewModel = hiltViewModel()) {
    val releases by viewModel.releases.collectAsStateWithLifecycle()
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.settings_versions), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).testTag("versions-screen"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.versions_current), style = MaterialTheme.typography.labelLarge)
                        Text(
                            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.testTag("current-version"),
                        )
                        Text(
                            stringResource(R.string.versions_build_date, BuildConfig.BUILD_TIME),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(stringResource(R.string.versions_commit, BuildConfig.GIT_HASH), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            itemsIndexed(releases, key = { _, r -> r.version }) { index, release ->
                var expanded by rememberSaveable(release.version) { mutableStateOf(index == 0) }
                Card(modifier = Modifier.fillMaxWidth().testTag("release-${release.version}")) {
                    Column(Modifier.clickable { expanded = !expanded }.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(release.version, style = MaterialTheme.typography.titleLarge)
                                release.date?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                        }
                        AnimatedVisibility(expanded) {
                            Column(Modifier.padding(top = 8.dp)) {
                                release.sections.forEach { section ->
                                    Text(
                                        section.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                    section.items.forEach { item ->
                                        Text(
                                            "• $item",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// endregion

// region О приложении

data class AboutUi(val selection: ServerSelection? = null, val info: ServerInfo? = null, val username: String? = null)

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val connection: ConnectionManager,
    private val client: SubsonicClient,
) : ViewModel() {
    private val _state = MutableStateFlow(AboutUi(connection.selection.value, username = connection.activeAccount?.username))
    val state: StateFlow<AboutUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val info = runCatching { client.handshake() }.getOrNull()
            _state.value = AboutUi(connection.selection.value, info, connection.activeAccount?.username)
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit, viewModel: AboutViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.settings_about), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            InfoRow(
                stringResource(R.string.about_app_version),
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_HASH}",
            )
            state.username?.let { InfoRow(stringResource(R.string.login_username), it) }
            state.selection?.let { s ->
                InfoRow(
                    stringResource(R.string.about_server_address),
                    "${s.baseUrl} (${stringResource(if (s.kind == ServerKind.Local) R.string.server_lan else R.string.server_wan)})",
                )
            }
            val info = state.info
            InfoRow(stringResource(R.string.about_server_type), info?.type ?: "—")
            InfoRow(stringResource(R.string.about_navidrome_version_label), info?.serverVersion ?: "—")
            InfoRow(stringResource(R.string.about_api_version), info?.apiVersion ?: "—")
            InfoRow(
                stringResource(R.string.about_opensubsonic),
                when {
                    info == null -> "—"
                    info.openSubsonic -> stringResource(R.string.yes) +
                        if (info.extensions.isNotEmpty()) ": " + info.extensions.joinToString { it.name } else ""
                    else -> stringResource(R.string.no)
                },
            )
            Text(
                stringResource(R.string.about_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    ListItem(headlineContent = { Text(value) }, overlineContent = { Text(label) })
}

// endregion
