package com.comedyplatform.mictracker.core.rules

// =============================================================================
// Change password (spec section 9) — the copy and the checks that run BEFORE
// any network call. Kept here (pure Kotlin) so they're unit-tested and the
// screen and the data layer use the exact same messages.
// =============================================================================

object PasswordRules {

    /** Supabase's default minimum, and what the footnote promises. */
    const val MIN_LENGTH = 8

    // ---- Copy (spec 9, word for word) -------------------------------------

    const val TITLE = "Change password"
    const val FOOTNOTE =
        "At least 8 characters. This only changes your Mic Tracker password, not your Supabase account."

    const val TOO_SHORT_MESSAGE = "Use at least 8 characters."
    const val MISMATCH_MESSAGE = "The new passwords don't match."
    const val SAME_AS_CURRENT_MESSAGE = "Pick a password different from your current one."

    /** Re-checking the current password failed with HTTP 400. */
    const val INCORRECT_CURRENT_MESSAGE = "Your current password is incorrect."

    /** Any other failure when the server didn't send a message of its own (e.g. offline). */
    const val GENERIC_FAILURE_MESSAGE = "Couldn't change your password. Check your connection and try again."

    const val SUCCESS_MESSAGE = "Password changed."

    // ---- Rules -------------------------------------------------------------

    /** Save is enabled only when every field is filled in (spaces count — they're valid in passwords). */
    fun canSave(current: String, new: String, confirm: String): Boolean =
        current.isNotEmpty() && new.isNotEmpty() && confirm.isNotEmpty()

    /**
     * Spec 9 validation, checked in this order:
     *   1. new password shorter than [MIN_LENGTH] characters
     *   2. new and confirm differ
     *   3. new equals current
     *
     * Returns the message to show, or null when the passwords may be sent.
     */
    fun validate(current: String, new: String, confirm: String): String? = when {
        characterCount(new) < MIN_LENGTH -> TOO_SHORT_MESSAGE
        new != confirm -> MISMATCH_MESSAGE
        new == current -> SAME_AS_CURRENT_MESSAGE
        else -> null
    }

    /**
     * Characters as a person counts them. `String.length` counts UTF-16 units,
     * so an emoji like 🎤 would count as 2; code points count it as 1.
     */
    private fun characterCount(text: String): Int = text.codePointCount(0, text.length)
}
