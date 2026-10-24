package ru.bodro.music.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.bodro.music.R
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.RepeatMode
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSeed
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.playback.PlayerUiState
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.app.LocalNavigator
import ru.bodro.music.ui.app.LocalReactions
import ru.bodro.music.ui.components.CoverImage
import ru.bodro.music.ui.components.CoverKind
import ru.bodro.music.ui.screens.search.DiscoverCallbacks
import ru.bodro.music.ui.theme.LocalPlayerColors
import ru.bodro.music.ui.util.formatMs

private enum class PlayerSheet { None, Queue, Lyrics, Sleep }

/**
 * Полноэкранный плеер: фон и акцент в цвет обложки, waveform-сикбар.
 * Дизлайк и лайк — по краям от кнопок переключения; внизу единый блок «повтор / перемешивание / текст / очередь».
 * Таймер сна и волна по треку — в меню «⋮».
 *
 * Вёрстка адаптивная ([playerLayout]): в портрете — колонка, обложка = min(ширина, доля высоты); в ландшафте и на
 * широком экране — две колонки: слева обложка, справа название, перемотка и кнопки. Если места не хватает,
 * содержимое прокручивается, а не обрезается. Свайп вниз, когда прокручивать вверх некуда, — свернуть.
 */
@Composable
fun FullPlayerScreen(
    onCollapse: () -> Unit,
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
    viewModel: PlayerViewModel = hiltViewModel(),
    extrasViewModel: PlayerExtrasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val extras by extrasViewModel.extras.collectAsStateWithLifecycle()
    val requests by extrasViewModel.requests.collectAsStateWithLifecycle()
    val canDownload by extrasViewModel.canDownload.collectAsStateWithLifecycle()
    val appSettings by extrasViewModel.settings.collectAsStateWithLifecycle()
    val poolIds by viewModel.poolSongIds.collectAsStateWithLifecycle()
    val song = state.currentSong ?: return
    val actions = LocalAppActions.current
    val colors = LocalPlayerColors.current
    val background = colors?.background ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val palette = PlayerPalette(
        background = background,
        content = colors?.onBackground ?: MaterialTheme.colorScheme.onSurface,
        accent = colors?.accent ?: MaterialTheme.colorScheme.primary,
    )
    var sheet by rememberSaveable { mutableStateOf(PlayerSheet.None) }
    val collapseOnPull = rememberCollapseOnPull(onCollapse)
    val navigator = LocalNavigator.current
    val similarTitle = stringResource(R.string.player_similar_source, song.title)
    LaunchedEffect(extrasViewModel) { extrasViewModel.messages.collect { actions.showMessage(it) } }
    val artistLiked = extras.artist.valueOrNull?.let { LocalReactions.current.artistLiked(it.id, it.starred) } == true
    val extrasActions = remember(extrasViewModel, song.id) {
        PlayerExtrasActions(
            onOpenArtist = navigator::openArtist,
            onToggleArtistLike = extrasViewModel::toggleArtistLike,
            callbacks = DiscoverCallbacks(
                onDownload = extrasViewModel::download,
                onDownloadArtist = {},
                onSelectArtist = {},
                onPlay = { id -> extrasViewModel.withSong(id) { actions.play(listOf(it), 0, extrasSource(similarTitle)) } },
                onRetry = {},
                onShowMore = {},
            ),
            onTrackMenu = { track ->
                val id = track.librarySongId ?: requests[track.key]?.songId
                if (id != null) extrasViewModel.withSong(id) { actions.showSongMenu(it) }
            },
            onDownloadAll = extrasViewModel::downloadAll,
            onAddTo = { playlist -> extrasViewModel.addTo(playlist, song) },
            onAllPlaylists = { actions.addToPlaylist(listOf(song)) },
            onSleepTimer = { sheet = PlayerSheet.Sleep },
            onQuality = extrasViewModel::setQuality,
            onRetry = extrasViewModel::retry,
        )
    }
    val extrasContent: LazyListScope.() -> Unit = {
        playerExtras(extras, requests, canDownload, artistLiked, state.sleepTimer != null, appSettings.quality, palette, extrasActions)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(background, Color.Black.copy(alpha = 0.92f).compositeOver(background))))
            .testTag("full-player"),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
            val layout = playerLayout(maxWidth, maxHeight)
            val header: @Composable () -> Unit = {
                PlayerHeader(
                    state,
                    palette,
                    onCollapse = onCollapse,
                    onSleep = { sheet = PlayerSheet.Sleep },
                    onWave = { actions.startWave(WaveSettings(seed = WaveSeed.Track(song.id, song.title, song.artistId, song.genre))) },
                    onSongActions = { actions.showSongMenu(song) },
                    onQueue = { sheet = PlayerSheet.Queue },
                )
            }
            val cover: @Composable () -> Unit = {
                PlayerCover(
                    song,
                    layout.coverSize,
                    sharedScope,
                    visibilityScope,
                    onNext = viewModel::next,
                    onPrevious = viewModel::previous,
                )
            }
            val controls: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    PlayerInfo(song, isNew = song.id in poolIds, palette)
                    Spacer(Modifier.height(12.dp))
                    PlayerSeek(state, palette, onSeek = viewModel::seekTo)
                    Spacer(Modifier.height(16.dp))
                    PlayerControls(state, palette, viewModel)
                    Spacer(Modifier.height(20.dp))
                    PlayerBottomBar(
                        state,
                        palette,
                        onRepeat = viewModel::cycleRepeat,
                        onShuffle = viewModel::toggleShuffle,
                        onLyrics = { sheet = PlayerSheet.Lyrics },
                        onQueue = { sheet = PlayerSheet.Queue },
                    )
                }
            }
            if (layout.twoPane) {
                Column(Modifier.fillMaxSize()) {
                    header()
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        cover()
                        // Справа — управление и прокручиваемые блоки (похожие, популярные, плейлисты).
                        LazyColumn(Modifier.weight(1f).fillMaxHeight().nestedScroll(collapseOnPull).testTag("player-scroll")) {
                            item(key = "controls") { Box(Modifier.padding(vertical = 8.dp)) { controls() } }
                            extrasContent()
                        }
                    }
                }
            } else {
                val screenHeight = maxHeight
                val list = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize().nestedScroll(collapseOnPull).testTag("player-scroll"), state = list) {
                    // Основной блок — на высоту экрана (блоки распределены по ней); ниже — похожие, популярные, плейлисты.
                    item(key = "main") {
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = screenHeight),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            header()
                            cover()
                            Box(Modifier.padding(top = 16.dp, bottom = 12.dp)) { controls() }
                        }
                    }
                    extrasContent()
                }
                // Основной блок ушёл за экран — внизу мини-плеер.
                val mainGone by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
                AnimatedVisibility(
                    visible = mainGone,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    PlayerMiniBar(state, palette, onTogglePlay = viewModel::togglePlay)
                }
            }
        }
    }

    when (sheet) {
        PlayerSheet.Queue -> QueueSheet(
            state = state,
            onDismiss = { sheet = PlayerSheet.None },
            onSkipTo = viewModel::skipTo,
            onMove = viewModel::moveItem,
            onRemove = viewModel::removeItem,
        )
        PlayerSheet.Lyrics -> LyricsSheet(viewModel, state.positionMs, onSeek = viewModel::seekTo, onDismiss = { sheet = PlayerSheet.None })
        PlayerSheet.Sleep -> SleepTimerDialog(
            current = state.sleepTimer,
            onSelect = {
                viewModel.setSleepTimer(it)
                sheet = PlayerSheet.None
            },
            onDismiss = { sheet = PlayerSheet.None },
        )
        PlayerSheet.None -> Unit
    }
}

