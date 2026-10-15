package ru.bodro.music.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import ru.bodro.music.core.domain.player.StreamParamsResolver
import ru.bodro.music.core.domain.player.StreamQuality
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.ConnectionManager
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
 * `bodro://song/<id>` → подписанный URL `stream.view` с параметрами качества (Wi-Fi / мобильная сеть).
 * Ключ кэша не содержит токенов: `account:song:quality:codec`.
 */
@OptIn(UnstableApi::class)
@Singleton
class StreamDataSourceFactory @Inject constructor(
    private val http: OkHttpClient,
    private val client: SubsonicClient,
    private val settings: SettingsRepository,
    private val connection: ConnectionManager,
    private val playerCache: PlayerCache,
) {
    fun create(): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(http)
        val cached = CacheDataSource.Factory()
            .setCache(playerCache.cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ResolvingDataSource.Factory(cached) { spec -> resolve(spec) }
    }

    fun currentQuality(): StreamQuality =
        if (connection.isOnUnmeteredNetwork) settings.current.qualityWifi else settings.current.qualityMobile

    private fun resolve(spec: DataSpec): DataSpec {
        if (spec.uri.scheme != MediaItemFactory.SCHEME) return spec
        val songId = spec.uri.lastPathSegment ?: throw IOException("Пустой id трека")
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
