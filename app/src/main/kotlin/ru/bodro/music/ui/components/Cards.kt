package ru.bodro.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.bodro.music.R
import ru.bodro.music.core.model.Album
import ru.bodro.music.core.model.Artist
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.Song

/** Крупный заголовок секции (как на главной Symfonium) со стрелкой «Все». */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, onShowAll: (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onShowAll != null) Modifier.clickable(onClick = onShowAll) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onShowAll != null) {
            Text(
                stringResource(R.string.action_all),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Горизонтальная карусель внутри LazyColumn. */
fun <T> LazyListScope.carousel(
    key: String,
    title: @Composable () -> String,
    items: List<T>,
    onShowAll: (() -> Unit)? = null,
    itemKey: (T) -> Any,
    content: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "$key-header") { SectionHeader(title(), onShowAll = onShowAll) }
    item(key = "$key-row") {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items.size, key = { itemKey(items[it]) }) { content(items[it]) }
        }
    }
}

@Composable
fun AlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = 148.dp) {
    val sizeModifier = if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()
    Column(modifier.then(sizeModifier).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).testTag("album-${album.id}")) {
        CoverImage(album.coverArt, Modifier.fillMaxWidth().aspectRatio(1f), sizeHint = width ?: 220.dp, shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(8.dp))
        Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(album.artist, album.year?.toString()).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Большая карточка «Последние прослушанные»: подпись поверх градиента. */
@Composable
fun BigAlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 260.dp) {
    Box(modifier.width(width).aspectRatio(1f).clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick)) {
        CoverImage(album.coverArt, Modifier.fillMaxSize(), sizeHint = width, shape = RoundedCornerShape(0.dp))
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(
                album.name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            album.artist?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ArtistCircle(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 112.dp, subtitle: String? = null) {
    Column(
        modifier.width(size).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtistAvatar(artist.coverArt, Modifier.size(size), sizeHint = size)
        Spacer(Modifier.height(8.dp))
        Text(
            artist.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun SongCard(song: Song, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 136.dp) {
    Column(
        modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickableCompat(onClick = onClick, onLongClick = onLongClick),
    ) {
        CoverImage(
            song.coverArt,
            Modifier.fillMaxWidth().aspectRatio(1f),
            sizeHint = width,
            shape = RoundedCornerShape(16.dp),
            kind = CoverKind.Song,
        )
        Spacer(Modifier.height(8.dp))
        Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            song.artist.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PlaylistCard(playlist: Playlist, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 148.dp) {
    Column(modifier.width(width).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        CoverImage(
            playlist.coverArt,
            Modifier.fillMaxWidth().aspectRatio(1f),
            sizeHint = width,
            shape = RoundedCornerShape(16.dp),
            kind = CoverKind.Playlist,
        )
        Spacer(Modifier.height(8.dp))
        Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            androidx.compose.ui.res.pluralStringResource(R.plurals.tracks, playlist.songCount, playlist.songCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
