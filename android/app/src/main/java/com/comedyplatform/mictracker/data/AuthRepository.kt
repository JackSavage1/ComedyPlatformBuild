package com.comedyplatform.mictracker.data

import com.comedyplatform.mictracker.core.json.CoreJson
import com.comedyplatform.mictracker.core.json.JsonValues
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
        val result = postAuth("token", grantType = "password", body = body, bearer = null)
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
                postAuth("logout", grantType = null, body = JsonObject(emptyMap()), bearer = current.accessToken)
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
            postAuth("token", grantType = "refresh_token", body = body, bearer = null)
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

    private suspend fun postAuth(endpoint: String, grantType: String?, body: JsonElement, bearer: String?): HttpResult {
        val baseUrl = config.baseUrl ?: throw ApiException(ApiException.NOT_CONFIGURED_MESSAGE)
        val urlBuilder = baseUrl.newBuilder().addPathSegments("auth/v1/$endpoint")
        if (grantType != null) urlBuilder.addQueryParameter("grant_type", grantType)
        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .header("apikey", config.trimmedAnonKey)
            .header("Accept", "application/json")
            .post(body.toRequestBody(json))
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
