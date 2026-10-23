package ru.bodro.music.ui.screens.home

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.bodro.music.R
import ru.bodro.music.core.domain.wave.WaveCharacter
import ru.bodro.music.core.domain.wave.WaveMood
import ru.bodro.music.core.domain.wave.WavePreset
import ru.bodro.music.core.downloads.ArtistFacts
import ru.bodro.music.core.downloads.WaveModeInfo
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.playback.PlayerUiState
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.app.LocalNavigator
import ru.bodro.music.ui.app.LocalReactions
import ru.bodro.music.ui.components.CoverImage
import ru.bodro.music.ui.components.CoverKind
import ru.bodro.music.ui.screens.settings.waveCharacterLabel
import ru.bodro.music.ui.screens.wave.EqualizerBackground
import ru.bodro.music.ui.theme.BodroCoral
import ru.bodro.music.ui.theme.BodroOrange
import ru.bodro.music.ui.theme.BodroViolet
import ru.bodro.music.ui.theme.BodroYellow
import ru.bodro.music.ui.theme.LocalPlayerColors
import ru.bodro.music.ui.theme.WaveTitleStyle

/** Фигура на карточке режима. */
enum class ModeShape { Circle, Blob, Triangle, Squircle, Rings, Diamond }

/** Карточка режима волны: id режима, подпись, число треков, цвета градиента и фигура, настройки запуска. */
data class ModeCard(
    val id: String,
    val title: String,
    val colors: List<Color>,
    val shape: ModeShape,
    val subtitle: String? = null,
    val count: Int? = null,
) {
    /** Миксы и пресеты с телефона — по [WavePreset]; режимы сервера — `preset` = id режима. */
    val settings: WaveSettings get() = WavePreset.of(id)?.settings() ?: WaveSettings(preset = id)
}

/** Вид карточки по id режима: цвета и фигура (ориентир — «Моя волна» Яндекса). */
private val ModeVisuals: Map<String, Pair<List<Color>, ModeShape>> = mapOf(
    "energetic" to (listOf(BodroYellow, BodroOrange) to ModeShape.Triangle),
    "calm" to (listOf(Color(0xFF5EC8E5), Color(0xFF3A66D6)) to ModeShape.Circle),
    "happy" to (listOf(Color(0xFFFFD84D), BodroCoral) to ModeShape.Blob),
    "sad" to (listOf(Color(0xFF6C7BA8), Color(0xFF2E3550)) to ModeShape.Rings),
    "loud" to (listOf(Color(0xFFFF3D3D), Color(0xFF7A0019)) to ModeShape.Triangle),
    "discover" to (listOf(BodroViolet, Color(0xFF00C2A8)) to ModeShape.Diamond),
    "favorites" to (listOf(BodroCoral, Color(0xFFB0286B)) to ModeShape.Blob),
    "popular" to (listOf(BodroOrange, BodroViolet) to ModeShape.Squircle),
    "new" to (listOf(Color(0xFF7CF29C), Color(0xFF00A3FF)) to ModeShape.Diamond),
    "instrumental" to (listOf(Color(0xFFB8C6FF), Color(0xFF5B5FC7)) to ModeShape.Rings),
    "ru" to (listOf(Color(0xFFFFFFFF), Color(0xFF1C5BD9), Color(0xFFE53935)) to ModeShape.Squircle),
    "en" to (listOf(Color(0xFF1C3FAA), Color(0xFFE53935)) to ModeShape.Circle),
    "track_mix" to (listOf(Color(0xFF43D17A), Color(0xFF1F7A8C)) to ModeShape.Circle),
    "album_mix" to (listOf(Color(0xFFFF8FB1), BodroViolet) to ModeShape.Squircle),
    "decade_mix" to (listOf(Color(0xFFE0B36A), Color(0xFF8A5A2B)) to ModeShape.Rings),
)

private val DynamicPalettes = listOf(
    listOf(Color(0xFFFF9A62), Color(0xFFB5179E)),
    listOf(Color(0xFF4CC9F0), Color(0xFF4361EE)),
    listOf(Color(0xFFF9C74F), Color(0xFF43AA8B)),
    listOf(Color(0xFFF72585), Color(0xFF7209B7)),
)