/** Цвета плеера из обложки. */
@Immutable
data class PlayerPalette(val background: Color, val content: Color, val accent: Color)

/** Раскладка плеера под размер окна. */
data class PlayerLayout(val twoPane: Boolean, val coverSize: Dp)

/**
 * Две колонки — когда окно шире высоты и ширины хватает (ландшафт телефона, разложенный экран боком).
 * Обложка в колонке = min(ширина, доля высоты): обычный телефон — 50 % высоты, почти квадратный экран — 42 %,
 * чтобы название, перемотка и кнопки помещались без прокрутки.
 */
fun playerLayout(width: Dp, height: Dp): PlayerLayout {
    val twoPane = width > height * TWO_PANE_RATIO && width >= TWO_PANE_MIN_WIDTH
    val cover = if (twoPane) {
        minOf(height - HEADER_HEIGHT - 16.dp, (width - 32.dp) * TWO_PANE_COVER_SHARE, MAX_COVER)
    } else {
        minOf(width, height * (if (width / height > NEAR_SQUARE) NEAR_SQUARE_COVER_SHARE else PORTRAIT_COVER_SHARE), MAX_COVER)
    }
    return PlayerLayout(twoPane, cover.coerceAtLeast(MIN_COVER))
}

private const val TWO_PANE_RATIO = 1.05f
private const val NEAR_SQUARE = 0.75f
private const val TWO_PANE_COVER_SHARE = 0.45f
private const val NEAR_SQUARE_COVER_SHARE = 0.42f
private const val PORTRAIT_COVER_SHARE = 0.5f
private val TWO_PANE_MIN_WIDTH = 520.dp
private val HEADER_HEIGHT = 56.dp
private val MAX_COVER = 560.dp
private val MIN_COVER = 120.dp

