package ru.bodro.music.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.bodro.music.core.domain.wave.LocalWaveSource
import ru.bodro.music.core.domain.wave.PoolMixer
import ru.bodro.music.core.domain.wave.WaveCharacter
import ru.bodro.music.core.domain.wave.WaveEngine
import ru.bodro.music.core.domain.wave.WaveLibrary
import ru.bodro.music.core.domain.wave.WaveMood
import ru.bodro.music.core.domain.wave.WavePreset
import ru.bodro.music.core.domain.wave.WaveProfile
import ru.bodro.music.core.domain.wave.WaveProfileBuilder
import ru.bodro.music.core.domain.wave.WaveRequest
import ru.bodro.music.core.downloads.WaveModeInfo
import ru.bodro.music.core.downloads.WaveNextRequest
import ru.bodro.music.core.downloads.WaveReport
import ru.bodro.music.core.downloads.WaveSessionEvent
import ru.bodro.music.core.model.AlbumListType
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveMode
import ru.bodro.music.core.model.WaveSeed
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.log.PlayerEvent
import ru.bodro.music.data.reco.PoolRepository
import ru.bodro.music.data.reco.SoundRepository
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

/** Ответ сервера о режиме: сколько треков подходит и не мало ли их. */
data class WaveCoverage(val mode: String, val matched: Int, val lowCoverage: Boolean)