private fun visualOf(id: String): Pair<List<Color>, ModeShape> = ModeVisuals[id]
    ?: (
        DynamicPalettes[(id.hashCode() and Int.MAX_VALUE) % DynamicPalettes.size] to
            if (id.startsWith("release:")) ModeShape.Squircle else ModeShape.Blob
        )

/** Миксы считаются на телефоне — их карточки есть всегда. */
@Composable
private fun mixCards(): List<ModeCard> = listOf(
    WavePreset.TrackMix to R.string.mix_tracks,
    WavePreset.AlbumMix to R.string.mix_albums,
    WavePreset.DecadeMix to R.string.mix_decade,
).map { (preset, title) -> visualOf(preset.id).let { (colors, shape) -> ModeCard(preset.id, stringResource(title), colors, shape) } }

/**
 * Карточки режимов: с сервера (bodrod 0.19.0+: число треков под режим, «В стиле X», «Новый релиз X») или, без него,
 * режимы, которые телефон собирает сам. Миксы — в конце.
 */
@Composable
fun rememberModeCards(server: List<WaveModeInfo>?): List<ModeCard> {
    val mixes = mixCards()
    val local = listOf(
        WavePreset.Energetic to R.string.wave_mood_energetic,
        WavePreset.Calm to R.string.wave_mood_calm,
        WavePreset.Happy to R.string.wave_mood_happy,
        WavePreset.Sad to R.string.wave_mood_sad,
        WavePreset.Discover to R.string.wave_mode_discover,
        WavePreset.Favorites to R.string.wave_mode_favorites,
        WavePreset.Popular to R.string.wave_mode_popular,
    ).map { (preset, title) -> visualOf(preset.id).let { (colors, shape) -> ModeCard(preset.id, stringResource(title), colors, shape) } }
    return remember(server, mixes, local) {
        val fromServer = server?.map { mode ->
            val (colors, shape) = visualOf(mode.id)
            ModeCard(mode.id, mode.title, colors, shape, subtitle = mode.subtitle, count = mode.count)
        }
        (fromServer ?: local) + mixes
    }
}

/**
 * Главная «Моя волна» (сверху вниз): строка с настройками волны, заголовком или чипом режима и поиском; крупное имя
 * исполнителя и маленькая обложка (свайп — следующий / предыдущий трек); кнопка, «таблетка» с названием и
 * прогрессом, лайк; факт из Last.fm; карусель режимов. Фон — эквалайзер в цветах обложки.
 */
@Composable
fun WaveHome(
    player: PlayerUiState,
    settings: AppSettings,
    facts: ArtistFacts?,
    modes: List<ModeCard>,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSelectMode: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    lowCoverage: Boolean = false,
    onFindMore: () -> Unit = {},
) {
    val actions = LocalAppActions.current
    val colors = LocalPlayerColors.current
    val wavePlaying = player.source.isWave && player.hasMedia
    val active = settings.waveModeId.ifBlank { null }?.let { id ->
        modes.firstOrNull { it.id == id }
            ?: ModeCard(id, id, emptyList(), ModeShape.Circle)
    }
    val configuration = LocalConfiguration.current
    // Широкое окно (ландшафт, разложенный боком): блок по высоте экрана, исполнитель слева, управление справа.
    val wide = configuration.screenWidthDp > configuration.screenHeightDp && configuration.screenWidthDp >= WIDE_MIN
    val height = if (wide) {
        (configuration.screenHeightDp * WIDE_HERO_SHARE).dp.coerceAtLeast(WIDE_HERO_MIN)
    } else {
        (configuration.screenHeightDp * HERO_SHARE).dp.coerceIn(HERO_MIN, HERO_MAX)
    }
    val start: (ModeCard?) -> Unit = { chosen ->
        onSelectMode(chosen?.id)
        actions.startWave(chosen?.settings ?: WaveSettings())
    }

    Box(Modifier.fillMaxWidth().heightIn(min = height).testTag("wave-home")) {
        EqualizerBackground(
            accent = colors?.accent ?: BodroYellow,
            base = colors?.background ?: Color(0xFF14110E),
            animate = settings.backgroundAnimation && (!wavePlaying || player.isPlaying),
            modifier = Modifier.matchParentSize(),
        )
        Column(
            Modifier.fillMaxWidth().heightIn(min = height).statusBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Сброс режима: волна играет — сразу «Моя волна» без режима; не играет — только снять режим.
            val reset: () -> Unit = { if (wavePlaying) start(null) else onSelectMode(null) }
            WaveTopRow(active, onOpenSettings, onReset = reset, onOpenSearch)
            if (wavePlaying && player.currentSong != null) {
                NowPlayingBlock(player, player.currentSong, onTogglePlay, onNext, onPrevious, facts, wide)
            } else {
                StartBlock(loading = player.waveLoading, onStart = { start(active) })
            }
            if (lowCoverage && wavePlaying && active != null) LowCoverageHint(onFindMore)
            ModeCarousel(modes, active = active, onPick = { chosen -> if (chosen == null) reset() else start(chosen) })
        }
    }
}

