package ru.bodro.music.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.bodro.music.core.domain.wave.LocalWaveSource
import ru.bodro.music.core.domain.wave.WaveEngine
import ru.bodro.music.core.domain.wave.WaveLibrary
import ru.bodro.music.core.domain.wave.WaveProfile
import ru.bodro.music.core.domain.wave.WaveProfileBuilder
import ru.bodro.music.core.domain.wave.WaveRequest
import ru.bodro.music.core.model.AlbumListType
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveMode
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.log.PlayerEvent
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.di.Clock

/** Доступ к медиатеке для волны поверх SubsonicClient (с коротким кэшем). */
class SubsonicWaveLibrary(
    private val client: SubsonicClient,
    private val library: LibraryRepository,
    private val reactions: ReactionRepository,
    private val random: () -> Random,
) : WaveLibrary {
    override suspend fun starredSongs(): List<Song> {
        val cached = reactions.likedSongs()
        return cached.ifEmpty { library.starred().songs }
    }

    override suspend fun albumListSongs(type: AlbumListType, albumCount: Int): List<Song> = coroutineScope {
        val albums = client.getAlbumList2(type, size = albumCount * 3).shuffled(random()).take(albumCount)
        albums.map { album -> async { runCatching { library.album(album.id).songs }.getOrDefault(emptyList()) } }.flatMap { it.await() }
    }

    override suspend fun artistSongs(artistId: String, limit: Int): List<Song> =
        library.artistSongs(artistId, limit * 2).shuffled(random()).take(limit)

    override suspend fun genreSongs(genre: String, count: Int): List<Song> {
        // Случайное смещение, чтобы не получать каждый раз одни и те же треки жанра.
        val total = library.genres().firstOrNull { it.name.equals(genre, true) }?.songCount ?: count
        val offset = if (total > count) random().nextInt(0, total - count + 1) else 0
        return client.getSongsByGenre(genre, count, offset)
    }

    override suspend fun randomSongs(count: Int, genre: String?, fromYear: Int?, toYear: Int?): List<Song> =
        client.getRandomSongs(count, genre, fromYear, toYear)

    override suspend fun similarSongs(artistId: String, count: Int): List<Song> = client.getSimilarSongs2(artistId, count)

    override suspend fun songs(ids: List<String>): List<Song> = coroutineScope {
        ids.take(MAX_SEED_SONGS).map { id -> async { runCatching { client.getSong(id) }.getOrNull() } }.mapNotNull { it.await() }
    }

    private companion object {
        const val MAX_SEED_SONGS = 60
    }
}

/** «Моя волна» и миксы: профиль пользователя + [WaveEngine]. */
@Singleton
class WaveRepository @Inject constructor(
    private val client: SubsonicClient,
    private val library: LibraryRepository,
    private val reactions: ReactionRepository,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
    private val connection: ConnectionManager,
    private val log: LogRepository,
    private val clock: Clock,
) {
    private val random = Random(clock.now())
    private val mutex = Mutex()
    private val engine = WaveEngine(
        sources = listOf(LocalWaveSource(SubsonicWaveLibrary(client, library, reactions) { random })),
        discoveryShare = { settings.current.waveDiscoveryShare.toDouble() },
    )

    suspend fun profile(): WaveProfile {
        val now = clock.now()
        val liked = reactions.likedSongs().ifEmpty { runCatching { library.starred().songs }.getOrDefault(emptyList()) }
        val disliked = reactions.dislikedSongs()
        val historyItems = history.items(now - HISTORY_WINDOW_MS)
        return WaveProfileBuilder.build(liked, disliked, historyItems, now, settings.current.skipStatsResetAt)
    }

    /** Следующая порция волны (20 треков). */
    suspend fun nextBatch(
        settings: WaveSettings,
        currentSong: Song?,
        queue: List<Song>,
        batchSize: Int = WaveEngine.BATCH_SIZE,
    ): List<Song> = mutex.withLock {
        val resolved = resolveDecade(settings)
        val profile = profile()
        // Дизлайкнутые прямо сейчас (ещё не в Room) тоже исключаем.
        val state = reactions.state.value
        val fullProfile = profile.copy(dislikedSongIds = profile.dislikedSongIds + state.dislikedIds)
        val request = WaveRequest(
            settings = resolved,
            profile = fullProfile,
            currentSong = currentSong,
            recentSongs = queue,
            batchSize = batchSize,
            nowMs = clock.now(),
            random = Random(random.nextLong()),
        )
        val batch = engine.nextBatch(request).filter { state.of(it) != Reaction.Disliked }
        log.event(PlayerEvent.Wave, "Волна (${resolved.mode}, ${resolved.seed::class.simpleName}): +${batch.size} треков")
        batch
    }

    /** Для «Микса десятилетия» без выбранного десятилетия — любимое десятилетие пользователя. */
    private suspend fun resolveDecade(settings: WaveSettings): WaveSettings {
        if (settings.mode != WaveMode.DecadeMix || settings.decade != null) return settings
        val decades = favoriteDecades()
        val decade = decades.take(3).randomOrNull(random) ?: return settings.copy(mode = WaveMode.Default)
        return settings.copy(decade = decade)
    }

    /** Десятилетия по лайкам и истории, по убыванию популярности. */
    suspend fun favoriteDecades(): List<Int> {
        val songs = reactions.likedSongs() + history.songsSince(clock.now() - HISTORY_WINDOW_MS)
        val decades = songs.mapNotNull { it.year }.map { it / 10 * 10 }
        return decades.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Топ-8 жанров пользователя для чипов; если истории нет — самые большие жанры медиатеки. */
    suspend fun topGenres(limit: Int = 8): List<String> {
        val fromProfile = runCatching { profile().topGenres }.getOrDefault(emptyList())
        if (fromProfile.size >= limit) return fromProfile.distinctBy { it.trim().lowercase() }.take(limit)
        val fromLibrary = runCatching { library.genres().map { it.name } }.getOrDefault(emptyList())
        // «Rock» и «rock» — один жанр.
        return (fromProfile + fromLibrary).distinctBy { it.trim().lowercase() }.take(limit)
    }

    val activeAccountId: Long? get() = connection.activeAccountId

    private companion object {
        const val HISTORY_WINDOW_MS = 120L * 24 * 60 * 60 * 1000
    }
}
