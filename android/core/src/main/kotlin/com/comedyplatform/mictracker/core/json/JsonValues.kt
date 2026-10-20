package com.comedyplatform.mictracker.core.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * Forgiving conversions from a raw [JsonElement] to Kotlin values.
 *
 * The database was filled by a web app and several scrapers, so a value can
 * arrive in an unexpected shape: `30.0` instead of `30`, `"1"` instead of
 * `true`, `"  "` instead of null. Every function here returns `null` for
 * anything it can't make sense of — it never throws.
 */
object JsonValues {

    /** Text, or null for JSON null / blank / whitespace-only / non-primitive values. */
    fun text(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        val content = primitive.content
        return if (content.isBlank()) null else content
    }

    /** A number as Double: accepts 7, 7.5, "7.5". Rejects NaN/Infinity and booleans. */
    fun double(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        val content = primitive.content.trim()
        if (!primitive.isString && (content == "true" || content == "false")) return null
        val value = content.toDoubleOrNull() ?: return null
        return if (value.isNaN() || value.isInfinite()) null else value
    }

    /** A whole number: accepts 30, 30.0, "30", "30.0" (fractions are rounded). */
    fun int(element: JsonElement?): Int? {
        val value = double(element) ?: return null
        val rounded = value.roundToLong()
        return if (rounded in Int.MIN_VALUE..Int.MAX_VALUE) rounded.toInt() else null
    }

    /** Like [int] but for bigint ids (scraped_mics.id). */
    fun long(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        // Try an exact parse first so very large ids keep every digit.
        primitive.content.trim().toLongOrNull()?.let { return it }
        return double(element)?.roundToLong()
    }

    /**
     * true/false, 1/0, "true"/"false", "t"/"f", "yes"/"no", "1"/"0", "on"/"off".
     * Anything else → null.
     */
    fun boolean(element: JsonElement?): Boolean? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        val content = primitive.content.trim().lowercase()
        return when (content) {
            "true", "t", "yes", "y", "1", "on" -> true
            "false", "f", "no", "n", "0", "off" -> false
            else -> {
                // Numbers like 1.0 / 0.0 / 2
                val number = content.toDoubleOrNull() ?: return null
                number != 0.0
            }
        }
    }

    /**
     * A calendar date from `"2026-10-20"` (or a timestamp that starts with
     * one, like `"2026-10-20T00:00:00"`). Never shifts through UTC.
     */
    fun date(element: JsonElement?): LocalDate? = parseDate(text(element))

    /** Same as [date] but from a plain string. */
    fun parseDate(raw: String?): LocalDate? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.length < 10) return null
        return try {
            LocalDate.parse(trimmed.substring(0, 10))
        } catch (e: Exception) {
            null
        }
    }

    /** True when [element] is missing or JSON null. */
    fun isNull(element: JsonElement?): Boolean = element == null || element is JsonNull

    /** The first error message from a Supabase/PostgREST/GoTrue error body, if any. */
    fun errorMessage(body: JsonElement?): String? {
        val obj = body as? JsonObject ?: return null
        // PostgREST uses "message"; GoTrue uses "error_description", "msg" or "error".
        for (key in listOf("message", "error_description", "msg", "error")) {
            val value = text(obj[key])
            if (value != null) return value
        }
        return null
    }

    /** Elements of a JSON array, or an empty list for anything else. */
    fun array(element: JsonElement?): List<JsonElement> = (element as? JsonArray) ?: emptyList()
}
