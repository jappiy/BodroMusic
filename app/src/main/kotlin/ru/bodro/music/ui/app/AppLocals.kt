package ru.bodro.music.ui.app

import androidx.compose.runtime.staticCompositionLocalOf
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.repository.ReactionState

/** Сообщение в снекбаре; с [actionLabel] — кнопка действия (например, «Отменить»). */
data class UiMessage(val text: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

/** Контекст, из которого открыто меню трека (например, плейлист — чтобы показать «Удалить из плейлиста»). */
data class SongMenuContext(val playlistId: String? = null, val indexInPlaylist: Int? = null, val onRemoved: (() -> Unit)? = null)

/** Действия уровня приложения, доступные любому экрану. */
interface AppActions {
    fun play(songs: List<Song>, index: Int = 0, source: QueueSource = QueueSource.CustomSource(), shuffle: Boolean = false)

    fun playNext(songs: List<Song>)

    fun addToQueue(songs: List<Song>)

    fun toggleLike(song: Song)

    fun toggleDislike(song: Song)

    /** «Не нравится» на главной: трек убирается из волны (сразу следующий), в снекбаре — «Отменить». */
    fun dislikeFromWave(song: Song) = toggleDislike(song)

    fun startWave(settings: WaveSettings)

    fun showSongMenu(song: Song, context: SongMenuContext = SongMenuContext())

    fun addToPlaylist(songs: List<Song>)

    fun showMessage(message: String)

    /** «Похожие по звучанию»: очередь из ближайших по звуку треков библиотеки. */
    fun playSimilar(song: Song) {}
}

/** Навигация, доступная любому экрану. */
interface Navigator {
    fun openAlbum(id: String)

    fun openArtist(id: String)

    fun openPlaylist(id: String)

    fun openGenre(name: String)

    fun openWave()

    fun openPlayer()

    fun openLogs()

    fun back()
}

val LocalAppActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }
val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }

/** Реакции (лайк/дизлайк) с учётом локальных изменений — для иконок в списках. */
val LocalReactions = staticCompositionLocalOf { ReactionState() }

/** id играющего трека — для подсветки в списках. */
val LocalNowPlayingId = staticCompositionLocalOf<String?> { null }

/** Запросы извне (нажатие на уведомление): открыть плеер, сыграть скачанный трек, открыть загрузки. */
sealed interface ExternalRequest {
    data object OpenPlayer : ExternalRequest

    data class PlaySong(val songId: String) : ExternalRequest

    data object OpenDownloads : ExternalRequest
}
