package ru.bodro.music.data.repository

import kotlinx.serialization.json.Json
import ru.bodro.music.core.model.QueueSnapshot
import ru.bodro.music.core.model.Song

/** JSON для хранения моделей в Room. */
object AppJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encodeSong(song: Song): String = json.encodeToString(Song.serializer(), song)

    fun decodeSong(text: String): Song? = runCatching { json.decodeFromString(Song.serializer(), text) }.getOrNull()

    fun encodeSnapshot(snapshot: QueueSnapshot): String = json.encodeToString(QueueSnapshot.serializer(), snapshot)

    fun decodeSnapshot(text: String): QueueSnapshot? = runCatching { json.decodeFromString(QueueSnapshot.serializer(), text) }.getOrNull()
}