/** Свайп вниз, когда прокручивать вверх уже некуда, — свернуть плеер (прокрутке содержимого не мешает). */
@Composable
private fun rememberCollapseOnPull(onCollapse: () -> Unit): NestedScrollConnection {
    val threshold = with(LocalDensity.current) { 120.dp.toPx() }
    val collapse by rememberUpdatedState(onCollapse)
    return remember(threshold) {
        object : NestedScrollConnection {
            var pulled = 0f

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    pulled = if (available.y > 0f) pulled + available.y else 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pulled > threshold) collapse()
                pulled = 0f
                return Velocity.Zero
            }
        }
    }
}

@Composable
private fun PlayerHeader(
    state: PlayerUiState,
    palette: PlayerPalette,
    onCollapse: () -> Unit,
    onSleep: () -> Unit,
    onWave: () -> Unit,
    onSongActions: () -> Unit,
    onQueue: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCollapse, modifier = Modifier.testTag("collapse-player")) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.action_collapse), tint = palette.content)
        }
        Text(
            sourceTitle(state.source),
            style = MaterialTheme.typography.labelLarge,
            color = palette.content.copy(alpha = 0.75f),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onQueue, modifier = Modifier.testTag("player-header-queue")) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = stringResource(R.string.player_queue), tint = palette.content)
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("player-menu")) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more), tint = palette.content)
            }
            PlayerMenu(
                expanded = menuOpen,
                sleepActive = state.sleepTimer != null,
                onDismiss = { menuOpen = false },
                onSleep = onSleep,
                onWave = onWave,
                onSongActions = onSongActions,
            )
        }
    }
}

@Composable
private fun PlayerCover(
    song: Song,
    size: Dp,
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    var coverDrag by remember { mutableFloatStateOf(0f) }
    with(sharedScope) {
        CoverImage(
            song.coverArt,
            Modifier
                .sharedElement(rememberSharedContentState("player-cover"), visibilityScope)
                .size(size)
                .shadow(24.dp, RoundedCornerShape(20.dp))
                .pointerInput(song.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { coverDrag = 0f },
                        onDragEnd = {
                            val threshold = 64.dp.toPx()
                            if (coverDrag < -threshold) {
                                onNext()
                            } else if (coverDrag > threshold) {
                                onPrevious()
                            }
                        },
                    ) { _, amount -> coverDrag += amount }
                }
                .testTag("player-cover"),
            sizeHint = 1000.dp,
            shape = RoundedCornerShape(20.dp),
            kind = CoverKind.Song,
        )
    }
}