@Composable
private fun WaveTopRow(card: ModeCard?, onOpenSettings: () -> Unit, onReset: () -> Unit, onOpenSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("wave-settings")) {
            Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.wave_settings), tint = Color.White)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (card == null) {
                Text(stringResource(R.string.wave_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
            } else {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable(onClick = onReset)
                        .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                        .testTag("wave-mode-chip"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        card.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(6.dp))
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.wave_mode_reset),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        IconButton(onClick = onOpenSearch, modifier = Modifier.testTag("wave-search")) {
            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.tab_search), tint = Color.White)
        }
    }
}

@Composable
private fun StartBlock(loading: Boolean, onStart: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.wave_title), style = WaveTitleStyle, color = Color.White)
        Spacer(Modifier.height(24.dp))
        Surface(onClick = onStart, shape = CircleShape, color = BodroYellow, modifier = Modifier.size(96.dp).testTag("wave-start")) {
            Box(contentAlignment = Alignment.Center) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(40.dp), color = Color.Black, strokeWidth = 3.dp)
                } else {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.wave_start),
                        tint = Color.Black,
                        modifier = Modifier.size(56.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.wave_start_hint), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
    }
}

/** Исполнитель, обложка, управление и факт. Свайп по исполнителю и обложке — следующий / предыдущий трек. */
@Composable
private fun NowPlayingBlock(
    player: PlayerUiState,
    song: Song,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    facts: ArtistFacts?,
    wide: Boolean,
) {
    val navigator = LocalNavigator.current
    var direction by remember { mutableIntStateOf(1) }
    var drag by remember { mutableFloatStateOf(0f) }
    val swipeArea: @Composable (Modifier) -> Unit = { modifier ->
        AnimatedContent(
            targetState = song,
            contentKey = { it.id },
            transitionSpec = {
                (slideInHorizontally(tween(SWIPE_MS)) { it * direction / 2 } + fadeIn(tween(SWIPE_MS))) togetherWith
                    (slideOutHorizontally(tween(SWIPE_MS)) { -it * direction / 2 } + fadeOut(tween(SWIPE_MS)))
            },
            label = "wave-track",
            modifier = modifier
                .pointerInput(Unit) {
                    // Только горизонтальный жест: вертикальная прокрутка главной и карусель режимов не мешают.
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = {
                            val threshold = SWIPE_THRESHOLD.dp.toPx()
                            if (drag < -threshold) {
                                direction = 1
                                onNext()
                            } else if (drag > threshold) {
                                direction = -1
                                onPrevious()
                            }
                        },
                    ) { change, amount ->
                        change.consume()
                        drag += amount
                    }
                }
                .testTag("wave-swipe-area"),
        ) { current ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                BasicText(
                    current.artist.orEmpty(),
                    style = WaveTitleStyle.copy(color = Color.White),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 26.sp, maxFontSize = 60.sp, stepSize = 2.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = current.artistId != null) { current.artistId?.let(navigator::openArtist) }
                        .testTag("wave-artist"),
                )
                Spacer(Modifier.height(16.dp))
                CoverImage(
                    current.coverArt,
                    Modifier.size(COVER_SIZE.dp),
                    sizeHint = 200.dp,
                    shape = RoundedCornerShape(14.dp),
                    kind = CoverKind.Song,
                )
            }
        }
    }
    val controls: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            ControlRow(player, song, onTogglePlay, onOpenPlayer = navigator::openPlayer)
            facts?.let { FactLine(it) }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            swipeArea(Modifier.weight(1f))
            controls(Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            swipeArea(Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            controls(Modifier.fillMaxWidth().widthIn(max = PILL_MAX_WIDTH))
        }
    }
}

