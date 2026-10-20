package com.comedyplatform.mictracker.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the session in app-private SharedPreferences (MODE_PRIVATE: only
 * this app can read the file). The manifest sets allowBackup="false" so the
 * tokens are never copied into device backups.
 */
class SharedPreferencesSessionStore(context: Context) : SessionStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): AuthSession? {
        val access = prefs.getString(KEY_ACCESS, null)
        val refresh = prefs.getString(KEY_REFRESH, null)
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) return null
        return AuthSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L),
            userId = prefs.getString(KEY_USER_ID, null),
            email = prefs.getString(KEY_EMAIL, null),
        )
    }

    override fun save(session: AuthSession) {
        prefs.edit()
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAt)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_EMAIL, session.email)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "supabase_session"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_USER_ID = "user_id"
        const val KEY_EMAIL = "email"
    }
}
