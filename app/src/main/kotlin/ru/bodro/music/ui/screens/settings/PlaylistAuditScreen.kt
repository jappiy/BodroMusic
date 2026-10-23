package ru.bodro.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import ru.bodro.music.R
import ru.bodro.music.core.downloads.AuditPlaylist
import ru.bodro.music.core.downloads.PlaylistAudit
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.components.SectionHeader
import ru.bodro.music.ui.util.toUiError

/** Отчёт администратору: чьи плейлисты на сервере и какие стоит проверить. Приложение ничего не переносит само. */
@HiltViewModel
class PlaylistAuditViewModel @Inject constructor(private val downloads: DownloadsRepository) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<PlaylistAudit>>(LoadState.Loading)
    val state: StateFlow<LoadState<PlaylistAudit>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = runCatching { downloads.playlistAudit() }.fold({ LoadState.Content(it) }, { LoadState.Error(it.toUiError()) })
        }
    }
}

@Composable
fun PlaylistAuditScreen(onBack: () -> Unit, viewModel: PlaylistAuditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.playlist_audit_title), onBack) }) { padding ->
        when (val s = state) {
            LoadState.Loading -> LoadingState(Modifier.padding(padding))
            is LoadState.Error -> ErrorState(s.error, viewModel::load, Modifier.padding(padding))
            is LoadState.Content -> {
                val audit = s.data
                val review = audit.playlists.filter { it.reasons.isNotEmpty() }
                val fine = audit.playlists.filter { it.reasons.isEmpty() }
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding).testTag("playlist-audit"),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item(key = "summary") {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.playlist_audit_hint), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                audit.byOwner.entries.joinToString(" · ") { "${it.key}: ${it.value}" },
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            if (audit.admins.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.playlist_audit_admins, audit.admins.joinToString()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (review.isNotEmpty()) {
                        item(key = "h-review") { SectionHeader(stringResource(R.string.playlist_audit_review, review.size)) }
                        items(review, key = { "r-${it.id}" }) { AuditRow(it) }
                    }
                    if (fine.isNotEmpty()) {
                        item(key = "h-fine") { SectionHeader(stringResource(R.string.playlist_audit_fine)) }
                        items(fine, key = { "f-${it.id}" }) { AuditRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditRow(playlist: AuditPlaylist) {
    val reasons = playlist.reasons.map { reason ->
        stringResource(
            when (reason) {
                "no_owner" -> R.string.playlist_audit_no_owner
                "yandex_export" -> R.string.playlist_audit_yandex
                "smart_file" -> R.string.playlist_audit_smart
                "explo" -> R.string.playlist_audit_explo
                else -> R.string.playlist_audit_imported
            },
        )
    }
    ListItem(
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    listOf(
                        stringResource(R.string.playlist_owner, playlist.owner ?: "—"),
                        stringResource(if (playlist.public) R.string.playlist_public else R.string.playlist_private),
                        playlist.songCount.toString(),
                    ).joinToString(" · "),
                )
                reasons.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                playlist.path?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
        },
    )
}
