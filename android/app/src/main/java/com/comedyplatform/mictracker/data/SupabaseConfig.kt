package com.comedyplatform.mictracker.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The two values that connect the app to your Supabase project (spec section 2).
 *
 * They come from android/secrets.properties via BuildConfig. When either is
 * missing the app shows the "Not configured" screen instead of crashing.
 */
data class SupabaseConfig(val url: String, val anonKey: String) {

    /** The project URL without a trailing slash, parsed; null when blank/invalid. */
    val baseUrl: HttpUrl? = url.trim().trimEnd('/').toHttpUrlOrNull()

    val trimmedAnonKey: String = anonKey.trim()

    val isConfigured: Boolean get() = baseUrl != null && trimmedAnonKey.isNotEmpty()
}
