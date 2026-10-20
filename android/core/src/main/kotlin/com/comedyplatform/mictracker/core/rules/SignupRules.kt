package com.comedyplatform.mictracker.core.rules

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// =============================================================================
// Signup rules — deadlines (rule 7), sign-up links (rule 8) and Instagram
// handles (rule 9).
// =============================================================================

/**
 * Rule 7: when you have to sign up for a mic that books `advance_days` ahead.
 */
sealed class SignupDeadline {
    /** The signup date is already behind us. */
    data object Passed : SignupDeadline()

    /** Sign up today. */
    data object Today : SignupDeadline()

    /** Sign up tomorrow. */
    data object Tomorrow : SignupDeadline()

    /** Sign up on [signupBy], which is [daysUntil] (≥ 2) days away. */
    data class Upcoming(val signupBy: LocalDate, val daysUntil: Int) : SignupDeadline()

    /** The exact web copy, e.g. "📅 Sign up by Thu 10/23 (3 days)". */
    val text: String
        get() = when (this) {
            Passed -> "⚠️ Signup deadline passed!"
            Today -> "🔴 Sign up TODAY!"
            Tomorrow -> "🟡 Sign up by TOMORROW!"
            is Upcoming -> "📅 Sign up by ${DateRules.shortWeekday(signupBy)} ${DateRules.monthDay(signupBy)} ($daysUntil days)"
        }

    /** True for "today" and "tomorrow" — worth highlighting. */
    val isUrgent: Boolean
        get() = this == Today || this == Tomorrow

    companion object {
        /**
         * Only mics with `advance_days > 0` have a deadline.
         *
         *     signupBy  = micDate − advanceDays
         *     daysUntil = signupBy − today
         */
        fun compute(advanceDays: Int?, micDate: LocalDate, today: LocalDate): SignupDeadline? {
            if (advanceDays == null || advanceDays <= 0) return null
            val signupBy = micDate.minusDays(advanceDays.toLong())
            val daysUntil = ChronoUnit.DAYS.between(today, signupBy).toInt()
            return when {
                signupBy.isBefore(today) -> Passed
                daysUntil == 0 -> Today
                daysUntil == 1 -> Tomorrow
                else -> Upcoming(signupBy, daysUntil)
            }
        }
    }
}

object SignupRules {

    private val schemeAndAuthority = Regex("^[A-Za-z][A-Za-z0-9+.-]*://([^/?#]*)")

    /**
     * Rule 8: turns the free-text `signup_url` (or `venue_url`) into something
     * safe to open, or null.
     *
     * 1. Trim.
     * 2. Starts with http:// or https:// and has a host → use it.
     * 3. Otherwise, no spaces and contains a "." → prefix "https://".
     * 4. Otherwise → null (show the raw text as a note, no button).
     */
    fun signupUrl(raw: String?): String? {
        val text = TextRules.trimmedOrNull(raw) ?: return null
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return if (hasHost(text)) text else null
        }
        if (text.none { it.isWhitespace() } && text.contains('.')) {
            val candidate = "https://$text"
            return if (hasHost(candidate)) candidate else null
        }
        return null
    }

    /**
     * The raw signup text to show as a note when it isn't a usable link
     * (e.g. "Sign up at the bar"). null when it *is* a link or is blank.
     */
    fun signupNote(raw: String?): String? {
        val text = TextRules.trimmedOrNull(raw) ?: return null
        return if (signupUrl(text) == null) text else null
    }

    private fun hasHost(url: String): Boolean {
        val authority = schemeAndAuthority.find(url)?.groupValues?.get(1) ?: return false
        // Strip "user:pass@" and ":port" to get the bare host.
        val host = authority.substringAfterLast('@').substringBefore(':')
        return host.isNotEmpty() && host.none { it.isWhitespace() }
    }
}

object InstagramRules {

    private val validHandle = Regex("^[A-Za-z0-9._]{1,30}$")

    /**
     * Rule 9: a valid Instagram handle (without "@"), or null.
     *
     * Trim; if it contains "instagram.com/", take the first path segment after
     * it; strip leading "@" and trailing "/"; drop any "?query".
     * Valid = [A-Za-z0-9._]{1,30}.
     */
    fun handle(raw: String?): String? {
        var text = TextRules.trimmedOrNull(raw) ?: return null

        val marker = "instagram.com/"
        val markerIndex = text.lowercase().indexOf(marker)
        if (markerIndex >= 0) {
            val afterMarker = text.substring(markerIndex + marker.length)
            text = afterMarker.split('/', '?', '#').firstOrNull { it.isNotEmpty() } ?: ""
        }

        text = text.substringBefore('?').substringBefore('#')
        text = text.trimStart('@').trimEnd('/')

        return if (validHandle.matches(text)) text else null
    }

    /** "https://instagram.com/{handle}". */
    fun profileUrl(handle: String): String = "https://instagram.com/$handle"
}
