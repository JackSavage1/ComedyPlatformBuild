package com.comedyplatform.mictracker.data

import com.comedyplatform.mictracker.core.json.JsonValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** JSON media type for request bodies. */
internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** Turns a JSON element into a request body. */
internal fun JsonElement.toRequestBody(json: Json): RequestBody =
    json.encodeToString(JsonElement.serializer(), this).toRequestBody(JSON_MEDIA_TYPE)

/**
 * The parts of an HTTP response we care about.
 *
 * @param body the parsed JSON body, or [JsonNull] when the body is empty
 *   (e.g. 204 No Content) or isn't JSON
 * @param rawBody the body as text, for error messages when it isn't JSON
 */
internal data class HttpResult(val code: Int, val body: JsonElement, val rawBody: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Runs [request] on the IO dispatcher and parses the body as JSON.
 * Network failures become an [ApiException] with a friendly message.
 */
internal suspend fun OkHttpClient.executeForJson(request: Request, json: Json): HttpResult =
    withContext(Dispatchers.IO) {
        try {
            newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val parsed: JsonElement = if (text.isBlank()) {
                    JsonNull
                } else {
                    try {
                        json.parseToJsonElement(text)
                    } catch (e: Exception) {
                        JsonNull
                    }
                }
                HttpResult(code = response.code, body = parsed, rawBody = text)
            }
        } catch (e: IOException) {
            throw ApiException.network(e)
        }
    }

/**
 * Builds the [ApiException] for a failed response. Uses the server's own
 * `message` (PostgREST) / `msg` / `error_description` (GoTrue) when present.
 */
internal fun HttpResult.toApiException(): ApiException {
    val serverMessage = JsonValues.errorMessage(body)
    val code = ((body as? JsonObject)?.get("code") as? JsonPrimitive)?.let { JsonValues.text(it) }
    val fallback = when (this.code) {
        401 -> "Not signed in (HTTP 401)."
        403 -> "This account isn't allowed to do that (HTTP 403)."
        404 -> "Not found (HTTP 404). Has the database migration been applied?"
        in 500..599 -> "The server had a problem (HTTP ${this.code}). Try again in a moment."
        else -> "Request failed (HTTP ${this.code})."
    }
    return ApiException(message = serverMessage ?: fallback, statusCode = this.code, code = code)
}
