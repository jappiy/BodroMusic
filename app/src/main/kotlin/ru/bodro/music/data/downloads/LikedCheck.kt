package ru.bodro.music.data.downloads

import javax.inject.Inject
import javax.inject.Singleton
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.data.repository.ReactionRepository

/**
 * «Любимое» без удалённых треков: спрашивает bodrod, каких лайкнутых треков больше нет в библиотеке
 * (удалены через «На удаление», пропали с диска, убраны регламентом как дубли). Если трек заменён
 * оставшейся копией — лайк переносится на неё.
 */
@Singleton
class LikedCheck @Inject constructor(
    private val downloads: DownloadsRepository,
    private val reactions: ReactionRepository,
    private val library: LibraryRepository,
) {
    suspend fun run() {
        if (!downloads.isConfigured) return
        val liked = reactions.likedSongs().map { it.id }
        if (liked.isEmpty()) return
        // Старый bodrod (до 0.4.0) или нет доступа к БД Navidrome — проверку пропускаем.
        val result = runCatching { downloads.checkLibrary(liked) }.getOrNull() ?: return
        for (new in result.replaced.values) {
            runCatching { library.song(new) }.getOrNull()?.let { song ->
                if (reactions.reactionOf(song) != Reaction.Liked) reactions.setReaction(song, Reaction.Liked)
            }
        }
        reactions.forget(result.missing + result.replaced.keys)
    }
}
