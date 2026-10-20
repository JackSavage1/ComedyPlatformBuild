package com.comedyplatform.mictracker.core.json

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * The one shared [Json] configuration, plus "decode a list but skip bad rows".
 */
object CoreJson {

    /**
     * - ignoreUnknownKeys: new columns (created_at, payload, ...) never break decoding.
     * - isLenient: tolerate slightly malformed input instead of failing.
     * - explicitNulls = false: a missing key and an explicit null mean the same thing.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * Decodes every element of a JSON array with [serializer]. A row that can't
     * be decoded (e.g. it has no id) is skipped instead of failing the whole
     * list — one bad scraper row must not blank the app.
     */
    fun <T> decodeList(element: JsonElement?, serializer: KSerializer<T>, json: Json = this.json): List<T> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            try {
                json.decodeFromJsonElement(serializer, item)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Decodes a single object, or null if it can't be decoded. */
    fun <T> decodeOrNull(element: JsonElement?, serializer: KSerializer<T>, json: Json = this.json): T? {
        if (element == null) return null
        return try {
            json.decodeFromJsonElement(serializer, element)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * PostgREST answers inserts/updates with `return=representation` as an
     * array of rows. Returns the first row decoded, or null.
     */
    fun <T> decodeFirst(element: JsonElement?, serializer: KSerializer<T>, json: Json = this.json): T? =
        decodeList(element, serializer, json).firstOrNull()
}
