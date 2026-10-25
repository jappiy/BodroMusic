package ru.bodro.music.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.bodro.music.R
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.reco.Section
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.playback.PlayerUiState
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.app.LocalNavigator
import ru.bodro.music.ui.app.LocalReactions
import ru.bodro.music.ui.components.CoverImage
import ru.bodro.music.ui.components.CoverKind
import ru.bodro.music.ui.components.shimmerAlpha
import ru.bodro.music.ui.screens.player.sourceTitle
import ru.bodro.music.ui.screens.wave.NebulaBackground
import ru.bodro.music.ui.theme.BodroYellow
import ru.bodro.music.ui.theme.LocalPlayerColors
import ru.bodro.music.ui.theme.NeutralGlow
import ru.bodro.music.ui.theme.WaveTitleStyle

/**
 * Главная — только «Моя волна» на весь экран, без прокрутки к другому контенту (остальное — во вкладке «Чарты»).
 * Показывает то, что реально играет, откуда бы трек ни был запущен. Трек не из волны (чарты, поиск, альбом):
 * сверху «Волна по треку «…»», очередь не трогается, пока пользователь не нажмёт «следующий» (свайп) или не выберет
 * режим — тогда очередь переходит на волну от этого трека; кончится сама — сервис продолжит волной от последнего.
 * Сверху вниз: настройки волны, заголовок или чип режима, поиск; крупное имя исполнителя и обложка (свайп —
 * следующий / предыдущий трек); «Не нравится», play/pause, «таблетка» с названием и прогрессом (тап — плеер), лайк; короткий
 * текст о треке; карусель режимов-куполов. Фон — туманность в цветах обложки ([NebulaBackground]).
 */
@Composable
fun WaveHome(
    player: PlayerUiState,
    settings: AppSettings,
    blurbs: Section<List<String>>,
    modes: List<ModeCard>,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSelectMode: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    lowCoverage: Boolean = false,
    onFindMore: () -> Unit = {},
    /** Фон рисует корень приложения ([HomeBackdrop]) — под нижним меню и системными панелями. */
    drawBackground: Boolean = true,
) {
    val actions = LocalAppActions.current
    val wavePlaying = player.source.isWave && player.hasMedia
    val outsideWave = player.hasMedia && !player.source.isWave
    val active = settings.waveModeId.ifBlank { null }?.let { id ->
        modes.firstOrNull { it.id == id } ?: ModeCard(id, id, Color.Gray, Icons.Rounded.AutoAwesome)
    }
    val configuration = LocalConfiguration.current
    // Широкое окно (ландшафт, разложенный боком): исполнитель слева, управление справа; по высоте может не влезть —
    // тогда блок прокручивается целиком (контента ниже волны всё равно нет).
    val wide = configuration.screenWidthDp > configuration.screenHeightDp && configuration.screenWidthDp >= WIDE_MIN
    val start: (ModeCard?) -> Unit = { chosen ->
        onSelectMode(chosen?.id)
        actions.startWave(chosen?.settings ?: WaveSettings())
    }
    // Сброс режима: что-то играет — сразу «Моя волна» без режима (от текущего трека); иначе — только снять режим.
    val reset: () -> Unit = { if (player.hasMedia) start(null) else onSelectMode(null) }
    // «Следующий» вне волны — очередь переходит на волну от текущего трека.
    val next: () -> Unit = {
        val song = player.currentSong
        if (outsideWave && song != null) actions.continueWithWave(song, skip = true) else onNext()
    }
    val title = when {
        outsideWave -> player.currentSong?.let { stringResource(R.string.wave_by_track, it.title) }
        else -> (player.source as? QueueSource.WaveSource)?.settings?.trackSeed?.takeIf { wavePlaying }
            ?.let { stringResource(R.string.wave_by_track, it.title) }
    }
    val subtitle = if (outsideWave) stringResource(R.string.wave_now_from, sourceTitle(player.source)) else null

    Box(modifier.fillMaxSize().then(if (drawBackground) Modifier.background(Color.Black) else Modifier).testTag("wave-home")) {
        if (drawBackground) HomeBackdrop(player, settings, Modifier.matchParentSize())
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
            val compact = maxHeight < COMPACT_HEIGHT
            val content: @Composable ColumnScope.(scrollable: Boolean) -> Unit = { scrollable ->
                // Чип режима — только когда играет волна этого режима (а не трек из чартов при выбранном режиме).
                WaveTopRow(active?.takeIf { title == null }, title, subtitle, onOpenSettings, onReset = reset, onOpenSearch)
                if (scrollable) Spacer(Modifier.height(12.dp)) else Spacer(Modifier.weight(1f))
                if (player.hasMedia && player.currentSong != null) {
                    NowPlayingBlock(player, player.currentSong, onTogglePlay, next, onPrevious, blurbs, wide, compact)
                } else {
                    StartBlock(loading = player.waveLoading, onStart = { start(active) })
                }
                if (lowCoverage && wavePlaying && active != null) LowCoverageHint(onFindMore)
                if (scrollable) Spacer(Modifier.height(12.dp)) else Spacer(Modifier.weight(0.5f))
                ModeDomes(modes, active = active, onPick = { chosen -> if (chosen == null) reset() else start(chosen) })
                Spacer(Modifier.height(8.dp))
            }
            if (wide) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content(true) }
            } else {
                Column(Modifier.fillMaxSize()) { content(false) }
            }
        }
    }
}

