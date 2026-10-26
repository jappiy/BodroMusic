package ru.bodro.music.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import ru.bodro.music.core.domain.player.StreamParamsResolver
import ru.bodro.music.core.domain.player.StreamQuality
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.log.PlayerEvent
import ru.bodro.music.data.offline.OfflineStore
import ru.bodro.music.data.settings.SettingsRepository

/** Кэш стрима (LRU). Размер берётся из настроек при первом обращении — изменение применяется после перезапуска. */
@OptIn(UnstableApi::class)
@Singleton
class PlayerCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    val cache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(settings.current.cacheSizeMb * 1024L * 1024L),
            StandaloneDatabaseProvider(context),
        )
    }

    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) { cache.cacheSpace }

    suspend fun clear() = withContext(Dispatchers.IO) {
        cache.keys.toList().forEach { key -> runCatching { cache.removeResource(key) } }
    }
}

/**
 * `bodro://song/<id>` → скачанный файл лайкнутого трека, если он есть,
 * иначе подписанный URL `stream.view` с параметрами качества.
 * Ключ кэша не содержит токенов: `account:song:quality:codec`.
 */
@OptIn(UnstableApi::class)
@Singleton
class StreamDataSourceFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val client: SubsonicClient,
    private val settings: SettingsRepository,
    private val connection: ConnectionManager,
    private val playerCache: PlayerCache,
    private val offline: OfflineStore,
    private val log: LogRepository,
) {
    private val cached: CacheDataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(playerCache.cache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(http))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun create(): DataSource.Factory {
        // DefaultDataSource: file:// (скачанные лайки) — с диска, http(s) — через кэш стрима.
        return ResolvingDataSource.Factory(DefaultDataSource.Factory(context, cached)) { spec -> resolve(spec) }
    }

    /**
     * Скачать в кэш стрима начало трека ([bytes]), чтобы переключение на него началось без ожидания сети.
     * Тот же ключ кэша, что и у плеера. Скачанные лайки и уже закэшированное повторно не качаются.
     *
     * Запрос — без границы (`Range: bytes=0-`), а не первые [bytes] байт: только так кэш запоминает полную длину
     * файла. С ограниченным запросом длина в кэше оставалась неизвестной, и плеер, открывший трек из кэша, не мог
     * перематывать FLAC без SEEKTABLE, Opus и MP3 без заголовка Xing (поиск по файлу требует его длины) —
     * «трек играет, но не перематывается, пока не переключишь».
     */
    suspend fun prefetch(songId: String, bytes: Long) {
        cacheAhead(songId) { cached, lengthKnown -> lengthKnown && cached >= bytes }
    }

    /**
     * Добиться, чтобы кэш знал длину файла трека: докачать «дыру» после закэшированного начала запросом без границы
     * и остановиться, как только сервер ответил с длиной. [full] — скачать трек в кэш целиком (если сервер длину не
     * отдаёт, она станет известна в конце файла). Возвращает длину или [C.LENGTH_UNSET].
     */
    suspend fun ensureContentLength(songId: String, full: Boolean = false): Long {
        cacheAhead(songId) { _, lengthKnown -> lengthKnown && !full }
        return cacheKey(songId)?.let(::knownLength) ?: C.LENGTH_UNSET.toLong()
    }

    private suspend fun cacheAhead(songId: String, done: (cached: Long, lengthKnown: Boolean) -> Boolean) = withContext(Dispatchers.IO) {
        val spec = try {
            resolve(DataSpec.Builder().setUri(MediaItemFactory.songUri(songId)).build(), logOpen = false)
        } catch (_: IOException) {
            return@withContext // нет активной учётки
        }
        if (spec.uri.scheme == "file") return@withContext
        val key = spec.key ?: return@withContext
        if (done(cachedPrefix(key), knownLength(key) != C.LENGTH_UNSET.toLong())) return@withContext
        lateinit var writer: CacheWriter
        writer = CacheWriter(cached.createDataSource(), spec, null) { _, bytesCached, _ ->
            if (done(bytesCached, knownLength(key) != C.LENGTH_UNSET.toLong())) writer.cancel()
        }
        val cancel = coroutineContext[Job]?.invokeOnCompletion { writer.cancel() }
        try {
            writer.cache()
        } catch (_: IOException) {
            // Остановлено по условию, нет сети или отменено — плеер скачает сам.
        } catch (e: IllegalStateException) {
            // Предзагрузка — не повод ронять плеер.
            android.util.Log.w("StreamPrefetch", "Предзагрузка $songId не удалась", e)
        } finally {
            cancel?.dispose()
        }
    }

    private fun knownLength(key: String): Long = ContentMetadata.getContentLength(playerCache.cache.getContentMetadata(key))

    private fun cachedPrefix(key: String): Long = playerCache.cache.getCachedLength(key, 0, Long.MAX_VALUE).coerceAtLeast(0)

    /** Ключ кэша трека при текущих учётке и качестве (`null` — нет учётки или трек скачан в память телефона). */
    private fun cacheKey(songId: String): String? = runCatching {
        resolve(DataSpec.Builder().setUri(MediaItemFactory.songUri(songId)).build(), logOpen = false).key
    }.getOrNull()

    fun currentQuality(): StreamQuality = settings.current.quality

    private fun resolve(spec: DataSpec, logOpen: Boolean = true): DataSpec {
        if (spec.uri.scheme != MediaItemFactory.SCHEME) return spec
        val songId = spec.uri.lastPathSegment ?: throw IOException("Пустой id трека")
        connection.activeAccountId?.let { offline.localFile(it, songId) }?.let { file ->
            if (logOpen && spec.position == 0L) log.event(PlayerEvent.Stream, "Поток $songId: файл в памяти телефона")
            return spec.buildUpon().setUri(Uri.fromFile(file)).setKey(null).build()
        }
        val quality = currentQuality()
        val codec = settings.current.transcodeCodec
        val params = StreamParamsResolver.resolve(quality, codec)
        val url = try {
            client.streamUrl(songId, params.maxBitRate, params.format)
        } catch (e: Exception) {
            throw IOException("Нет активной учётки", e)
        }
        val keyQuality = if (quality.isOriginal) quality.name else "${quality.name}-${codec.name}"
        val key = "${connection.activeAccountId}:$songId:$keyQuality"
        if (logOpen) logStreamOpen(songId, spec.position, key, url, params.format)
        return spec.buildUpon()
            .setUri(Uri.parse(url.toString()))
            .setKey(key)
            .build()
    }

    /**
     * Журнал открытия потока (в том числе после перемотки): адрес без токенов, формат, транскодирование, откуда
     * пойдут данные (кэш / сеть) и знает ли кэш длину файла — без длины перемотка во многих форматах невозможна.
     */
    private fun logStreamOpen(songId: String, position: Long, key: String, url: okhttp3.HttpUrl, format: String?) {
        val safe = url.newBuilder().apply { SECRET_PARAMS.forEach(::removeAllQueryParameters) }.build()
        val cachedHere = playerCache.cache.getCachedLength(key, position, Long.MAX_VALUE)
        val source = if (cachedHere > 0) "кэш ${cachedHere / 1024} КБ, затем сеть" else "сеть"
        val length = knownLength(key).takeIf { it != C.LENGTH_UNSET.toLong() }?.let { "${it / 1024} КБ" } ?: "неизвестна"
        val transcode = if (format == null || format == "raw") "оригинал" else "транскодирование $format"
        val what = if (position == 0L) "открыт" else "открыт с ${position / 1024} КБ (перемотка)"
        log.event(
            PlayerEvent.Stream,
            "Поток $songId $what: $transcode, $source, длина $length",
            details = "${safe.encodedPath}?${safe.encodedQuery.orEmpty()}",
        )
    }

    private companion object {
        /** Параметры авторизации Subsonic — в журнал не пишутся. */
        val SECRET_PARAMS = listOf("u", "t", "s", "p", "apiKey")
    }
}
