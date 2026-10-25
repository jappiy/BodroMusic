package ru.bodro.music.ui.screens.home

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SentimentVerySatisfied
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.domain.wave.WavePreset
import ru.bodro.music.core.downloads.WaveModeInfo
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.ui.components.CoverImage
import ru.bodro.music.ui.components.CoverKind

/**
 * Режим волны в карусели: id режима, подпись, цвет купола, глиф; у режимов «В стиле X» / «Новый релиз X» —
 * [category] над подписью и круглый аватар исполнителя ([avatar] — обложка исполнителя из библиотеки).
 */
data class ModeCard(
    val id: String,
    val title: String,
    val color: Color,
    val icon: ImageVector,
    val category: String? = null,
    val avatar: String? = null,
    val count: Int? = null,
) {
    /** Миксы и пресеты с телефона — по [WavePreset]; режимы сервера — `preset` = id режима. */
    val settings: WaveSettings get() = WavePreset.of(id)?.settings() ?: WaveSettings(preset = id)

    /** Исполнитель режима «В стиле X» / «Новый релиз X» — для аватара. */
    val artistName: String? get() = artistOf(id, title)
}

/** Цвет купола и глиф по id режима: сочные цвета, которые светятся на тёмном низу экрана. */
private val ModeVisuals: Map<String, Pair<Color, ImageVector>> = mapOf(
    "energetic" to (Color(0xFFFF8A1F) to Icons.Rounded.Bolt),
    "calm" to (Color(0xFF2FA8D8) to Icons.Rounded.Spa),
    "happy" to (Color(0xFFF2C320) to Icons.Rounded.SentimentVerySatisfied),
    "sad" to (Color(0xFF5562C9) to Icons.Rounded.WaterDrop),
    "loud" to (Color(0xFFE5303C) to Icons.AutoMirrored.Rounded.VolumeUp),
    "discover" to (Color(0xFF8E44E8) to Icons.Rounded.Explore),
    "favorites" to (Color(0xFFE4234F) to Icons.Rounded.Favorite),
    "popular" to (Color(0xFFE0389B) to Icons.Rounded.LocalFireDepartment),
    "new" to (Color(0xFF22B573) to Icons.Rounded.NewReleases),
    "instrumental" to (Color(0xFF9B59F0) to Icons.Rounded.Piano),
    "ru" to (Color(0xFF2E62E8) to Icons.Rounded.Translate),
    "en" to (Color(0xFFD6334A) to Icons.Rounded.Translate),
    "track_mix" to (Color(0xFF1FB89A) to Icons.Rounded.Shuffle),
    "album_mix" to (Color(0xFFE2559A) to Icons.Rounded.Album),
    "decade_mix" to (Color(0xFFD99A2B) to Icons.Rounded.History),
)

private val DynamicColors = listOf(Color(0xFF2EAD4B), Color(0xFF3557E0), Color(0xFFC63FD8), Color(0xFFE0682A))

private val BackColor = Color(0xFF8C93A3)

private fun visualOf(id: String): Pair<Color, ImageVector> = ModeVisuals[id]
    ?: (DynamicColors[(id.hashCode() and Int.MAX_VALUE) % DynamicColors.size] to Icons.Rounded.AutoAwesome)

private fun artistOf(id: String, title: String): String? = when {
    id.startsWith("artist:") -> id.removePrefix("artist:")
    id.startsWith("release:") -> title.substringAfter(": ", "").ifBlank { null }
    else -> null
}

/** Миксы считаются на телефоне — их карточки есть всегда. */
@Composable
private fun mixCards(): List<ModeCard> = listOf(
    WavePreset.TrackMix to R.string.mix_tracks,
    WavePreset.AlbumMix to R.string.mix_albums,
    WavePreset.DecadeMix to R.string.mix_decade,
).map { (preset, title) -> visualOf(preset.id).let { (color, icon) -> ModeCard(preset.id, stringResource(title), color, icon) } }

/**
 * Режимы: с сервера (bodrod 0.19.0+: «В стиле X», «Новый релиз X») или, без него, режимы, которые телефон собирает
 * сам. Миксы — в конце. [artistCovers] — обложки исполнителей библиотеки по имени (в нижнем регистре).
 */
