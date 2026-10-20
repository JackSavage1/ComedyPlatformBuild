package com.comedyplatform.mictracker.data

/**
 * The one error type the data layer throws. [message] is always safe to show
 * to the user — for server errors it's the server's own `message` (spec 3.1).
 *
 * @param statusCode the HTTP status, or null for network/parsing problems
 * @param code PostgREST / GoTrue error code when the server sent one (e.g. "42501")
 */
class ApiException(
    message: String,
    val statusCode: Int? = null,
    val code: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The non-null message (Exception.message is nullable in Kotlin). */
    val userMessage: String get() = message ?: "Something went wrong."

    val isUnauthorized: Boolean get() = statusCode == 401

    companion object {
        const val NETWORK_MESSAGE = "Couldn't reach the server. Check your internet connection and try again."
        const val SESSION_EXPIRED_MESSAGE = "Your session expired. Please sign in again."
        const val NOT_CONFIGURED_MESSAGE = "The app isn't configured with a Supabase URL and key."

        fun network(cause: Throwable): ApiException = ApiException(NETWORK_MESSAGE, cause = cause)
    }
}

/** A human-readable message for any throwable (ApiException's own message, else a generic one). */
fun Throwable.toUserMessage(): String = when (this) {
    is ApiException -> userMessage
    else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
}
