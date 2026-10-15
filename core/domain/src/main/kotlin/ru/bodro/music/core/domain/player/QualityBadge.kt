package ru.bodro.music.core.domain.player

import ru.bodro.music.core.model.Song

/**
 * Бейдж качества на плеере: «FLAC 16/44», «MP3 320». [lossless] → в UI дополнительно «Без потерь».
 */
data class QualityBadge(val label: String, val lossless: Boolean, val transcoded: Boolean = false)

object QualityBadges {
    private val losslessFormats = setOf("flac", "alac", "wav", "aif", "aiff", "ape", "wv", "dsf", "dff", "tta")

    fun isLossless(song: Song): Boolean {
        val suffix = song.suffix?.lowercase()
        if (suffix in losslessFormats) return true
        // m4a может быть и AAC, и ALAC: ALAC выдаёт высокий битрейт.
        return suffix == "m4a" && (song.bitDepth != null || (song.bitRate ?: 0) > 700)
    }

    fun of(song: Song, quality: StreamQuality = StreamQuality.Original, codec: TranscodeCodec = TranscodeCodec.Mp3): QualityBadge? {
        val suffix = song.suffix?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val lossless = isLossless(song)
        val sourceBitrate = song.bitRate
        val maxBitRate = quality.maxBitRate
        val transcoded = maxBitRate != null && (lossless || sourceBitrate == null || sourceBitrate > maxBitRate)
        if (transcoded) return QualityBadge("${codec.format.uppercase()} $maxBitRate", lossless = false, transcoded = true)
        if (lossless) {
            val depth = song.bitDepth
            val rate = song.samplingRate
            val name = if (suffix == "m4a") "ALAC" else suffix.uppercase()
            val details = if (depth != null && rate != null) " $depth/${formatKhz(rate)}" else ""
            return QualityBadge("$name$details", lossless = true)
        }
        val bitrate = sourceBitrate?.let { " $it" }.orEmpty()
        return QualityBadge("${suffix.uppercase()}$bitrate", lossless = false)
    }

    /** 44100 → «44», 48000 → «48», 88200 → «88», 96000 → «96». */
    private fun formatKhz(hz: Int): String = (hz / 1000).toString()
}