@Composable
fun rememberModeCards(server: List<WaveModeInfo>?, artistCovers: Map<String, String> = emptyMap()): List<ModeCard> {
    val mixes = mixCards()
    val styleOf = stringResource(R.string.wave_mode_style_of)
    val newRelease = stringResource(R.string.wave_mode_new_release)
    val local = listOf(
        WavePreset.Energetic to R.string.wave_mood_energetic,
        WavePreset.Calm to R.string.wave_mood_calm,
        WavePreset.Happy to R.string.wave_mood_happy,
        WavePreset.Sad to R.string.wave_mood_sad,
        WavePreset.Discover to R.string.wave_mode_discover,
        WavePreset.Favorites to R.string.wave_mode_favorites,
        WavePreset.Popular to R.string.wave_mode_popular,
    ).map { (preset, title) -> visualOf(preset.id).let { (color, icon) -> ModeCard(preset.id, stringResource(title), color, icon) } }
    return remember(server, mixes, local, artistCovers) {
        val fromServer = server?.map { mode ->
            val (color, icon) = visualOf(mode.id)
            val artist = artistOf(mode.id, mode.title)
            when {
                mode.id.startsWith("artist:") && artist != null ->
                    ModeCard(mode.id, artist, color, icon, category = styleOf, avatar = artistCovers[artist.lowercase()])
                mode.id.startsWith("release:") && artist != null ->
                    ModeCard(
                        mode.id,
                        mode.subtitle ?: artist,
                        color,
                        icon,
                        category = newRelease,
                        avatar = artistCovers[artist.lowercase()],
                    )
                else -> ModeCard(mode.id, mode.title, color, icon, count = mode.count)
            }
        }
        (fromServer ?: local) + mixes
    }
}

/**
 * Карусель режимов в виде светящихся «куполов» (ориентир — «Моя волна» Яндекса): без карточек и рамок, купол —
 * арка, насыщенная сверху и растворяющаяся книзу; глиф наполовину заходит на верх купола. Соседи чуть
 * перекрываются, крайние уходят за край экрана. Прокрутка примагничивается к центру; по пологой дуге
 * центральный купол выше и крупнее, боковые ниже, меньше и прозрачнее — плавно, прямо во время свайпа.
 * Выбранный режим — в центре (и чипом в шапке); при выбранном режиме первый купол — «Назад к привычному».
 * Карусель бесконечная в обе стороны (…, 9, 10, 1, 2, …): элементов [LOOPS] × n, старт — в середине, купол — `i % n`.
 */
@Composable
fun ModeDomes(cards: List<ModeCard>, active: ModeCard?, onPick: (ModeCard?) -> Unit, modifier: Modifier = Modifier) {
    val back = ModeCard("back", stringResource(R.string.wave_back_to_usual), BackColor, Icons.Rounded.Replay)
    val items = if (active != null) listOf(back) + cards else cards
    val activeIndex = active?.let { a -> items.indexOfFirst { it.id == a.id } }?.takeIf { it >= 0 } ?: 0
    val n = items.size.coerceAtLeast(1)
    val total = n * LOOPS
    val state = rememberLazyListState(initialFirstVisibleItemIndex = loopIndex(total / 2, activeIndex, n))
    val scope = rememberCoroutineScope()
    // Выбор режима (и смена набора куполов — «Назад» появился или исчез): к ближайшей копии нужного купола.
    LaunchedEffect(active?.id, n) {
        val target = loopIndex(centerIndex(state) ?: state.firstVisibleItemIndex, activeIndex, n)
        if (abs(target - state.firstVisibleItemIndex) > n * 2) state.scrollToItem(target) else state.animateScrollToItem(target)
    }

    BoxWithConstraints(modifier.fillMaxWidth().testTag("wave-modes")) {
        val side = ((maxWidth - DOME_WIDTH) / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(-DOME_OVERLAP),
            flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Center),
            modifier = Modifier.fillMaxWidth().height(DOME_ITEM_HEIGHT + ARC_DROP * 2),
        ) {
            items(total) { index ->
                val card = items[index % n]
                // Центральный — поверх соседей; zIndex меняется, только когда купол проходит середину.
                val rank by remember(index) { derivedStateOf { (abs(distanceFromCenter(state, index)) * 2).toInt() } }
                Dome(
                    card,
                    tag = if (card.id == "back") "wave-mode-back" else "wave-mode-${card.id}",
                    modifier = Modifier
                        .zIndex(-rank.toFloat())
                        .graphicsLayer {
                            val d = abs(distanceFromCenter(state, index)).coerceAtMost(MAX_DISTANCE)
                            val scale = 1f - SCALE_STEP * d.coerceAtMost(1.5f)
                            scaleX = scale
                            scaleY = scale
                            translationY = ARC_DROP.toPx() * (d * d * 0.5f + d * 0.5f).coerceAtMost(2.5f)
                            alpha = 1f - FADE_STEP * (d / 2f).coerceAtMost(1f)
                        },
                ) {
                    scope.launch { state.animateScrollToItem(index) }
                    onPick(if (card.id == "back") null else card)
                }
            }
        }
    }
}

/** Индекс копии купола [item] (0 until [n]), ближайшей к позиции [near]. */
internal fun loopIndex(near: Int, item: Int, n: Int): Int {
    val base = near - Math.floorMod(near, n) + item
    return listOf(base - n, base, base + n).minBy { abs(it - near) }.coerceAtLeast(0)
}