/**
 * Фон главной: чёрный и живая туманность. Корень рисует его на весь экран, edge-to-edge. Цвета — обложки текущего
 * трека (любого, не только из волны); без обложки — цвет режима волны («Пошумим» — красный, «Спокойное» — голубой…);
 * ничего не играет и режима нет — фиолетовый [NeutralGlow]. [active] = false — главную закрыл плеер, кадров нет.
 */
@Composable
fun HomeBackdrop(player: PlayerUiState, settings: AppSettings, modifier: Modifier = Modifier, active: Boolean = true) {
    val colors = LocalPlayerColors.current
    val cover = colors?.glow?.takeIf { player.hasMedia && it.isNotEmpty() && it != NeutralGlow }
    val modeId = (player.source as? QueueSource.WaveSource)?.settings?.preset ?: settings.waveModeId.ifBlank { null }
    val mode = modeId?.takeIf { !player.hasMedia || player.source.isWave }?.let { listOf(modeColor(it)) }
    Box(modifier.background(Color.Black)) {
        NebulaBackground(
            colors = cover ?: mode ?: NeutralGlow,
            mode = settings.backgroundAnimation,
            playing = player.hasMedia && player.isPlaying,
            modifier = Modifier.matchParentSize(),
            active = active,
        )
    }
}

