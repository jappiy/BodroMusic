package ru.bodro.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.bodro.music.R
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.Song
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.app.LocalNowPlayingId
import ru.bodro.music.ui.app.LocalReactions
import ru.bodro.music.ui.app.SongMenuContext
import ru.bodro.music.ui.util.formatDuration

/**
 * Строка трека. Дизлайкнутые треки видны (с иконкой и приглушённо), но не скрываются —
 * пользователь может сознательно их включить.
 */
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    showCover: Boolean = number == null,
    menuContext: SongMenuContext = SongMenuContext(),
    /** Показать в подписи число прослушиваний (группа «Любимые треки»). */
    showPlays: Boolean = false,
) {
    val actions = LocalAppActions.current
    val reaction = LocalReactions.current.of(song)
    val playing = LocalNowPlayingId.current == song.id
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickableCompat(onClick = onClick, onLongClick = { actions.showSongMenu(song, menuContext) })
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .testTag("song-${song.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            showCover -> Box {
                CoverImage(song.coverArt, Modifier.size(48.dp), sizeHint = 48.dp, shape = RoundedCornerShape(10.dp), kind = CoverKind.Song)
                if (playing) {
                    Icon(Icons.Rounded.Equalizer, contentDescription = null, tint = accent, modifier = Modifier.align(Alignment.Center))
                }
            }
            else -> Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (playing) {
                    Icon(Icons.Rounded.Equalizer, contentDescription = stringResource(R.string.now_playing), tint = accent)
                } else {
                    Text(
                        number?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Column(Modifier.weight(1f).alpha(if (reaction == Reaction.Disliked) 0.5f else 1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (playing) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val plays = song.playCount?.takeIf { showPlays && it > 0 }?.toInt()?.let { pluralStringResource(R.plurals.plays, it, it) }
            Text(
                listOfNotNull(song.artist, song.duration.takeIf { it > 0 }?.let { formatDuration(it.toLong()) }, plays).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (reaction) {
            Reaction.Liked -> Icon(
                Icons.Rounded.Favorite,
                contentDescription = stringResource(R.string.reaction_liked),
                tint = accent,
                modifier = Modifier.size(18.dp).testTag("liked-${song.id}"),
            )
            Reaction.Disliked -> Icon(
                painterResource(R.drawable.ic_heart_broken),
                contentDescription = stringResource(R.string.reaction_disliked),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).testTag("disliked-${song.id}"),
            )
            Reaction.None -> Unit
        }
        IconButton(onClick = { actions.showSongMenu(song, menuContext) }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
    }
}
