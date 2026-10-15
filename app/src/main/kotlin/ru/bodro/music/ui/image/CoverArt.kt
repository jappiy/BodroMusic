package ru.bodro.music.ui.image

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.ConnectionManager

/** Обложка Subsonic: id + размер. URL подписывается в момент загрузки, ключ кэша — стабильный. */
data class CoverArtRef(val id: String, val sizePx: Int)

@Singleton
class CoverArtUrls @Inject constructor(
    private val client: SubsonicClient,
    private val connection: ConnectionManager,
) {
    /** Размер запроса округляем вверх до «ступенек», чтобы кэш не дробился. */
    fun normalizedSize(sizePx: Int): Int = SIZES.firstOrNull { it >= sizePx } ?: SIZES.last()

    fun url(ref: CoverArtRef): String? = runCatching {
        client.coverArtUrl(ref.id, normalizedSize(ref.sizePx)).toString()
    }.getOrNull()

    /** Стабильный ключ (без t/s): сервер + id + размер. */
    fun cacheKey(ref: CoverArtRef): String {
        val server = connection.current()?.let { "${it.baseUrl.host}:${it.baseUrl.port}" } ?: "none"
        return "cover:$server:${ref.id}:${normalizedSize(ref.sizePx)}"
    }

    fun request(context: Context, ref: CoverArtRef): ImageRequest {
        val key = cacheKey(ref)
        return ImageRequest.Builder(context)
            .data(url(ref))
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .crossfade(true)
            .build()
    }

    private companion object {
        val SIZES = listOf(96, 192, 300, 600, 1000)
    }
}

@Singleton
class ImageLoaderFactory @Inject constructor(private val http: OkHttpClient) {
    fun create(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("covers").toOkioPath())
                .maxSizeBytes(300L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()
}

/** Цвета обложки для фона плеера и мини-плеера. */
data class CoverColors(val background: Color, val accent: Color, val onBackground: Color)

@Singleton
class CoverPaletteRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val urls: CoverArtUrls,
) {
    private val cache = LruCache<String, CoverColors>(64)

    suspend fun colorsFor(coverArtId: String?): CoverColors? {
        coverArtId ?: return null
        val ref = CoverArtRef(coverArtId, 192)
        val key = urls.cacheKey(ref)
        return cache.get(key) ?: load(ref, key)?.also { cache.put(key, it) }
    }

    private suspend fun load(ref: CoverArtRef, key: String): CoverColors? {
        val request = ImageRequest.Builder(context)
            .data(urls.url(ref))
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        return withContext(Dispatchers.Default) { extract(result.image.toBitmap()) }
    }

    private fun extract(bitmap: Bitmap): CoverColors? {
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        val backgroundSwatch = palette.darkMutedSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch ?: return null
        val accentSwatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch ?: backgroundSwatch
        return CoverColors(
            background = Color(backgroundSwatch.rgb),
            accent = Color(accentSwatch.rgb),
            onBackground = Color(backgroundSwatch.bodyTextColor).copy(alpha = 1f),
        )
    }
}
