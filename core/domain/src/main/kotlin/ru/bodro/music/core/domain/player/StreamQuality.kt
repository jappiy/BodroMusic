package ru.bodro.music.core.domain.player

/** Качество потока: `maxBitRate` и формат транскодирования для `stream`. */
enum class StreamQuality(val maxBitRate: Int?) {
    Original(null),
    Kbps320(320),
    Kbps192(192),
    Kbps128(128),
    ;

    val isOriginal: Boolean get() = this == Original
}

enum class TranscodeCodec(val format: String) { Mp3("mp3"), Opus("opus") }

data class StreamParams(val maxBitRate: Int?, val format: String?)

object StreamParamsResolver {
    fun resolve(quality: StreamQuality, codec: TranscodeCodec): StreamParams =
        if (quality.isOriginal) StreamParams(maxBitRate = 0, format = "raw") else StreamParams(quality.maxBitRate, codec.format)
}
