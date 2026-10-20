package com.comedyplatform.mictracker.data

/**
 * A signed-in Supabase session.
 *
 * @param expiresAt when [accessToken] expires, in seconds since 1970 (UTC)
 */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String?,
    val email: String?,
)

/**
 * Where the session is persisted between app launches. The real one is
 * [SharedPreferencesSessionStore] (app-private storage, spec section 1);
 * tests use an in-memory store.
 */
interface SessionStore {
    fun load(): AuthSession?
    fun save(session: AuthSession)
    fun clear()
}

/** Keeps the session in memory only (tests, previews). */
class InMemorySessionStore(private var session: AuthSession? = null) : SessionStore {
    override fun load(): AuthSession? = session

    override fun save(session: AuthSession) {
        this.session = session
    }

    override fun clear() {
        session = null
    }
}
