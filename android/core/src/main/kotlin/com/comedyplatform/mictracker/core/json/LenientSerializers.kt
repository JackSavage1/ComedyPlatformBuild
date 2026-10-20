@file:OptIn(ExperimentalSerializationApi::class)

package com.comedyplatform.mictracker.core.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate

// =============================================================================
// Lenient serializers — used on model properties with
//     @Serializable(with = LenientIntSerializer::class) val x: Int? = null
//
// Each one reads the raw JSON value (decodeJsonElement) and converts it with
// JsonValues, so a weird value becomes null instead of crashing the decode of
// the whole list. They only work with kotlinx.serialization's Json format,
// which is the only format this app uses.
// =============================================================================

private fun Decoder.jsonElement(): JsonElement {
    val json = this as? JsonDecoder
        ?: throw SerializationException("Lenient serializers only support JSON")
    return json.decodeJsonElement()
}

/** Text; null / missing / whitespace-only → null (the Streamlit `safe_str` rule). */
object BlankAsNullStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BlankAsNullString", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): String? = JsonValues.text(decoder.jsonElement())

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/** Whole numbers that may arrive as 30, 30.0 or "30". */
object LenientIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT).nullable

    override fun deserialize(decoder: Decoder): Int? = JsonValues.int(decoder.jsonElement())

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

/** A required integer id (still accepts 12.0 / "12"); throws if missing or junk. */
object LenientIdSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientId", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int =
        JsonValues.int(decoder.jsonElement()) ?: throw SerializationException("Row has no usable id")

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

/** A required bigint id (scraped_mics.id). */
object LenientLongIdSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientLongId", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long =
        JsonValues.long(decoder.jsonElement()) ?: throw SerializationException("Row has no usable id")

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

/** Real numbers (ratings, coordinates) that may arrive as numbers or numeric strings. */
object LenientDoubleSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientDouble", PrimitiveKind.DOUBLE).nullable

    override fun deserialize(decoder: Decoder): Double? = JsonValues.double(decoder.jsonElement())

    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}

/** Booleans that may arrive as true/false, 1/0 or "true"/"1"/"yes". */
object LenientBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientBoolean", PrimitiveKind.BOOLEAN).nullable

    override fun deserialize(decoder: Decoder): Boolean? = JsonValues.boolean(decoder.jsonElement())

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) encoder.encodeNull() else encoder.encodeBoolean(value)
    }
}

/** `date` columns ("2026-10-20") as a [LocalDate] — a calendar date, no time zone. */
object LenientLocalDateSerializer : KSerializer<LocalDate?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientLocalDate", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): LocalDate? = JsonValues.date(decoder.jsonElement())

    override fun serialize(encoder: Encoder, value: LocalDate?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.toString())
    }
}
