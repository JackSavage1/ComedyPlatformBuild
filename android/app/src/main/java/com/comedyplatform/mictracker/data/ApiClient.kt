package com.comedyplatform.mictracker.data

import com.comedyplatform.mictracker.core.json.CoreJson
import com.comedyplatform.mictracker.core.model.Rpc
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Gives the API client the signed-in user's access token.
 * Implemented by [AuthRepository]; an interface so the client can be tested
 * without real sign-in.
 */
interface AccessTokenProvider {

    /** A currently valid access token (refreshed first if it's about to expire), or null when signed out. */
    suspend fun accessToken(): String?

    /**
     * Called after the server answered 401 to a request made with
     * [rejectedToken]. Refreshes the session (unless another request already
     * did) and returns the new token — or null when the session is gone, in
     * which case the app returns to the sign-in screen.
     */
    suspend fun refreshAfterUnauthorized(rejectedToken: String): String?
}

/**
 * A thin REST client for Supabase's PostgREST API (spec 3.1) — no Supabase SDK.
 *
 *   select  GET    /rest/v1/{table}?select=*&{filters}
 *   insert  POST   /rest/v1/{table}             Prefer: return=representation
 *   update  PATCH  /rest/v1/{table}?{filters}   Prefer: return=representation
 *   delete  DELETE /rest/v1/{table}?{filters}
 *   rpc     POST   /rest/v1/rpc/{function}      body = named args
 *
 * Filters are PostgREST query pairs such as `"id" to "eq.12"` or
 * `"plan_date" to "gte.2026-10-20"`.
 *
 * Every request carries `apikey` plus `Authorization: Bearer <user JWT>` when
 * signed in. On HTTP 401 the session is refreshed once and the request
 * retried once. Failures throw [ApiException] with the server's message.
 */
class ApiClient(
    private val config: SupabaseConfig,
    private val http: OkHttpClient,
    private val tokens: AccessTokenProvider,
    private val json: Json = CoreJson.json,
) {

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** GET rows. `select=*` is added automatically unless [query] has its own `select`. */
    suspend fun select(table: String, query: List<Pair<String, String>> = emptyList()): JsonElement {
        val withSelect = if (query.any { it.first == "select" }) query else listOf("select" to "*") + query
        return send(method = "GET", path = "rest/v1/$table", query = withSelect)
    }

    /** GET rows and decode them, skipping rows that can't be decoded. */
    suspend fun <T> selectList(
        table: String,
        serializer: KSerializer<T>,
        query: List<Pair<String, String>> = emptyList(),
    ): List<T> = CoreJson.decodeList(select(table, query), serializer, json)

    /** POST a new row; returns the inserted rows (an array). */
    suspend fun insert(table: String, body: JsonElement): JsonElement =
        send(method = "POST", path = "rest/v1/$table", body = body, prefer = "return=representation")

    /** PATCH rows matching [filters]; returns the updated rows (an array, empty if nothing matched). */
    suspend fun update(table: String, filters: List<Pair<String, String>>, body: JsonElement): JsonElement {
        require(filters.isNotEmpty()) { "Refusing to PATCH a whole table" }
        return send(method = "PATCH", path = "rest/v1/$table", query = filters, body = body, prefer = "return=representation")
    }

    /** DELETE rows matching [filters]; returns the deleted rows (an array). */
    suspend fun delete(table: String, filters: List<Pair<String, String>>): JsonElement {
        require(filters.isNotEmpty()) { "Refusing to DELETE a whole table" }
        return send(method = "DELETE", path = "rest/v1/$table", query = filters, prefer = "return=representation")
    }

    /** POST /rest/v1/rpc/{function}. Returns the function's JSON result (JsonNull for void). */
    suspend fun rpc(function: String, args: JsonObject = Rpc.noArgs()): JsonElement =
        send(method = "POST", path = "rest/v1/rpc/$function", body = args)

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun send(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonElement? = null,
        prefer: String? = null,
    ): JsonElement {
        val baseUrl = config.baseUrl
        if (baseUrl == null || config.trimmedAnonKey.isEmpty()) {
            throw ApiException(ApiException.NOT_CONFIGURED_MESSAGE)
        }
        val url = buildUrl(baseUrl, path, query)

        val firstToken = tokens.accessToken()
        val first = http.executeForJson(buildRequest(method, url, body, prefer, firstToken), json)

        if (first.code == 401 && firstToken != null) {
            // Expired / revoked JWT: refresh once, retry once (spec 3.1).
            val newToken = tokens.refreshAfterUnauthorized(firstToken)
                ?: throw ApiException(ApiException.SESSION_EXPIRED_MESSAGE, statusCode = 401)
            val second = http.executeForJson(buildRequest(method, url, body, prefer, newToken), json)
            if (!second.isSuccessful) throw second.toApiException()
            return second.body
        }

        if (!first.isSuccessful) throw first.toApiException()
        return first.body
    }

    private fun buildUrl(baseUrl: HttpUrl, path: String, query: List<Pair<String, String>>): HttpUrl {
        val builder = baseUrl.newBuilder().addPathSegments(path)
        for ((name, value) in query) {
            builder.addQueryParameter(name, value)
        }
        return builder.build()
    }

    private fun buildRequest(
        method: String,
        url: HttpUrl,
        body: JsonElement?,
        prefer: String?,
        accessToken: String?,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("apikey", config.trimmedAnonKey)
            .header("Accept", "application/json")
        // Without a user token we send only the apikey: the new-style
        // "publishable" keys are not JWTs and must not go in Authorization.
        if (accessToken != null) builder.header("Authorization", "Bearer $accessToken")
        if (prefer != null) builder.header("Prefer", prefer)

        val requestBody = body?.toRequestBody(json)
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody ?: JsonObject(emptyMap()).toRequestBody(json))
            "PATCH" -> builder.patch(requestBody ?: JsonObject(emptyMap()).toRequestBody(json))
            "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
            else -> throw IllegalArgumentException("Unsupported method $method")
        }
        return builder.build()
    }
}
