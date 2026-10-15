package ru.bodro.music.core.subsonic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

internal val subsonicJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
}

private val prettyJson = Json { prettyPrint = true }

/** Достаёт `subsonic-response` и проверяет `status`. Бросает [SubsonicException]. */
internal fun parseEnvelope(body: String): JsonObject {
    val root = try {
        subsonicJson.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw SubsonicException(SubsonicError.InvalidResponse(e.message), e)
    }
    val response = root["subsonic-response"] as? JsonObject
        ?: throw SubsonicException(SubsonicError.InvalidResponse("нет subsonic-response"))
    val status = response["status"]?.jsonPrimitive?.contentOrNull
    if (status != "ok") {
        val error = response["error"] as? JsonObject
        val code = error?.get("code")?.jsonPrimitive?.intOrNull ?: 0
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull
        throw SubsonicException(SubsonicError.Api(code, message))
    }
    return response
}

internal inline fun <reified T> JsonObject.decodeChild(key: String, default: () -> T): T {
    val element: JsonElement = this[key] ?: return default()
    return subsonicJson.decodeFromJsonElement(serializer<T>(), element)
}

/** Форматирует JSON для экрана деталей лога; если текст обрезан или не JSON — возвращает как есть. */
fun prettyJsonOrRaw(text: String): String = try {
    prettyJson.encodeToString(JsonElement.serializer(), subsonicJson.parseToJsonElement(text))
} catch (_: Exception) {
    text
}
