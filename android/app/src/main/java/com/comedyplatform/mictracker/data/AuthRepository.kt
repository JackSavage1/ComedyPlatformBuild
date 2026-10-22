package com.comedyplatform.mictracker.data

import com.comedyplatform.mictracker.core.json.CoreJson
import com.comedyplatform.mictracker.core.json.JsonValues
import com.comedyplatform.mictracker.core.rules.PasswordRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Sign in / refresh / sign out against Supabase Auth (GoTrue) over plain REST
 * (spec 3.2). Also hands access tokens to [ApiClient] via [AccessTokenProvider].
 *
 *   sign in   POST /auth/v1/token?grant_type=password       {"email","password"}
 *   refresh   POST /auth/v1/token?grant_type=refresh_token  {"refresh_token"}
 *   sign out  POST /auth/v1/logout (best effort), then forget the tokens
 *   change    sign in again with the current password, then
 *   password  PUT /auth/v1/user {"password"} (spec section 9)
 *
 * The token is refreshed proactively when it expires in < 60 s. A refresh the
 * server rejects (e.g. revoked refresh token) signs the user out; a refresh
 * that fails only because the phone is offline keeps the session.
 */
class AuthRepository(
    private val config: SupabaseConfig,
    private val http: OkHttpClient,
    private val store: SessionStore,
    private val json: Json = CoreJson.json,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) : AccessTokenProvider {

    private val _session = MutableStateFlow(store.load())

    /** The current session, or null when signed out. The app's gate observes this. */
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    /** Only one refresh at a time; others wait and reuse its result. */
    private val refreshMutex = Mutex()

    val isSignedIn: Boolean get() = _session.value != null

    // ------------------------------------------------------------------
    // Sign in / out
    // ------------------------------------------------------------------

    /** Signs in with email + password. Throws [ApiException] with GoTrue's message on failure. */
    suspend fun signIn(email: String, password: String): AuthSession {
        val body = buildJsonObject {
            put("email", JsonPrimitive(email.trim()))
            put("password", JsonPrimitive(password))
        }
        val result = authRequest("POST", "token", grantType = "password", body = body, bearer = null)
        if (!result.isSuccessful) throw result.toApiException()
        val session = parseSession(result.body, fallbackEmail = email.trim())
            ?: throw ApiException("Unexpected sign-in response from the server.")
        persist(session)
        return session
    }

    /** Best-effort server logout, then forget the tokens locally (always succeeds). */
    suspend fun signOut() {
        val current = _session.value
        if (current != null && config.isConfigured) {
            try {
                authRequest("POST", "logout", grantType = null, body = JsonObject(emptyMap()), bearer = current.accessToken)
            } catch (e: ApiException) {
                // Offline or already expired — signing out locally is enough.
            }
        }
        clearSession()
    }

    /** Forgets the session locally (no network). */
    fun clearSession() {
        store.clear()
        _session.value = null
    }

    // ------------------------------------------------------------------
    // Change password (spec section 9)
    // ------------------------------------------------------------------

    /**
     * Changes the signed-in user's password in two steps:
     *
     *  1. Sign in again with the signed-in email and [current]
     *     (POST /auth/v1/token?grant_type=password). HTTP 400 means the current
     *     password is wrong: we say so and leave the session alone (the user
     *     stays signed in). On success the fresh session is stored exactly like
     *     after a normal sign-in — which also satisfies Supabase's optional
     *     "recent login required" rule for password changes.
     *  2. PUT /auth/v1/user `{"password": new}` with the fresh access token.
     *
     * Call [PasswordRules.validate] first; this function doesn't repeat those
     * checks. Throws [ApiException] whose message is safe to show:
     * "Your current password is incorrect.", the server's own message (e.g. a
     * 422 `weak_password` / `same_password`), or the spec's generic message.
     *
     * The passwords only ever go into request bodies: never logged, never stored.
     */
    suspend fun changePassword(current: String, new: String) {
        val email = _session.value?.email
            ?: throw ApiException(ApiException.SESSION_EXPIRED_MESSAGE)

        // Step 1: prove the current password by signing in with it.
        val reauthBody = buildJsonObject {
            put("email", JsonPrimitive(email))
            put("password", JsonPrimitive(current))
        }
        val reauth = passwordChangeRequest("POST", "token", grantType = "password", body = reauthBody, bearer = null)
        if (reauth.code == 400) {
            throw ApiException(PasswordRules.INCORRECT_CURRENT_MESSAGE, statusCode = 400, code = reauth.goTrueErrorCode())
        }
        if (!reauth.isSuccessful) throw reauth.toPasswordChangeException()
        val fresh = parseSession(reauth.body, fallbackEmail = email)
            ?: throw ApiException(PasswordRules.GENERIC_FAILURE_MESSAGE)
        persist(fresh)

        // Step 2: set the new password, authorized by the fresh session.
        val updateBody = buildJsonObject { put("password", JsonPrimitive(new)) }
        val update = passwordChangeRequest("PUT", "user", grantType = null, body = updateBody, bearer = fresh.accessToken)
        if (!update.isSuccessful) throw update.toPasswordChangeException()
    }

    /** [authRequest], but a network failure gets the change-password screen's own wording. */
    private suspend fun passwordChangeRequest(
        method: String,
        endpoint: String,
        grantType: String?,
        body: JsonElement,
        bearer: String?,
    ): HttpResult = try {
        authRequest(method, endpoint, grantType, body, bearer)
    } catch (e: ApiException) {
        throw ApiException(PasswordRules.GENERIC_FAILURE_MESSAGE, cause = e)
    }

    /**
     * Spec 9: show the server's message (GoTrue's `msg`, e.g. for a 422
     * `weak_password` or `same_password`), or the generic message when the
     * server didn't send one.
     */
    private fun HttpResult.toPasswordChangeException(): ApiException = ApiException(
        message = JsonValues.errorMessage(body) ?: PasswordRules.GENERIC_FAILURE_MESSAGE,
        statusCode = code,
        code = goTrueErrorCode(),
    )

    /** GoTrue's string error code (`"weak_password"`); older servers only send the numeric `code`. */
    private fun HttpResult.goTrueErrorCode(): String? {
        val obj = body as? JsonObject ?: return null
        return JsonValues.text(obj["error_code"]) ?: JsonValues.text(obj["code"])
    }

    // ------------------------------------------------------------------
    // AccessTokenProvider
    // ------------------------------------------------------------------

    override suspend fun accessToken(): String? {
        val current = _session.value ?: return null
        if (!isExpiringSoon(current)) return current.accessToken
        return refreshMutex.withLock {
            val latest = _session.value ?: return@withLock null
            if (!isExpiringSoon(latest)) {
                latest.accessToken // another caller refreshed while we waited
            } else {
                when (val outcome = refresh(latest)) {
                    is RefreshOutcome.Success -> outcome.session.accessToken
                    RefreshOutcome.Rejected -> null
                    // Offline: keep using the old token; the request itself
                    // will report the network problem.
                    is RefreshOutcome.NetworkError -> latest.accessToken
                }
            }
        }
    }

    override suspend fun refreshAfterUnauthorized(rejectedToken: String): String? =
        refreshMutex.withLock {
            val latest = _session.value ?: return@withLock null
            if (latest.accessToken != rejectedToken) {
                latest.accessToken // already refreshed by someone else
            } else {
                when (val outcome = refresh(latest)) {
                    is RefreshOutcome.Success -> outcome.session.accessToken
                    RefreshOutcome.Rejected -> null
                    is RefreshOutcome.NetworkError -> throw outcome.error
                }
            }
        }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private sealed class RefreshOutcome {
        data class Success(val session: AuthSession) : RefreshOutcome()
        data object Rejected : RefreshOutcome()
        data class NetworkError(val error: ApiException) : RefreshOutcome()
    }

    private fun isExpiringSoon(session: AuthSession): Boolean =
        session.expiresAt - nowEpochSeconds() < REFRESH_MARGIN_SECONDS

    /** Must be called with [refreshMutex] held. */
    private suspend fun refresh(current: AuthSession): RefreshOutcome {
        val body = buildJsonObject { put("refresh_token", JsonPrimitive(current.refreshToken)) }
        val result = try {
            authRequest("POST", "token", grantType = "refresh_token", body = body, bearer = null)
        } catch (e: ApiException) {
            return RefreshOutcome.NetworkError(e)
        }
        if (result.code in 500..599 || result.code == 429) {
            // Server hiccup or rate limit — don't throw the user out for it
            // (same as iOS: only a rejected refresh token signs you out).
            return RefreshOutcome.NetworkError(result.toApiException())
        }
        val session = if (result.isSuccessful) parseSession(result.body, fallbackEmail = current.email) else null
        if (session == null) {
            clearSession()
            return RefreshOutcome.Rejected
        }
        persist(session)
        return RefreshOutcome.Success(session)
    }

    private fun persist(session: AuthSession) {
        store.save(session)
        _session.value = session
    }

    /** One GoTrue request: [method] (POST or PUT) to /auth/v1/[endpoint]. */
    private suspend fun authRequest(
        method: String,
        endpoint: String,
        grantType: String?,
        body: JsonElement,
        bearer: String?,
    ): HttpResult {
        val baseUrl = config.baseUrl ?: throw ApiException(ApiException.NOT_CONFIGURED_MESSAGE)
        val urlBuilder = baseUrl.newBuilder().addPathSegments("auth/v1/$endpoint")
        if (grantType != null) urlBuilder.addQueryParameter("grant_type", grantType)
        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .header("apikey", config.trimmedAnonKey)
            .header("Accept", "application/json")
            .method(method, body.toRequestBody(json))
        if (bearer != null) requestBuilder.header("Authorization", "Bearer $bearer")
        return http.executeForJson(requestBuilder.build(), json)
    }

    /**
     * Reads `{access_token, refresh_token, expires_in, expires_at, user:{id,email}}`.
     * Returns null when the tokens are missing.
     */
    private fun parseSession(body: JsonElement, fallbackEmail: String?): AuthSession? {
        val obj = body as? JsonObject ?: return null
        val access = JsonValues.text(obj["access_token"]) ?: return null
        val refresh = JsonValues.text(obj["refresh_token"]) ?: return null
        // Prefer "now + expires_in" measured on THIS device's clock, so a phone
        // whose clock is off doesn't think every token is about to expire.
        // Fall back to the server's absolute expires_at.
        val now = nowEpochSeconds()
        val expiresAt = JsonValues.long(obj["expires_in"])?.let { now + it }
            ?: JsonValues.long(obj["expires_at"])
            ?: (now + DEFAULT_LIFETIME_SECONDS)
        val user = obj["user"] as? JsonObject
        return AuthSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = expiresAt,
            userId = JsonValues.text(user?.get("id")),
            email = JsonValues.text(user?.get("email")) ?: fallbackEmail,
        )
    }

    private companion object {
        /** Refresh when the token expires within this many seconds (spec 3.2). */
        const val REFRESH_MARGIN_SECONDS = 60L

        /** Supabase's default JWT lifetime, used if the server omits both expiry fields. */
        const val DEFAULT_LIFETIME_SECONDS = 3600L
    }
}