/** Индекс купола, ближайшего к центру карусели (`null` — ещё не разложена). */
private fun centerIndex(state: androidx.compose.foundation.lazy.LazyListState): Int? {
    val info = state.layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - center) }?.index
}

/** Насколько купол [index] далёк от центра карусели, в ширинах купола (знак — сторона). */
private fun distanceFromCenter(state: androidx.compose.foundation.lazy.LazyListState, index: Int): Float {
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return MAX_DISTANCE
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return (item.offset + item.size / 2f - center) / item.size.coerceAtLeast(1)
}

@Composable
private fun Dome(card: ModeCard, tag: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .width(DOME_WIDTH)
            .height(DOME_ITEM_HEIGHT)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .testTag(tag),
    ) {
        // Свечение вокруг купола и сам купол: размытые края — как свет, а не плашка (размытие — Android 12+).
        Canvas(
            Modifier
                .padding(top = DOME_TOP)
                .fillMaxWidth()
                .height(DOME_ITEM_HEIGHT - DOME_TOP)
                .then(
                    if (Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.S
                    ) {
                        Modifier.blur(18.dp, BlurredEdgeTreatment.Unbounded)
                    } else {
                        Modifier
                    },
                ),
        ) { drawDome(card.color, glow = true) }
        Canvas(
            Modifier
                .padding(top = DOME_TOP)
                .fillMaxWidth()
                .height(DOME_ITEM_HEIGHT - DOME_TOP)
                .then(
                    if (Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.S
                    ) {
                        Modifier.blur(2.dp, BlurredEdgeTreatment.Unbounded)
                    } else {
                        Modifier
                    },
                ),
        ) { drawDome(card.color, glow = false) }
        DomeIcon(card, Modifier.align(Alignment.TopCenter))
        Column(
            Modifier.align(Alignment.TopCenter).offset(y = DOME_TOP + ICON_SIZE / 2 + 6.dp).padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            card.category?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                card.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Глиф настроения (светлее купола) или круглый аватар исполнителя. */
@Composable
private fun DomeIcon(card: ModeCard, modifier: Modifier) {
    val artist = card.artistName
    if (artist != null) {
        Box(
            modifier.size(ICON_SIZE).clip(CircleShape).background(lerp(card.color, Color.Black, 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            if (card.avatar != null) {
                CoverImage(card.avatar, Modifier.size(ICON_SIZE), sizeHint = 112.dp, shape = CircleShape, kind = CoverKind.Artist)
            } else {
                Text(
                    artist.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = Color.White,
                )
            }
        }
    } else {
        Icon(
            card.icon,
            contentDescription = null,
            tint = lerp(card.color, Color.White, 0.35f),
            modifier = modifier.size(ICON_SIZE),
        )
    }
}

/** Арка со скруглённым верхом: сочный цвет сверху, к низу — в прозрачность. [glow] — широкий мягкий ореол. */
private fun DrawScope.drawDome(color: Color, glow: Boolean) {
    val w = size.width
    val h = size.height
    val inset = if (glow) -w * 0.04f else w * 0.04f
    val top = if (glow) -h * 0.02f else 0f
    val width = w - inset * 2
    val radius = width * 0.48f
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                left = inset,
                top = top,
                right = w - inset,
                bottom = h,
                topLeftCornerRadius = CornerRadius(radius),
                topRightCornerRadius = CornerRadius(radius),
                bottomLeftCornerRadius = CornerRadius.Zero,
                bottomRightCornerRadius = CornerRadius.Zero,
            ),
        )
    }
    val alpha = if (glow) 0.55f else 1f
    drawPath(
        path,
        Brush.verticalGradient(
            0f to lerp(color, Color.White, if (glow) 0f else 0.12f).copy(alpha = alpha),
            0.35f to color.copy(alpha = alpha * 0.85f),
            0.7f to color.copy(alpha = alpha * 0.32f),
            1f to Color.Transparent,
            startY = top,
            endY = h,
        ),
    )
    if (!glow) {
        // Блик у верха купола — объём, как у светящегося стекла.
        drawOval(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(w / 2, h * 0.08f),
                radius = width * 0.45f,
            ),
            topLeft = Offset(inset + width * 0.1f, 0f),
            size = Size(width * 0.8f, h * 0.3f),
        )
    }
}

private val DOME_WIDTH = 140.dp
private val DOME_OVERLAP = 18.dp
private val DOME_ITEM_HEIGHT = 176.dp
private val ICON_SIZE = 56.dp
private val DOME_TOP = 28.dp
private val ARC_DROP = 12.dp
private const val MAX_DISTANCE = 3f
private const val SCALE_STEP = 0.1f
private const val FADE_STEP = 0.35f

/** Сколько раз повторяется набор куполов: «бесконечно» в обе стороны от середины. */
private const val LOOPS = 1_000
