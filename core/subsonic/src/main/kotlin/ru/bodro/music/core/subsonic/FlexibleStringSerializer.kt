package ru.bodro.music.core.subsonic

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Принимает и строку, и число (разные серверы Subsonic отдают id по-разному). */
internal object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            return (element as? JsonPrimitive)?.content ?: element.jsonPrimitive.content
        }
        return decoder.decodeString()
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
