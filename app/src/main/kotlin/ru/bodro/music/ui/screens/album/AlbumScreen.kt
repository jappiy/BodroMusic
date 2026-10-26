package ru.bodro.music.ui.screens.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.domain.reaction.ReactionTarget
import ru.bodro.music.core.model.AlbumDetails
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.data.repository.ReactionRepository
import ru.bodro.music.ui.app.AlbumRoute
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.app.LocalNavigator
import ru.bodro.music.ui.app.LocalReactions
import ru.bodro.music.ui.components.CoverImage
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.components.SongRow
import ru.bodro.music.ui.components.heroCoverSize
import ru.bodro.music.ui.image.CoverColors
import ru.bodro.music.ui.image.CoverPaletteRepository
import ru.bodro.music.ui.theme.Contrast
import ru.bodro.music.ui.util.toUiError
import ru.bodro.music.ui.util.totalDurationText
import ru.bodro.music.ui.util.tracksText

@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val library: LibraryRepository,
    private val reactions: ReactionRepository,
    private val palette: CoverPaletteRepository,
) : ViewModel() {
    private val albumId = savedState.toRoute<AlbumRoute>().id
    private val _state = MutableStateFlow<LoadState<AlbumDetails>>(LoadState.Loading)
    val state: StateFlow<LoadState<AlbumDetails>> = _state.asStateFlow()
    private val _colors = MutableStateFlow<CoverColors?>(null)
    val colors: StateFlow<CoverColors?> = _colors.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = LoadState.Loading
            runCatching { library.album(albumId) }
                .onSuccess { details ->
                    _state.value = LoadState.Content(details)
                    _colors.value = runCatching { palette.colorsFor(details.album.coverArt) }.getOrNull()
                }
                .onFailure { _state.value = LoadState.Error(it.toUiError()) }
        }
    }

    fun toggleAlbumLike(current: Boolean) {
        viewModelScope.launch { reactions.setStarred(ReactionTarget.Album, albumId, !current) }
    }
}

@Composable
fun AlbumScreen(onBack: () -> Unit, viewModel: AlbumViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors by viewModel.colors.collectAsStateWithLifecycle()
    when (val s = state) {
        LoadState.Loading -> LoadingState()
        is LoadState.Error -> ErrorState(s.error, onRetry = viewModel::load)
        is LoadState.Content -> AlbumContent(s.data, colors, onBack, viewModel::toggleAlbumLike)
    }
}

@Composable
private fun AlbumContent(details: AlbumDetails, colors: CoverColors?, onBack: () -> Unit, onToggleLike: (Boolean) -> Unit) {
    val actions = LocalAppActions.current
    val navigator = LocalNavigator.current
    val album = details.album
    val songs = details.songs
    val liked = LocalReactions.current.albumLiked(album.id, album.starred)
    val source = QueueSource.AlbumSource(album.id, album.name)
    val gradientTop = colors?.background ?: MaterialTheme.colorScheme.surfaceContainerHigh
    // Шапка — градиент от цвета обложки к фону темы: кнопка «назад» стоит на верхнем цвете, текст и кнопки — в нижней
    // части. Цвета считаются по фону именно там (тёмная обложка в светлой теме больше не прячет тёмные иконки).
    val gradientBottom = MaterialTheme.colorScheme.background
    val topSurfaces = Contrast.gradient(gradientTop, gradientBottom, 0f, TOP_ZONE)
    val lowerSurfaces = Contrast.gradient(gradientTop, gradientBottom, LOWER_ZONE, 1f)
    val topContent = Contrast.pick(MaterialTheme.colorScheme.onSurface, topSurfaces, Contrast.TEXT)
    val lowerContent = Contrast.pick(MaterialTheme.colorScheme.onSurface, lowerSurfaces, Contrast.TEXT)
    val lowerSecondary = Contrast.pick(MaterialTheme.colorScheme.onSurfaceVariant, lowerSurfaces, Contrast.TEXT)
    val lowerAccent = MaterialTheme.colorScheme.primary.let { if (Contrast.worst(it, lowerSurfaces) >= Contrast.TEXT) it else lowerContent }
    val multiDisc = songs.mapNotNull { it.discNumber }.distinct().size > 1

    LazyColumn(Modifier.fillMaxSize().testTag("album-screen"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(gradientTop, gradientBottom)))) {
                Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth()) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                tint = topContent,
                            )
                        }
                    }
                    CoverImage(album.coverArt, Modifier.size(heroCoverSize(240.dp)), sizeHint = 600.dp, shape = RoundedCornerShape(20.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        album.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = lowerContent,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp).testTag("album-title"),
                    )
                    album.artist?.let { artist ->
                        Text(
                            artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = lowerAccent,
                            modifier = Modifier.clickable(enabled = album.artistId != null) {
                                album.artistId?.let(navigator::openArtist)
                            }.padding(4.dp),
                        )
                    }
                    Text(
                        listOfNotNull(
                            album.year?.toString(),
                            album.genre,
                            tracksText(songs.size),
                            totalDurationText(album.duration),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = lowerSecondary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onToggleLike(liked) }, modifier = Modifier.testTag("album-like")) {
                            Icon(
                                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = stringResource(if (liked) R.string.action_unlike else R.string.action_like),
                                tint = if (liked) lowerAccent else lowerContent,
                            )
                        }
                        Button(onClick = { actions.play(songs, 0, source) }, modifier = Modifier.testTag("album-play")) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Text(stringResource(R.string.action_play))
                        }
                        FilledTonalButton(onClick = { actions.play(songs, 0, source, shuffle = true) }) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null)
                            Text(stringResource(R.string.action_shuffle))
                        }
                        IconButton(onClick = { actions.addToPlaylist(songs) }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.PlaylistAdd,
                                contentDescription = stringResource(R.string.menu_add_to_playlist),
                                tint = lowerContent,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        itemsIndexed(songs, key = { index, song -> "${song.id}-$index" }) { index, song ->
            Column {
                if (multiDisc && (index == 0 || songs[index - 1].discNumber != song.discNumber)) {
                    Text(
                        stringResource(R.string.album_disc, song.discNumber ?: 1),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                SongRow(song, onClick = { actions.play(songs, index, source) }, number = song.track ?: (index + 1))
            }
        }
    }
}

/** Доли высоты шапки альбома: «назад» — верхние 15 %, название, исполнитель и кнопки — ниже 55 %. */
private const val TOP_ZONE = 0.15f
private const val LOWER_ZONE = 0.55f