@Composable
private fun WaveTopRow(
    card: ModeCard?,
    title: String?,
    subtitle: String?,
    onOpenSettings: () -> Unit,
    onReset: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("wave-settings")) {
            Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.wave_settings), tint = Color.White)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (card == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.testTag("wave-title")) {
                    Text(
                        title ?: stringResource(R.string.wave_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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

/** Исполнитель, обложка, управление и короткий текст. Свайп по исполнителю и обложке — следующий / предыдущий трек. */
@Composable
private fun NowPlayingBlock(
    player: PlayerUiState,
    song: Song,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    blurbs: Section<List<String>>,
    wide: Boolean,
    compact: Boolean,
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
                    // Только горизонтальный жест: карусель режимов и вертикальные жесты не мешают.
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
                    // Сначала уменьшается шрифт, потом — две строки с многоточием. Межстрочный интервал в em
                    // ([WaveTitleStyle]) растёт вместе с размером: строки капсом и с Й/Ё не наползают.
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = if (compact) 24.sp else 26.sp,
                        maxFontSize = if (compact) 44.sp else 60.sp,
                        stepSize = 2.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = current.artistId != null) { current.artistId?.let(navigator::openArtist) }
                        .testTag("wave-artist"),
                )
                Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
                CoverImage(
                    current.coverArt,
                    Modifier.size(if (compact) COVER_COMPACT.dp else COVER_SIZE.dp),
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
            TrackBlurb(blurbs, Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp))
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
            Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
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
    // По логике приложения: «Не нравится» слева, ♡ справа. Сужается только «таблетка», кнопки — не меньше 48 dp.
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { actions.dislikeFromWave(song) }, modifier = Modifier.testTag("wave-dislike")) {
            Icon(
                painterResource(R.drawable.ic_heart_broken),
                contentDescription = stringResource(R.string.action_dislike),
                tint = Color.White.copy(alpha = 0.85f),
            )
        }
        Spacer(Modifier.size(2.dp))
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

/**
 * Короткий текст о треке под «таблеткой»: место — ровно [BLURB_LINES] строки. Сервер присылает несколько законченных
 * фраз (до ~110 символов); показывается первая, которая целиком помещается при текущей ширине и размере шрифта —
 * без многоточий. Не поместилась ни одна или текста нет — блок пуст. Пока текст подбирается — «Собираю интересное…».
 */
@Composable
private fun TrackBlurb(blurbs: Section<List<String>>, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    val iconSpace = BLURB_ICON + 12.dp
    BoxWithConstraints(modifier.fillMaxWidth().height(lineHeight * BLURB_LINES + 4.dp).testTag("wave-blurb")) {
        val measurer = rememberTextMeasurer()
        val textWidth = with(LocalDensity.current) { (maxWidth - iconSpace).roundToPx().coerceAtLeast(1) }
        val fitting = (blurbs as? Section.Ready)?.value?.let { variants ->
            remember(variants, textWidth, style) { firstFitting(variants, textWidth, style, measurer::measureLines) }
        }
        val shown: String? = when {
            blurbs is Section.Loading -> LOADING
            fitting != null -> fitting
            else -> null
        }
        AnimatedContent(targetState = shown, transitionSpec = {
            fadeIn(tween(BLURB_FADE_MS)) togetherWith fadeOut(tween(BLURB_FADE_MS))
        }, label = "blurb") { text ->
            if (text != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(BLURB_ICON).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.size(12.dp))
                    if (text == LOADING) {
                        val alpha = shimmerAlpha()
                        Text(
                            stringResource(R.string.wave_blurb_loading),
                            style = style,
                            color = Color.White,
                            modifier = Modifier.graphicsLayer { this.alpha = 0.45f + alpha * 2f }.testTag("wave-blurb-loading"),
                        )
                    } else {
                        Text(text, style = style, color = Color.White.copy(alpha = 0.86f), modifier = Modifier.testTag("wave-fact"))
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.text.TextMeasurer.measureLines(text: String, width: Int, style: TextStyle): Pair<Int, Boolean> {
    val layout = measure(text, style, constraints = Constraints(maxWidth = width), maxLines = BLURB_LINES + 1)
    return layout.lineCount to layout.hasVisualOverflow
}

/** Первая фраза, которая целиком помещается в [BLURB_LINES] строки ширины [width]. */
internal fun firstFitting(
    variants: List<String>,
    width: Int,
    style: TextStyle,
    measure: (String, Int, TextStyle) -> Pair<Int, Boolean>,
): String? = variants.firstOrNull { text ->
    val (lines, overflow) = measure(text, width, style)
    lines <= BLURB_LINES && !overflow
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

/** Метка состояния «текст подбирается» в [TrackBlurb]. */
private const val LOADING = "\u0000loading"
private const val BLURB_LINES = 3
private val BLURB_ICON: Dp = 32.dp
private const val BLURB_FADE_MS = 400
private const val COVER_SIZE = 84
private const val COVER_COMPACT = 64
private val COMPACT_HEIGHT = 640.dp
private const val WIDE_MIN = 600
private val PILL_MAX_WIDTH = 640.dp
private const val SWIPE_MS = 260
private const val SWIPE_THRESHOLD = 56
private const val PROGRESS_MS = 250
