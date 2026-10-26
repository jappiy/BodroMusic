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
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.di.AppScope

/** Обложка Subsonic: id + размер. URL подписывается в момент загрузки, ключ кэша — стабильный. */
data class CoverArtRef(val id: String, val sizePx: Int)

/**
 * Цепочка обложки трека: своя обложка (`mf-…`: Navidrome сам берёт вшитую, затем обложку альбома) → обложка альбома →
 * фото исполнителя. Следующая пробуется, если предыдущая не загрузилась; все не загрузились — заглушка.
 */
fun Song.coverChain(): List<String> = listOfNotNull(coverArt, albumId?.let { "al-$it" }, artistId?.let { "ar-$it" }).distinct()

/** Обложка трека из библиотеки по одному id (похожие, чарты, загрузки): Navidrome сам дойдёт до обложки альбома. */
fun librarySongCover(songId: String): String = "mf-$songId"

@Singleton
class CoverArtUrls @Inject constructor(
    private val client: SubsonicClient,
    private val connection: ConnectionManager,
    @AppScope scope: CoroutineScope,
) {
    /**
     * Текущий сервер (`хост:порт`) — ключ для перезапроса обложек. Пока адрес не выбран (старт приложения), URL
     * обложки не подписать; раньше такая обложка навсегда оставалась заглушкой, теперь перезапрашивается при выборе.
     */
    val server: StateFlow<String?> = connection.selection
        .map { connection.current()?.let { "${it.baseUrl.host}:${it.baseUrl.port}" } }
        .stateIn(scope, SharingStarted.Eagerly, connection.current()?.let { "${it.baseUrl.host}:${it.baseUrl.port}" })

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

    /**
     * Запрос обложки: данные — [CoverArtRef], подписанный URL строит [CoverArtMapper] в момент загрузки (токен не
     * протухает в списке, смена LAN/WAN подхватывается). Ключи кэша — стабильные, без токенов.
     */
    fun request(context: Context, ref: CoverArtRef): ImageRequest {
        val key = cacheKey(ref)
        return ImageRequest.Builder(context)
            .data(ref)
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .crossfade(true)
            .build()
    }

    private companion object {
        val SIZES = listOf(96, 192, 300, 600, 1000)
    }
}

/** [CoverArtRef] → подписанный URL `getCoverArt` в момент загрузки (`null` — сервер ещё не выбран, будет ошибка). */
class CoverArtMapper(private val urls: CoverArtUrls) : Mapper<CoverArtRef, String> {
    override fun map(data: CoverArtRef, options: Options): String? = urls.url(data)
}

/** Ключ памяти для [CoverArtRef] — как у [CoverArtUrls.cacheKey]. */
class CoverArtKeyer(private val urls: CoverArtUrls) : Keyer<CoverArtRef> {
    override fun key(data: CoverArtRef, options: Options): String = urls.cacheKey(data)
}

@Singleton
class ImageLoaderFactory @Inject constructor(private val http: OkHttpClient, private val urls: CoverArtUrls) {
    fun create(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(CoverArtMapper(urls))
            add(CoverArtKeyer(urls))
            add(OkHttpNetworkFetcherFactory(callFactory = { http }))
        }
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

/**
 * Цвета обложки для фона плеера и мини-плеера. [glow] — доминантный цвет и 1–2 акцента (разные по оттенку) для
 * свечения «Моей волны».
 */
data class CoverColors(
    val background: Color,
    val accent: Color,
    val onBackground: Color,
    val glow: List<Color> = listOf(accent),
)

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
            .data(ref)
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
            glow = glowColors(palette),
        )
    }

    /** Доминантный цвет, затем самые заметные акценты, отличные от него по оттенку (≥ 25°). */
    private fun glowColors(palette: Palette): List<Color> {
        val swatches = listOfNotNull(
            palette.dominantSwatch,
            palette.vibrantSwatch,
            palette.lightVibrantSwatch,
            palette.darkVibrantSwatch,
            palette.mutedSwatch,
            palette.lightMutedSwatch,
        )
        val chosen = mutableListOf<Palette.Swatch>()
        for (swatch in swatches.sortedByDescending { if (it === palette.dominantSwatch) Int.MAX_VALUE else it.population }) {
            if (chosen.size == GLOW_COLORS) break
            if (chosen.none { hueDistance(it.hsl[0], swatch.hsl[0]) < MIN_HUE_DISTANCE }) chosen += swatch
        }
        return chosen.map { Color(it.rgb) }
    }

    private fun hueDistance(a: Float, b: Float): Float = abs(a - b).let { minOf(it, 360f - it) }

    private companion object {
        const val GLOW_COLORS = 3
        const val MIN_HUE_DISTANCE = 25f
    }
}
