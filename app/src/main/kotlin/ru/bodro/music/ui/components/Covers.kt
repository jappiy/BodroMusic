package ru.bodro.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import ru.bodro.music.core.model.Song
import ru.bodro.music.ui.image.CoverArtRef
import ru.bodro.music.ui.image.CoverArtUrls
import ru.bodro.music.ui.image.coverChain

val LocalCoverArtUrls = staticCompositionLocalOf<CoverArtUrls> { error("CoverArtUrls not provided") }

enum class CoverKind(val icon: ImageVector) {
    Song(Icons.Rounded.MusicNote),
    Album(Icons.Rounded.Album),
    Artist(Icons.Rounded.Person),
    Playlist(Icons.Rounded.QueueMusic),
}

/** Обложка с плейсхолдером. Размер в px определяется по [sizeHint]. */
@Composable
fun CoverImage(
    coverArtId: String?,
    modifier: Modifier = Modifier,
    sizeHint: Dp = 160.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    kind: CoverKind = CoverKind.Album,
    contentDescription: String? = null,
) = CoverImage(listOfNotNull(coverArtId), modifier, sizeHint, shape, kind, contentDescription)

/** Обложка трека с цепочкой «трек → альбом → исполнитель → заглушка» ([coverChain]). */
@Composable
fun SongCover(
    song: Song,
    modifier: Modifier = Modifier,
    sizeHint: Dp = 48.dp,
    shape: Shape = RoundedCornerShape(10.dp),
    contentDescription: String? = null,
) = CoverImage(song.coverChain(), modifier, sizeHint, shape, CoverKind.Song, contentDescription)

/**
 * Единый компонент обложки: пробует [coverIds] по порядку (следующий — если предыдущий не загрузился), затем
 * [remoteUrl] (внешний адрес, например из Яндекса или Last.fm), затем заглушка. Пока грузится — та же заглушка, без
 * мигания: из кэша памяти картинка приходит сразу, из сети — с плавным появлением. Сервер ещё не выбран (старт) —
 * запрос повторяется, когда адрес станет известен.
 */
@Composable
fun CoverImage(
    coverIds: List<String>,
    modifier: Modifier = Modifier,
    sizeHint: Dp = 160.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    kind: CoverKind = CoverKind.Album,
    contentDescription: String? = null,
    remoteUrl: String? = null,
) {
    val context = LocalContext.current
    val urls = LocalCoverArtUrls.current
    val server by urls.server.collectAsState()
    val px = with(LocalDensity.current) { sizeHint.roundToPx() }
    val sources = remember(coverIds, remoteUrl) { coverIds.distinct() + listOfNotNull(remoteUrl?.takeIf { it.isNotBlank() }) }
    var index by remember(sources, server) { mutableIntStateOf(0) }
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val source = sources.getOrNull(index)
        if (source == null) {
            CoverPlaceholder(kind)
        } else {
            val isRemote = source == remoteUrl && index >= coverIds.distinct().size
            val request = remember(source, px, server) {
                if (isRemote) {
                    ImageRequest.Builder(context).data(source).crossfade(true).build()
                } else {
                    urls.request(context, CoverArtRef(source, px))
                }
            }
            SubcomposeAsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { CoverPlaceholder(kind) },
                error = {
                    CoverPlaceholder(kind)
                    // Не загрузилось — следующий источник цепочки (без сервера не перебираем: дождёмся адреса).
                    if (server != null || isRemote) SideEffect { if (index < sources.size) index++ }
                },
            )
        }
    }
}

@Composable
private fun CoverPlaceholder(kind: CoverKind) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(scheme.surfaceContainerHighest, scheme.surfaceContainerHigh))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(kind.icon, contentDescription = null, tint = scheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(32.dp))
    }
}

@Composable
fun ArtistAvatar(coverArtId: String?, modifier: Modifier = Modifier, sizeHint: Dp = 120.dp) {
    CoverImage(coverArtId, modifier, sizeHint, CircleShape, CoverKind.Artist)
}

/** Обложка плейлиста — коллаж из 4 обложек треков (или одна, если меньше). */
@Composable
fun PlaylistCollage(
    coverIds: List<String>,
    modifier: Modifier = Modifier,
    fallbackCoverId: String? = null,
    shape: Shape = RoundedCornerShape(16.dp),
) {
    val distinct = coverIds.distinct()
    if (distinct.size < 4) {
        CoverImage(distinct.firstOrNull() ?: fallbackCoverId, modifier.aspectRatio(1f), shape = shape, kind = CoverKind.Playlist)
        return
    }
    Column(modifier.aspectRatio(1f).clip(shape)) {
        for (row in 0 until 2) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until 2) {
                    CoverImage(
                        distinct[row * 2 + col],
                        Modifier.weight(1f).fillMaxSize(),
                        sizeHint = 120.dp,
                        shape = RoundedCornerShape(0.dp),
                    )
                }
            }
        }
    }
}