/** Название, исполнитель и альбом — на всю ширину колонки. */
@Composable
private fun PlayerInfo(song: Song, isNew: Boolean, palette: PlayerPalette) {
    val navigator = LocalNavigator.current
    Column(Modifier.fillMaxWidth()) {
        if (isNew) {
            // Трек из пула «На пробу»: в библиотеку его переведут лайк, два полных прослушивания или плейлист.
            Text(
                stringResource(R.string.player_new_badge),
                style = MaterialTheme.typography.labelMedium,
                color = palette.background,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .background(palette.accent, MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag("player-new-badge"),
            )
        }
        Text(
            song.title,
            style = MaterialTheme.typography.headlineSmall,
            color = palette.content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("player-title"),
        )
        Text(
            song.artist.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = palette.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(enabled = song.artistId != null) { song.artistId?.let(navigator::openArtist) },
        )
        song.album?.let { album ->
            Text(
                album,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.content.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = song.albumId != null) { song.albumId?.let(navigator::openAlbum) },
            )
        }
    }
}

@Composable
private fun PlayerSeek(state: PlayerUiState, palette: PlayerPalette, onSeek: (Long) -> Unit) {
    val song = state.currentSong ?: return
    Column(Modifier.fillMaxWidth()) {
        WaveformSeekBar(
            songId = song.id,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            onSeek = onSeek,
            activeColor = palette.accent,
            inactiveColor = palette.content.copy(alpha = 0.22f),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatMs(state.positionMs), style = MaterialTheme.typography.labelMedium, color = palette.content.copy(alpha = 0.75f))
            Spacer(Modifier.weight(1f))
            Text(
                "−" + formatMs((state.durationMs - state.positionMs).coerceAtLeast(0)),
                style = MaterialTheme.typography.labelMedium,
                color = palette.content.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun PlayerControls(state: PlayerUiState, palette: PlayerPalette, viewModel: PlayerViewModel) {
    val song = state.currentSong ?: return
    val actions = LocalAppActions.current
    val reaction = LocalReactions.current.of(song)
    val content = palette.content
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        val disliked = reaction == Reaction.Disliked
        ReactionButton(
            active = disliked,
            onClick = { actions.toggleDislike(song) },
            description = stringResource(if (disliked) R.string.action_undislike else R.string.action_dislike),
            content = content,
            buttonTag = "player-dislike",
            activeColor = DislikeBlue,
            iconTag = if (disliked) "icon-disliked" else "icon-not-disliked",
        ) { tint, description ->
            Icon(painterResource(R.drawable.ic_heart_broken), contentDescription = description, tint = tint)
        }
        IconButton(onClick = viewModel::previous, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Rounded.SkipPrevious,
                contentDescription = stringResource(R.string.action_previous),
                tint = content,
                modifier = Modifier.size(40.dp),
            )
        }
        Surface(
            onClick = viewModel::togglePlay,
            shape = RoundedCornerShape(28.dp),
            color = palette.accent,
            modifier = Modifier.size(80.dp).testTag("player-play"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (state.isBuffering) CircularProgressIndicator(Modifier.size(72.dp), color = palette.background, strokeWidth = 2.dp)
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play),
                    tint = palette.background,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        IconButton(onClick = viewModel::next, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.action_next),
                tint = content,
                modifier = Modifier.size(40.dp),
            )
        }
        val liked = reaction == Reaction.Liked
        ReactionButton(
            active = liked,
            onClick = { actions.toggleLike(song) },
            description = stringResource(if (liked) R.string.action_unlike else R.string.action_like),
            content = content,
            buttonTag = "player-like",
            activeColor = LikeRed,
            iconTag = if (liked) "icon-liked" else "icon-not-liked",
        ) { tint, description ->
            Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, contentDescription = description, tint = tint)
        }
    }
}

/** Мини-плеер внизу полноэкранного плеера, когда основной блок прокручен за экран: обложка, название, ♡, пауза. */
@Composable
private fun PlayerMiniBar(state: PlayerUiState, palette: PlayerPalette, onTogglePlay: () -> Unit) {
    val song = state.currentSong ?: return
    val actions = LocalAppActions.current
    val liked = LocalReactions.current.of(song) == Reaction.Liked
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .shadow(12.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(palette.background)
            .padding(8.dp)
            .testTag("player-mini-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(song.coverArt, Modifier.size(44.dp), sizeHint = 88.dp, shape = RoundedCornerShape(10.dp), kind = CoverKind.Song)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                song.title,
                color = palette.content,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist.orEmpty(),
                color = palette.content.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        IconButton(onClick = { actions.toggleLike(song) }) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = stringResource(if (liked) R.string.action_unlike else R.string.action_like),
                tint = if (liked) LikeRed else palette.content,
            )
        }
        IconButton(onClick = onTogglePlay) {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play),
                tint = palette.content,
            )
        }
    }
}

