package ru.bodro.music.ui.screens.wave

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.bodro.music.R
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.WaveMode
import ru.bodro.music.playback.PlayerUiState
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.app.LocalReactions
import ru.bodro.music.ui.theme.BodroCoral
import ru.bodro.music.ui.theme.BodroOrange
import ru.bodro.music.ui.theme.BodroViolet
import ru.bodro.music.ui.theme.BodroYellow
import ru.bodro.music.ui.theme.LocalPlayerColors
import ru.bodro.music.ui.theme.WaveTitleStyle

/** Режимы волны в ленте внизу экрана. */
val WaveModes = listOf(WaveMode.Default, WaveMode.Favorites, WaveMode.Discover, WaveMode.Popular)

@Composable
fun waveModeLabel(mode: WaveMode): String = stringResource(
    when (mode) {
        WaveMode.Default -> R.string.wave_mode_default
        WaveMode.Favorites -> R.string.wave_mode_favorites
        WaveMode.Discover -> R.string.wave_mode_discover
        WaveMode.Popular -> R.string.wave_mode_popular
        WaveMode.TrackMix -> R.string.mix_tracks
        WaveMode.AlbumMix -> R.string.mix_albums
        WaveMode.DecadeMix -> R.string.mix_decade
    },
)

@Composable
fun waveColors(): List<Color> {
    val player = LocalPlayerColors.current
    return if (player !=
        null
    ) {
        listOf(player.accent, BodroOrange, player.background, BodroViolet)
    } else {
        listOf(BodroYellow, BodroOrange, BodroCoral, BodroViolet)
    }
}

/** Экран «Моей волны»: облако, огромный заголовок исполнителя, жёлтая кнопка, капсула трека, режимы. */
@Composable
fun WaveScreen(onBack: () -> Unit, viewModel: WaveViewModel = hiltViewModel()) {
    val player by viewModel.player.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val waveIsPlaying = player.source.isWave && player.hasMedia

    var allGenres by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().testTag("wave-screen")) {
        WaveBackground(waveColors(), Modifier.fillMaxSize(), animate = player.isPlaying || !waveIsPlaying)
        // Без горизонтальной прокрутки: чипы переносятся на новые строки, экран целиком прокручивается вверх-вниз.
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(top = 64.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    if (waveIsPlaying) player.currentSong?.artist.orEmpty() else stringResource(R.string.wave_title),
                    style = WaveTitleStyle,
                    color = Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(24.dp))
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    WavePlayButton(
                        playing = waveIsPlaying && player.isPlaying,
                        loading = player.waveLoading,
                        onClick = {
                            if (waveIsPlaying) viewModel.togglePlay() else actions.startWave(filters.toSettings())
                        },
                    )
                    Spacer(Modifier.size(16.dp))
                    if (waveIsPlaying) TrackCapsule(player)
                }
                Spacer(Modifier.height(24.dp))
                ChipGroup(stringResource(R.string.wave_group_mood)) {
                    WaveModes.forEach { mode ->
                        WaveChip(waveModeLabel(mode), filters.mode == mode) {
                            viewModel.setMode(mode)
                            actions.startWave(filters.copy(mode = mode).toSettings())
                        }
                    }
                }
                if (filters.genres.isNotEmpty()) {
                    val shown = if (allGenres) filters.genres else filters.genres.take(GENRES_SHOWN)
                    ChipGroup(stringResource(R.string.wave_group_genre)) {
                        // Выбранный жанр виден, даже если он не в первых восьми.
                        (shown + listOfNotNull(filters.genre?.takeIf { it !in shown })).forEach { genre ->
                            WaveChip(genre, filters.genre == genre) {
                                viewModel.setGenre(genre)
                                actions.startWave(filters.copy(genre = if (filters.genre == genre) null else genre).toSettings())
                            }
                        }
                        if (filters.genres.size > GENRES_SHOWN) {
                            WaveChip(stringResource(if (allGenres) R.string.action_show_less else R.string.wave_more_genres), false) {
                                allGenres = !allGenres
                            }
                        }
                    }
                }
                ChipGroup(stringResource(R.string.wave_group_decade), Modifier.padding(bottom = 24.dp)) {
                    filters.decades.forEach { decade ->
                        WaveChip(stringResource(R.string.decade_label, decade), filters.decade == decade) {
                            viewModel.setDecade(decade)
                            actions.startWave(filters.copy(decade = if (filters.decade == decade) null else decade).toSettings())
                        }
                    }
                }
            }
            IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
            }
        }
    }
}

/** Группа чипов с подписью; чипы переносятся на следующую строку. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 2.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

private const val GENRES_SHOWN = 8

@Composable
fun WaveChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.White.copy(alpha = 0.12f),
            labelColor = Color.White,
            selectedContainerColor = BodroYellow,
            selectedLabelColor = Color.Black,
        ),
        border = null,
    )
}

@Composable
fun WavePlayButton(playing: Boolean, loading: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = CircleShape, color = BodroYellow, modifier = modifier.size(76.dp).testTag("wave-play")) {
        Box(contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(36.dp), color = Color.Black, strokeWidth = 3.dp)
            } else {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
                    tint = Color.Black,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
    }
}

/** Капсула с названием трека и сердцем. */
@Composable
fun TrackCapsule(player: PlayerUiState, modifier: Modifier = Modifier) {
    val song = player.currentSong ?: return
    val actions = LocalAppActions.current
    val reaction = LocalReactions.current.of(song)
    Row(
        modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable { actions.toggleLike(song) }
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            song.title,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.size(8.dp))
        Icon(
            if (reaction == Reaction.Liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = stringResource(if (reaction == Reaction.Liked) R.string.action_unlike else R.string.action_like),
            tint = if (reaction == Reaction.Liked) BodroYellow else Color.White,
        )
    }
}