@Composable
private fun ControlRow(player: PlayerUiState, song: Song, onTogglePlay: () -> Unit, onOpenPlayer: () -> Unit) {
    val actions = LocalAppActions.current
    val reaction = LocalReactions.current.of(song)
    val accent = LocalPlayerColors.current?.accent ?: BodroYellow
    val progress by animateFloatAsState(
        if (player.durationMs > 0) (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f,
        tween(PROGRESS_MS, easing = LinearEasing),
        label = "wave-progress",
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = onTogglePlay, shape = CircleShape, color = BodroYellow, modifier = Modifier.size(56.dp).testTag("wave-play")) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (player.isPlaying) R.string.action_pause else R.string.action_play),
                    tint = Color.Black,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        // «Таблетка»: заполняется слева направо по прогрессу; нажатие — полноэкранный плеер.
        Box(
            Modifier
                .weight(1f)
                .height(52.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onOpenPlayer)
                .testTag("wave-pill"),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(accent.copy(alpha = 0.5f)))
            Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                song.album?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        val liked = reaction == Reaction.Liked
        IconButton(onClick = { actions.toggleLike(song) }, modifier = Modifier.testTag("wave-like")) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = stringResource(if (liked) R.string.action_unlike else R.string.action_like),
                tint = if (liked) BodroYellow else Color.White,
            )
        }
    }
}

@Composable
private fun FactLine(facts: ArtistFacts) {
    val text = facts.fact ?: facts.tags.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.82f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp).testTag("wave-fact"),
    )
}

/** Под режим мало треков в библиотеке: предложить подобрать и докачать похожее (пул «На пробу»). */
@Composable
private fun LowCoverageHint(onFindMore: () -> Unit) {
    Row(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onFindMore)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("wave-low-coverage"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.wave_low_coverage),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        Text(stringResource(R.string.wave_find_more), style = MaterialTheme.typography.labelLarge, color = BodroYellow)
    }
}

/** Карусель режимов. При выбранном режиме первая карточка — «Назад к привычному». */
@Composable
private fun ModeCarousel(cards: List<ModeCard>, active: ModeCard?, onPick: (ModeCard?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 20.dp).testTag("wave-modes"),
    ) {
        if (active != null) {
            item(key = "back") {
                ModeCardView(
                    stringResource(R.string.wave_back_to_usual),
                    null,
                    listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.12f)),
                    ModeShape.Rings,
                    selected = false,
                    tag = "wave-mode-back",
                ) { onPick(null) }
            }
        }
        items(cards, key = { it.id }) { card ->
            val caption = card.subtitle ?: card.count?.let { pluralStringResource(R.plurals.tracks, it, it) }
            ModeCardView(card.title, caption, card.colors, card.shape, selected = card.id == active?.id, tag = "wave-mode-${card.id}") {
                onPick(card)
            }
        }
    }
}