/** Нижняя панель — единый блок: повтор, перемешивание, текст песни, очередь. */
@Composable
private fun PlayerBottomBar(
    state: PlayerUiState,
    palette: PlayerPalette,
    onRepeat: () -> Unit,
    onShuffle: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
) {
    val content = palette.content
    Row(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(content.copy(alpha = 0.10f))
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .testTag("player-bottom-bar"),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomAction(
            if (state.repeatMode == RepeatMode.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            stringResource(R.string.action_repeat),
            content,
            active = state.repeatMode != RepeatMode.Off,
            tag = "player-repeat",
            onClick = onRepeat,
        )
        BottomAction(
            Icons.Rounded.Shuffle,
            stringResource(R.string.action_shuffle),
            content,
            active = state.shuffle,
            tag = "player-shuffle",
            onClick = onShuffle,
        )
        BottomAction(Icons.Rounded.Lyrics, stringResource(R.string.player_lyrics), content, tag = "open-lyrics", onClick = onLyrics)
        BottomAction(
            Icons.AutoMirrored.Rounded.QueueMusic,
            stringResource(R.string.player_queue),
            content,
            tag = "open-queue",
            onClick = onQueue,
        )
    }
}

@Composable
private fun BottomAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    active: Boolean = false,
    tag: String? = null,
    onClick: () -> Unit,
) {
    val accent = LocalPlayerColors.current?.accent ?: MaterialTheme.colorScheme.primary
    IconButton(onClick = onClick, modifier = if (tag != null) Modifier.testTag(tag) else Modifier) {
        Icon(icon, contentDescription = label, tint = if (active) accent else tint.copy(alpha = 0.6f))
    }
}

/** Лайк/дизлайк: поставленная отметка — цветная обводка и подложка (лайк красный, дизлайк синий). */
@Composable
private fun ReactionButton(
    active: Boolean,
    onClick: () -> Unit,
    description: String,
    content: Color,
    buttonTag: String,
    iconTag: String,
    activeColor: Color,
    icon: @Composable (tint: Color, description: String) -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(if (active) Modifier.background(activeColor.copy(alpha = 0.18f)).border(2.dp, activeColor, CircleShape) else Modifier)
            .clickable(onClick = onClick, role = Role.Button)
            .testTag(buttonTag),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.testTag(iconTag)) { icon(if (active) activeColor else content.copy(alpha = 0.8f), description) }
    }
}

private val LikeRed = Color(0xFFE53935)
private val DislikeBlue = Color(0xFF1E88E5)

@Composable
private fun PlayerMenu(
    expanded: Boolean,
    sleepActive: Boolean,
    onDismiss: () -> Unit,
    onSleep: () -> Unit,
    onWave: () -> Unit,
    onSongActions: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        PlayerMenuItem(
            Icons.Rounded.Bedtime,
            if (sleepActive) R.string.player_sleep_timer_on else R.string.player_sleep_timer,
            "open-sleep",
            onDismiss,
            onSleep,
        )
        PlayerMenuItem(Icons.Rounded.Waves, R.string.player_wave_by_track, "open-wave", onDismiss, onWave)
        PlayerMenuItem(Icons.Rounded.MoreHoriz, R.string.player_song_actions, "open-song-menu", onDismiss, onSongActions)
    }
}

@Composable
private fun PlayerMenuItem(icon: ImageVector, label: Int, tag: String, onDismiss: () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = {
            onDismiss()
            onClick()
        },
        modifier = Modifier.testTag(tag),
    )
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f,
    )
}

@Composable
private fun sourceTitle(source: QueueSource): String = when (source) {
    is QueueSource.AlbumSource -> source.title
    is QueueSource.PlaylistSource -> source.title
    is QueueSource.ArtistSource -> source.title
    QueueSource.LikedSource -> stringResource(R.string.liked_title)
    is QueueSource.WaveSource -> stringResource(R.string.wave_title)
    is QueueSource.CustomSource -> source.title ?: stringResource(R.string.player_now_playing)
}
