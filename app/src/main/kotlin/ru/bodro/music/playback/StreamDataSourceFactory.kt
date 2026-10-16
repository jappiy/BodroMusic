package ru.bodro.music.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
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
     */
    suspend fun prefetch(songId: String, bytes: Long) = withContext(Dispatchers.IO) {
        val spec = try {
            resolve(DataSpec.Builder().setUri(MediaItemFactory.songUri(songId)).setLength(bytes).build())
        } catch (_: IOException) {
            return@withContext // нет активной учётки
        }
        if (spec.uri.scheme == "file") return@withContext
        val writer = CacheWriter(cached.createDataSource(), spec, null, null)
        val cancel = coroutineContext[Job]?.invokeOnCompletion { writer.cancel() }
        try {
            writer.cache()
        } catch (_: IOException) {
            // Нет сети или отменено — плеер скачает сам.
        } catch (e: IllegalStateException) {
            // Предзагрузка — не повод ронять плеер.
            android.util.Log.w("StreamPrefetch", "Предзагрузка $songId не удалась", e)
        } finally {
            cancel?.dispose()
        }
    }

    fun currentQuality(): StreamQuality = settings.current.quality

    private fun resolve(spec: DataSpec): DataSpec {
        if (spec.uri.scheme != MediaItemFactory.SCHEME) return spec
        val songId = spec.uri.lastPathSegment ?: throw IOException("Пустой id трека")
        connection.activeAccountId?.let { offline.localFile(it, songId) }?.let { file ->
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
        return spec.buildUpon()
            .setUri(Uri.parse(url.toString()))
            .setKey("${connection.activeAccountId}:$songId:$keyQuality")
            .build()
    }
}
