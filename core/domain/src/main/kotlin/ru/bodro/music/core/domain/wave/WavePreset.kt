package ru.bodro.music.core.domain.wave

import ru.bodro.music.core.model.WaveMode
import ru.bodro.music.core.model.WaveSettings

/**
 * Режимы «Моей волны» на главной (карточки под плеером). Режим задаёт настроение и характер волны поверх
 * настроек пользователя; пустой режим — обычная «Моя волна» по настройкам.
 */
enum class WavePreset(
    val id: String,
    val mode: WaveMode = WaveMode.Default,
    val mood: WaveMood? = null,
    val character: WaveCharacter? = null,
) {
    Energetic("energetic", mood = WaveMood.Energetic),
    Calm("calm", mood = WaveMood.Calm),
    Happy("happy", mood = WaveMood.Happy),
    Sad("sad", mood = WaveMood.Sad),
    Discover("discover", mode = WaveMode.Discover, character = WaveCharacter.Discover),
    Favorites("favorites", mode = WaveMode.Favorites, character = WaveCharacter.Favorites),
    Popular("popular", mode = WaveMode.Popular),
    TrackMix("track_mix", mode = WaveMode.TrackMix),
    AlbumMix("album_mix", mode = WaveMode.AlbumMix),
    DecadeMix("decade_mix", mode = WaveMode.DecadeMix),
    ;

    fun settings(): WaveSettings = WaveSettings(mode = mode, preset = id)

    companion object {
        fun of(id: String?): WavePreset? = id?.let { value -> entries.firstOrNull { it.id == value } }
    }
}
