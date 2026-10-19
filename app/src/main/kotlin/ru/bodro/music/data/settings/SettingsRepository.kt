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
            val updated = transform(prefs.toSettings())
            prefs[Keys.theme] = updated.themeMode.name
            prefs[Keys.coverColors] = updated.coverColors
            prefs[Keys.dynamicColor] = updated.dynamicColor
            prefs[Keys.quality] = updated.quality.name
            prefs[Keys.codec] = updated.transcodeCodec.name
            prefs[Keys.cacheSize] = updated.cacheSizeMb
            prefs[Keys.resume] = updated.resumeOnStart
            prefs[Keys.downloadLiked] = updated.downloadLiked
            prefs[Keys.logLevel] = updated.logLevel.name
            prefs[Keys.discovery] = updated.waveDiscoveryShare
            prefs[Keys.character] = updated.waveCharacter.name
            prefs[Keys.mood] = updated.waveMood.name
            prefs[Keys.chartsOrder] = updated.chartsOrder.joinToString(",")
            prefs[Keys.chartsHidden] = updated.chartsHidden.joinToString(",")
            prefs[Keys.skipReset] = updated.skipStatsResetAt
            val active = updated.activeAccountId
            if (active == null) prefs.remove(Keys.activeAccount) else prefs[Keys.activeAccount] = active
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        themeMode = enumOr(this[Keys.theme], ThemeMode.System),
        coverColors = this[Keys.coverColors] ?: true,
        dynamicColor = this[Keys.dynamicColor] ?: false,
        quality = enumOr(this[Keys.quality], StreamQuality.Original).let { if (it.isOriginal) it else StreamQuality.Kbps128 },
        transcodeCodec = enumOr(this[Keys.codec], TranscodeCodec.Mp3),
        cacheSizeMb = this[Keys.cacheSize] ?: AppSettings.DEFAULT_CACHE_MB,
        resumeOnStart = this[Keys.resume] ?: true,
        downloadLiked = this[Keys.downloadLiked] ?: false,
        logLevel = enumOr(this[Keys.logLevel], NetworkLogLevel.Normal),
        waveDiscoveryShare = this[Keys.discovery] ?: AppSettings.DEFAULT_DISCOVERY,
        waveCharacter = enumOr(this[Keys.character], WaveCharacter.Mix),
        waveMood = enumOr(this[Keys.mood], WaveMood.Any),
        chartsOrder = this[Keys.chartsOrder]?.split(",")?.filter { it.isNotBlank() }.orEmpty(),
        chartsHidden = this[Keys.chartsHidden]?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty(),
        skipStatsResetAt = this[Keys.skipReset] ?: 0,
        activeAccountId = this[Keys.activeAccount],
    )

    private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
        value?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val coverColors = booleanPreferencesKey("cover_colors")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val quality = stringPreferencesKey("quality")
        val codec = stringPreferencesKey("transcode_codec")
        val cacheSize = intPreferencesKey("cache_size_mb")
        val resume = booleanPreferencesKey("resume_on_start")
        val downloadLiked = booleanPreferencesKey("download_liked")
        val logLevel = stringPreferencesKey("log_level")
        val discovery = floatPreferencesKey("wave_discovery")
        val character = stringPreferencesKey("wave_character")
        val mood = stringPreferencesKey("wave_mood")
        val chartsOrder = stringPreferencesKey("charts_order")
        val chartsHidden = stringPreferencesKey("charts_hidden")
        val skipReset = longPreferencesKey("skip_stats_reset_at")
        val activeAccount = longPreferencesKey("active_account")
    }
}
