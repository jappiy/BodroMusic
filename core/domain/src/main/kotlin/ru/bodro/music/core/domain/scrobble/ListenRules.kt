package ru.bodro.music.core.domain.scrobble

/**
 * Итог проигрывания трека для рекомендаций (не путать со скробблом — он по [ScrobblePolicy] и уходит наружу).
 * Правило целиком — `docs/reco/listening-rules.md`.
 */
enum class PlayOutcome {
    /** Пользователь переключил в первые 30 с или до 20 % трека — слабый негативный сигнал. */
    EarlySkip,

    /** Переключил между ~20 % и 50 % (или просто остановил раньше) — нейтрально, не штрафуется. */
    Partial,

    /** Прослушано ≥ 50 % или ≥ 4 мин — позитивный сигнал. */
    Full,
}

/** Откуда играл трек: пропуск в волне весит полностью, в своём плейлисте или альбоме — почти не штрафуется. */
enum class PlayContext(val skipWeight: Double) {
    /** «Моя волна», «Волна по треку», режимы главной, «Похожие по звучанию». */
    Wave(1.0),

    /** Свой плейлист, альбом, «Мне нравится», исполнитель — пользователь выбрал сам. */
    Own(0.1),

    /** Поиск, чарты, прочее. */
    Other(0.5),
}

object ListenRules {
    const val EARLY_SKIP_MS = 30_000L
    const val EARLY_SKIP_RATIO = 0.2
    const val FULL_RATIO = 0.5
    const val FULL_MS = 240_000L

    fun outcome(listenedMs: Long, durationMs: Long, userSkipped: Boolean): PlayOutcome {
        val full = listenedMs >= FULL_MS || (durationMs > 0 && listenedMs >= durationMs * FULL_RATIO)
        if (full) return PlayOutcome.Full
        val early = listenedMs < EARLY_SKIP_MS || (durationMs > 0 && listenedMs < durationMs * EARLY_SKIP_RATIO)
        return if (userSkipped && early) PlayOutcome.EarlySkip else PlayOutcome.Partial
    }
}