@Composable
private fun ModeCardView(
    title: String,
    caption: String?,
    colors: List<Color>,
    shape: ModeShape,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(CARD_WIDTH.dp)
            .height(CARD_HEIGHT.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(20.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(10.dp)
            .testTag(tag),
    ) {
        Canvas(Modifier.fillMaxWidth().height(CARD_WIDTH.dp - 44.dp)) { drawModeShape(shape, colors) }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = if (caption ==
                null
            ) {
                2
            } else {
                1
            },
            overflow = TextOverflow.Ellipsis,
        )
        caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun DrawScope.drawModeShape(shape: ModeShape, colors: List<Color>) {
    val brush = Brush.linearGradient(colors, start = Offset.Zero, end = Offset(size.width, size.height))
    val cx = size.width / 2
    val cy = size.height / 2
    val r = minOf(size.width, size.height) * 0.42f
    when (shape) {
        ModeShape.Circle -> drawCircle(brush, r, Offset(cx, cy))
        ModeShape.Rings -> {
            drawCircle(brush, r, Offset(cx, cy), alpha = 0.35f)
            drawCircle(brush, r * 0.7f, Offset(cx, cy), alpha = 0.6f)
            drawCircle(brush, r * 0.4f, Offset(cx, cy))
        }
        ModeShape.Squircle -> drawRoundRect(
            brush,
            topLeft = Offset(cx - r, cy - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.55f),
        )
        ModeShape.Triangle -> drawPath(
            Path().apply {
                moveTo(cx, cy - r)
                lineTo(cx + r, cy + r * 0.8f)
                lineTo(cx - r, cy + r * 0.8f)
                close()
            },
            brush,
        )
        ModeShape.Diamond -> drawPath(
            Path().apply {
                moveTo(cx, cy - r)
                lineTo(cx + r, cy)
                lineTo(cx, cy + r)
                lineTo(cx - r, cy)
                close()
            },
            brush,
        )
        ModeShape.Blob -> {
            drawCircle(brush, r * 0.7f, Offset(cx - r * 0.35f, cy - r * 0.15f))
            drawCircle(brush, r * 0.6f, Offset(cx + r * 0.4f, cy + r * 0.2f))
            drawCircle(brush, r * 0.45f, Offset(cx, cy + r * 0.45f))
        }
    }
}

/** Настройки волны (кнопка слева сверху): характер и настроение — сочетаются с режимом. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WaveSettingsSheet(
    settings: AppSettings,
    onCharacter: (WaveCharacter) -> Unit,
    onMood: (WaveMood) -> Unit,
    onLanguage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.wave_settings), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.settings_wave_character), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WaveCharacter.entries.forEach { character ->
                    FilterChip(
                        selected = settings.waveCharacter == character,
                        onClick = { onCharacter(character) },
                        label = { Text(stringResource(waveCharacterLabel(character))) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_wave_mood), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WaveMood.entries.forEach { mood ->
                    FilterChip(selected = settings.waveMood == mood, onClick = {
                        onMood(mood)
                    }, label = { Text(stringResource(moodLabel(mood))) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.wave_language), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "" to R.string.wave_language_any,
                    "ru" to R.string.wave_language_ru,
                    "en" to R.string.wave_language_en,
                    "none" to R.string.wave_language_none,
                )
                    .forEach { (code, label) ->
                        FilterChip(selected = settings.waveLanguage == code, onClick = {
                            onLanguage(code)
                        }, label = { Text(stringResource(label)) })
                    }
            }
            Text(
                stringResource(R.string.wave_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@StringRes
fun moodLabel(mood: WaveMood): Int = when (mood) {
    WaveMood.Any -> R.string.wave_mood_any
    WaveMood.Energetic -> R.string.wave_mood_energetic
    WaveMood.Calm -> R.string.wave_mood_calm
    WaveMood.Happy -> R.string.wave_mood_happy
    WaveMood.Sad -> R.string.wave_mood_sad
}

private const val HERO_SHARE = 0.78f
private val HERO_MIN = 460.dp
private val HERO_MAX = 760.dp
private const val COVER_SIZE = 84
private const val CARD_WIDTH = 116
private const val CARD_HEIGHT = 136
private const val WIDE_MIN = 600
private const val WIDE_HERO_SHARE = 0.92f
private val WIDE_HERO_MIN = 320.dp
private val PILL_MAX_WIDTH = 640.dp
private const val SWIPE_MS = 260
private const val SWIPE_THRESHOLD = 56
private const val PROGRESS_MS = 250