/** «Моя волна» и миксы: подбор на сервере (bodrod 0.19.0+), иначе профиль пользователя + [WaveEngine]. */
@Singleton
class WaveRepository @Inject constructor(
    private val client: SubsonicClient,
    private val library: LibraryRepository,
    private val reactions: ReactionRepository,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
    private val connection: ConnectionManager,
    private val log: LogRepository,
    private val pool: PoolRepository,
    private val sound: SoundRepository,
    private val downloads: DownloadsRepository,
    private val clock: Clock,
) {
    private val random = Random(clock.now())
    private val mutex = Mutex()
    private var serverWave: Pair<Long, Boolean>? = null
    private val _coverage = MutableStateFlow<WaveCoverage?>(null)

    /** Сколько треков под текущий режим (по ответу bodrod); `lowCoverage` — предложить докачать похожее. */
    val coverage: StateFlow<WaveCoverage?> = _coverage.asStateFlow()
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
        serverBatch(settings, currentSong, queue, batchSize)?.let { return@withLock it }
        val resolved = resolveDecade(settings)
        val profile = profile()
        // Дизлайкнутые прямо сейчас (ещё не в Room) тоже исключаем.
        val state = reactions.state.value
        val fullProfile = profile.copy(dislikedSongIds = profile.dislikedSongIds + state.dislikedIds)
        // Настроение волны: кандидатов втрое больше, потом отбор по признакам звучания. Режим с главной
        // («Бодрое», «Спокойное»…) задаёт настроение поверх настроек.
        val preset = WavePreset.of(settings.preset)
        val mood = preset?.mood ?: this.settings.current.waveMood
        val wanted = if (mood != WaveMood.Any && sound.isSupported()) batchSize * MOOD_OVERSAMPLE else batchSize
        val request = WaveRequest(
            settings = resolved,
            profile = fullProfile,
            currentSong = currentSong,
            recentSongs = queue,
            batchSize = wanted,
            nowMs = clock.now(),
            random = Random(random.nextLong()),
        )
        val raw = withSoundAlike(resolved, engine.nextBatch(request), queue, wanted).filter { state.of(it) != Reaction.Disliked }
        val familiar = sound.arrange(raw, mood, batchSize, currentSong)
        val batch = withPool(familiar, resolved, fullProfile, queue, preset?.character)
        log.event(PlayerEvent.Wave, "Волна (${resolved.mode}, ${resolved.seed::class.simpleName}): +${batch.size} треков")
        batch
    }

    /**
     * Волна по треку: к кандидатам движка (исполнитель, жанр, похожие исполнители) подмешиваются похожие по звучанию
     * треки библиотеки (bodrod с анализом), через один, — дальше [SoundArranger] выстраивает их плавно.
     */
    private suspend fun withSoundAlike(settings: WaveSettings, engineSongs: List<Song>, queue: List<Song>, wanted: Int): List<Song> {
        val seed = settings.trackSeed ?: return engineSongs
        if (!sound.isSupported()) return engineSongs
        val played = queue.mapTo(mutableSetOf()) { it.id } + seed.songId
        val alike = runCatching { sound.similar(Song(id = seed.songId, title = seed.title)) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
            .filter { it.id !in played }
            .shuffled(random)
            .take(wanted / 2)
        if (alike.isEmpty()) return engineSongs
        val rest = engineSongs.filter { s -> s.id !in played && alike.none { it.id == s.id } }
        val mixed = mutableListOf<Song>()
        val a = alike.iterator()
        val b = rest.iterator()
        while (mixed.size < wanted && (a.hasNext() || b.hasNext())) {
            if (a.hasNext()) mixed += a.next()
            if (b.hasNext() && mixed.size < wanted) mixed += b.next()
        }
        return mixed
    }

    /** Подбирает ли волну bodrod (0.19.0+, режимы и темпоритм). Ответ запоминается на учётку. */
    private suspend fun serverSupported(): Boolean {
        val accountId = connection.activeAccountId ?: return false
        serverWave?.takeIf { it.first == accountId }?.let { return it.second }
        if (!downloads.isConfigured) return false
        val value = runCatching { downloads.info().wave }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        value?.let { serverWave = accountId to it }
        return value == true
    }

    /**
     * Порция с сервера: «Моя волна» по всей медиатеке и режимы с главной. Миксы, волна по артисту, треку, жанру и
     * эпохе — локальный движок. Сервер недоступен или вернул пусто — тоже локальный движок (`null`).
     */
    private suspend fun serverBatch(settings: WaveSettings, currentSong: Song?, queue: List<Song>, batchSize: Int): List<Song>? {
        val localPreset = WavePreset.of(settings.preset)
        val local = settings.mode in LOCAL_MODES ||
            settings.genre != null ||
            settings.decade != null ||
            (settings.seed != WaveSeed.Everything && settings.seed != WaveSeed.Collection)
        if (local || !serverSupported()) return null
        val current = this.settings.current
        val request = WaveNextRequest(
            mode = settings.preset ?: "default",
            mood = (localPreset?.mood ?: current.waveMood).takeIf { it != WaveMood.Any }?.name?.lowercase(),
            lang = current.waveLanguage.ifBlank { null },
            character = (localPreset?.character ?: current.waveCharacter).name.lowercase(),
            currentId = currentSong?.id,
            recent = queue.map { it.id }.takeLast(MAX_RECENT),
            session = sessionEvents(queue),
            count = batchSize,
        )
        val answer = runCatching { downloads.waveNext(request) }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { log.event(PlayerEvent.Wave, "Волна на сервере недоступна: ${it.message} — подбор на телефоне") }
            .getOrNull() ?: return null
        _coverage.value = WaveCoverage(request.mode, answer.matched, answer.lowCoverage)
        val state = reactions.state.value
        val songs = SubsonicWaveLibrary(client, library, reactions) { random }.songs(answer.songIds)
            .filter { state.of(it) != Reaction.Disliked }
        if (songs.isEmpty()) return null
        val poolIds = pool.visibility.value.songIds
        songs.filter { it.id in poolIds }.forEach { pool.track(it, "shown", "wave") }
        log.event(PlayerEvent.Wave, "Волна на сервере (${request.mode}): +${songs.size} треков, под режим ${answer.matched}")
        return songs
    }

    /** Реакции сессии для сервера: лайк, дизлайк, пропуск в первые 30 с, дослушал — по трекам текущей очереди. */
    private suspend fun sessionEvents(queue: List<Song>): List<WaveSessionEvent> {
        val ids = queue.map { it.id }.takeLast(MAX_RECENT).toSet()
        if (ids.isEmpty()) return emptyList()
        val state = reactions.state.value
        val plays = history.items(clock.now() - SESSION_WINDOW_MS).filter { it.songId in ids }.associateBy { it.songId }
        return queue.filter { it.id in ids }.mapNotNull { song ->
            when {
                state.of(song) == Reaction.Disliked -> WaveSessionEvent(song.id, "dislike")
                state.of(song) == Reaction.Liked && plays.containsKey(song.id) -> WaveSessionEvent(song.id, "like")
                plays[song.id]?.skippedEarly == true -> WaveSessionEvent(song.id, "skip")
                plays.containsKey(song.id) -> WaveSessionEvent(song.id, "complete")
                else -> null
            }
        }
    }

    /** Режимы с сервера для главной (статические с числом треков и «В стиле X», «Новый релиз X»); `null` — нет. */
    suspend fun serverModes(): List<WaveModeInfo>? =
        if (serverSupported()) {
            runCatching {
                downloads.waveModes()
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        } else {
            null
        }

    /** Отчёт об анализе и режимах (`null` — сервер не подбирает волну). */
    suspend fun report(): WaveReport? =
        if (serverSupported()) {
            runCatching {
                downloads.waveReport()
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        } else {
            null
        }

    /** «Моя волна» по всей медиатеке: подмешать треки пула «На пробу» по характеру волны (показ — событие для bodrod). */
    private suspend fun withPool(
        batch: List<Song>,
        settings: WaveSettings,
        profile: WaveProfile,
        queue: List<Song>,
        character: WaveCharacter? = null,
    ): List<Song> {
        if (settings.seed != WaveSeed.Everything && settings.seed != WaveSeed.Collection) return batch
        val share = (character ?: this.settings.current.waveCharacter).poolShare
        val poolIds = pool.visibility.value.songIds
        if (poolIds.isEmpty()) return batch
        val state = reactions.state.value
        val candidates = if (share > 0.0) {
            pool.songs().filter { it.id !in profile.dislikedSongIds && state.of(it) != Reaction.Disliked }
        } else {
            emptyList()
        }
        val mixed = PoolMixer.mix(batch, candidates.filter { pool.inWave(it.id) }, share, Random(random.nextLong()), queue, poolIds)
        mixed.filter { it.id in poolIds }.forEach { pool.track(it, "shown", "wave") }
        return mixed
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
        const val MAX_RECENT = 200
        const val SESSION_WINDOW_MS = 12L * 60 * 60 * 1000
        val LOCAL_MODES = setOf(WaveMode.TrackMix, WaveMode.AlbumMix, WaveMode.DecadeMix)
        const val MOOD_OVERSAMPLE = 3
        const val HISTORY_WINDOW_MS = 120L * 24 * 60 * 60 * 1000
    }
}
