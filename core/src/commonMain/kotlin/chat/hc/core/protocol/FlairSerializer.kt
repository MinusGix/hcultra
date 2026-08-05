package chat.hc.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * `flair` is `String | false` on the wire.
 *
 * `getAppearance()` in `_UAC.js` returns `flair: false` for anyone without one,
 * and only a decorated level gets a string. Our reader is lenient — it has to
 * be, since the server also sends `channel: false` — so that boolean decodes as
 * the *string* `"false"` and renders as a two-character badge reading "false"
 * next to every ordinary user's nick.
 *
 * Normalised here, at the boundary, rather than by every consumer: the roster
 * and the message renderer both read it, and a third caller would forget.
 */
internal object FlairSerializer : KSerializer<String?> {

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Flair", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): String? {
        val json = decoder as? JsonDecoder
            ?: return decoder.decodeString().normalizeFlair()
        val element = json.decodeJsonElement()
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        // Anything that is not a JSON string — `false`, a number — means "none".
        if (!primitive.isString) return null
        return primitive.content.normalizeFlair()
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }

    /**
     * Leniency can still hand us a bare `false` as an unquoted string. A real
     * flair is capped at two characters by `forceflair`, so the literal can
     * never collide with one.
     */
    private fun String.normalizeFlair(): String? =
        takeIf { it.isNotBlank() && it != "false" }
}
