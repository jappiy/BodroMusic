package ru.bodro.music.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import ru.bodro.music.core.domain.player.StreamQuality
import ru.bodro.music.core.domain.player.TranscodeCodec
import ru.bodro.music.core.domain.wave.WaveCharacter
import ru.bodro.music.core.domain.wave.WaveMood
import ru.bodro.music.core.subsonic.NetworkLogLevel
import ru.bodro.music.di.AppScope

enum class ThemeMode { System, Light, Dark }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val coverColors: Boolean = true,
    val dynamicColor: Boolean = false,
    /** Качество воспроизведения: «Оригинал» или «Медленный интернет» (минимальный битрейт) — одно на все сети. */
    val quality: StreamQuality = StreamQuality.Original,
    val transcodeCodec: TranscodeCodec = TranscodeCodec.Mp3,
    val cacheSizeMb: Int = DEFAULT_CACHE_MB,
    val resumeOnStart: Boolean = true,
    /** Скачивать лайкнутые треки в память телефона для прослушивания без сети. */
    val downloadLiked: Boolean = false,
    val logLevel: NetworkLogLevel = NetworkLogLevel.Normal,
    val waveDiscoveryShare: Float = DEFAULT_DISCOVERY,
    /** Характер волны: доля треков из пула «На пробу» (Любимое 0 %, Микс 25 %, Незнакомое 50 %). */
    val waveCharacter: WaveCharacter = WaveCharacter.Mix,
    /** Настроение волны по признакам звучания (нужен анализ на сервере). */
    val waveMood: WaveMood = WaveMood.Any,
    /** Чарты на главной: порядок (id; новые — в конце) и скрытые. */
    val chartsOrder: List<String> = emptyList(),
    val chartsHidden: Set<String> = emptySet(),
    /** Пропуски раньше этого момента не учитываются волной («Сбросить статистику пропусков»). */
    val skipStatsResetAt: Long = 0,
    /** Анимированный фон «Моей волны» (эквалайзер в цветах обложки). */
    val backgroundAnimation: Boolean = true,
    /** Выбранный режим волны на главной (id режима, пусто — «Моя волна»). Свой у каждой учётки. */
    val waveModeId: String = "",
    val activeAccountId: Long? = null,
) {
    companion object {
        const val DEFAULT_CACHE_MB = 1024
        const val DEFAULT_DISCOVERY = 0.2f
        const val MIN_CACHE_MB = 100
        const val MAX_CACHE_MB = 100_000

        /** Варианты качества в настройках. */
        val QUALITIES = listOf(StreamQuality.Original, StreamQuality.Kbps128)
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    @AppScope scope: CoroutineScope,
) {
    private val store = context.dataStore

    val settings: StateFlow<AppSettings> = store.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { store.data.first().toSettings() })

    /** Текущее значение без подписки (для перехватчиков OkHttp, сервиса плеера). */
    val current: AppSettings get() = settings.value

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val before = prefs.toSettings()
            val updated = transform(before)
            prefs[Keys.theme] = updated.themeMode.name
            prefs[Keys.coverColors] = updated.coverColors
            prefs[Keys.dynamicColor] = updated.dynamicColor
            prefs[Keys.quality] = updated.quality.name
            prefs[Keys.codec] = updated.transcodeCodec.name
            prefs[Keys.cacheSize] = updated.cacheSizeMb
            prefs[Keys.resume] = updated.resumeOnStart
            prefs[Keys.downloadLiked] = updated.downloadLiked
            prefs[Keys.logLevel] = updated.logLevel.name
            prefs[Keys.backgroundAnimation] = updated.backgroundAnimation
            // Волна и чарты — свои у каждой учётки: пишутся под той учёткой, для которой были прочитаны
            // (при переключении учётки значения старой не попадают в новую).
            if (updated.activeAccountId == before.activeAccountId) {
                val owner = before.activeAccountId
                prefs[Keys.discovery.of(owner)] = updated.waveDiscoveryShare
                prefs[Keys.character.of(owner)] = updated.waveCharacter.name
                prefs[Keys.mood.of(owner)] = updated.waveMood.name
                prefs[Keys.waveMode.of(owner)] = updated.waveModeId
                prefs[Keys.chartsOrder.of(owner)] = updated.chartsOrder.joinToString(",")
                prefs[Keys.chartsHidden.of(owner)] = updated.chartsHidden.joinToString(",")
                prefs[Keys.skipReset.of(owner)] = updated.skipStatsResetAt
            }
            val active = updated.activeAccountId
            if (active == null) prefs.remove(Keys.activeAccount) else prefs[Keys.activeAccount] = active
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val account = this[Keys.activeAccount]

        // До 0.22.0 волна была общей для всех учёток: старое значение — запасное для каждой учётки.
        fun <T> perAccount(key: Keys.PerAccount<T>): T? = this[key.of(account)] ?: this[key.base]
        return AppSettings(
            themeMode = enumOr(this[Keys.theme], ThemeMode.System),
            coverColors = this[Keys.coverColors] ?: true,
            dynamicColor = this[Keys.dynamicColor] ?: false,
            quality = enumOr(this[Keys.quality], StreamQuality.Original).let { if (it.isOriginal) it else StreamQuality.Kbps128 },
            transcodeCodec = enumOr(this[Keys.codec], TranscodeCodec.Mp3),
            cacheSizeMb = this[Keys.cacheSize] ?: AppSettings.DEFAULT_CACHE_MB,
            resumeOnStart = this[Keys.resume] ?: true,
            downloadLiked = this[Keys.downloadLiked] ?: false,
            logLevel = enumOr(this[Keys.logLevel], NetworkLogLevel.Normal),
            backgroundAnimation = this[Keys.backgroundAnimation] ?: true,
            waveDiscoveryShare = perAccount(Keys.discovery) ?: AppSettings.DEFAULT_DISCOVERY,
            waveCharacter = enumOr(perAccount(Keys.character), WaveCharacter.Mix),
            waveMood = enumOr(perAccount(Keys.mood), WaveMood.Any),
            waveModeId = this[Keys.waveMode.of(account)].orEmpty(),
            chartsOrder = perAccount(Keys.chartsOrder)?.split(",")?.filter { it.isNotBlank() }.orEmpty(),
            chartsHidden = perAccount(Keys.chartsHidden)?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty(),
            skipStatsResetAt = perAccount(Keys.skipReset) ?: 0,
            activeAccountId = account,
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
        value?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    object Keys {
        val theme = stringPreferencesKey("theme")
        val coverColors = booleanPreferencesKey("cover_colors")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val quality = stringPreferencesKey("quality")
        val codec = stringPreferencesKey("transcode_codec")
        val cacheSize = intPreferencesKey("cache_size_mb")
        val resume = booleanPreferencesKey("resume_on_start")
        val downloadLiked = booleanPreferencesKey("download_liked")
        val logLevel = stringPreferencesKey("log_level")
        val discovery = PerAccount(floatPreferencesKey("wave_discovery"), ::floatPreferencesKey)
        val character = PerAccount(stringPreferencesKey("wave_character"), ::stringPreferencesKey)
        val mood = PerAccount(stringPreferencesKey("wave_mood"), ::stringPreferencesKey)
        val chartsOrder = PerAccount(stringPreferencesKey("charts_order"), ::stringPreferencesKey)
        val chartsHidden = PerAccount(stringPreferencesKey("charts_hidden"), ::stringPreferencesKey)
        val skipReset = PerAccount(longPreferencesKey("skip_stats_reset_at"), ::longPreferencesKey)
        val activeAccount = longPreferencesKey("active_account")
        val backgroundAnimation = booleanPreferencesKey("background_animation")
        val waveMode = PerAccount(stringPreferencesKey("wave_mode"), ::stringPreferencesKey)

        /** Настройка учётки: ключ `wave_mood@12`; без учётки — общий ключ [base]. */
        class PerAccount<T>(val base: Preferences.Key<T>, private val make: (String) -> Preferences.Key<T>) {
            fun of(account: Long?): Preferences.Key<T> = if (account == null) base else make("${base.name}@$account")
        }
    }
}
