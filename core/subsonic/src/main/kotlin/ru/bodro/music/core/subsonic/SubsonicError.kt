package ru.bodro.music.core.subsonic

/** Ошибки обращения к Navidrome. Текст для пользователя подбирается в UI по типу ошибки. */
sealed interface SubsonicError {
    /** `status=failed` в ответе Subsonic. */
    data class Api(val code: Int, val serverMessage: String?) : SubsonicError {
        val kind: ApiErrorKind get() = ApiErrorKind.fromCode(code)
    }

    /** HTTP-ответ не 2xx. */
    data class Http(val status: Int) : SubsonicError

    data class Network(val kind: NetworkErrorKind, val details: String?) : SubsonicError

    /** Ответ не удалось разобрать. */
    data class InvalidResponse(val details: String?) : SubsonicError

    /** Нет активной учётной записи / не задан адрес. */
    data object NotConfigured : SubsonicError
}

enum class NetworkErrorKind { Timeout, Unreachable, Ssl, Other }

/** Коды ошибок Subsonic API. */
enum class ApiErrorKind(val code: Int) {
    Generic(0),
    MissingParameter(10),
    ClientTooOld(20),
    ServerTooOld(30),
    WrongCredentials(40),
    TokenAuthNotSupported(41),
    AuthMechanismNotSupported(42),
    ConflictingAuth(43),
    InvalidApiKey(44),
    NotAuthorized(50),
    TrialExpired(60),
    NotFound(70),
    ;

    companion object {
        fun fromCode(code: Int): ApiErrorKind = entries.firstOrNull { it.code == code } ?: Generic
    }
}

class SubsonicException(val error: SubsonicError, cause: Throwable? = null) :
    Exception(error.toString(), cause)
