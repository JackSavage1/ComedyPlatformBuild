package com.comedyplatform.mictracker.core

import com.comedyplatform.mictracker.core.rules.PasswordRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordRulesTest {

    @Test
    fun saveNeedsEveryFieldFilledIn() {
        assertTrue(PasswordRules.canSave("old-pass", "new-pass", "new-pass"))
        assertFalse(PasswordRules.canSave("", "new-pass", "new-pass"))
        assertFalse(PasswordRules.canSave("old-pass", "", "new-pass"))
        assertFalse(PasswordRules.canSave("old-pass", "new-pass", ""))
        // A space is a real character in a password, so it counts as filled in.
        assertTrue(PasswordRules.canSave(" ", " ", " "))
    }

    @Test
    fun validPasswordsPass() {
        assertNull(PasswordRules.validate("old-password", "new-password", "new-password"))
        // Exactly 8 characters is enough.
        assertNull(PasswordRules.validate("old-password", "12345678", "12345678"))
    }

    @Test
    fun rule1TooShort() {
        assertEquals(
            "Use at least 8 characters.",
            PasswordRules.validate("old-password", "1234567", "1234567"),
        )
        // Characters, not UTF-16 units: 7 emoji are still too short.
        val sevenEmoji = "🎤".repeat(7)
        assertEquals(PasswordRules.TOO_SHORT_MESSAGE, PasswordRules.validate("old-password", sevenEmoji, sevenEmoji))
        val eightEmoji = "🎤".repeat(8)
        assertNull(PasswordRules.validate("old-password", eightEmoji, eightEmoji))
    }

    @Test
    fun rule2Mismatch() {
        assertEquals(
            "The new passwords don't match.",
            PasswordRules.validate("old-password", "new-password", "new-passw0rd"),
        )
    }

    @Test
    fun rule3SameAsCurrent() {
        assertEquals(
            "Pick a password different from your current one.",
            PasswordRules.validate("same-password", "same-password", "same-password"),
        )
    }

    @Test
    fun rulesAreCheckedInSpecOrder() {
        // Too short AND mismatched AND equal to current → rule 1 wins.
        assertEquals(PasswordRules.TOO_SHORT_MESSAGE, PasswordRules.validate("short", "short", "shorter"))
        assertEquals(PasswordRules.TOO_SHORT_MESSAGE, PasswordRules.validate("short", "short", "short"))
        // Long enough, mismatched AND new equals current → rule 2 wins.
        assertEquals(
            PasswordRules.MISMATCH_MESSAGE,
            PasswordRules.validate("same-password", "same-password", "other-password"),
        )
    }

    @Test
    fun copyMatchesTheSpec() {
        assertEquals("Change password", PasswordRules.TITLE)
        assertEquals(
            "At least 8 characters. This only changes your Mic Tracker password, not your Supabase account.",
            PasswordRules.FOOTNOTE,
        )
        assertEquals("Your current password is incorrect.", PasswordRules.INCORRECT_CURRENT_MESSAGE)
        assertEquals(
            "Couldn't change your password. Check your connection and try again.",
            PasswordRules.GENERIC_FAILURE_MESSAGE,
        )
        assertEquals("Password changed.", PasswordRules.SUCCESS_MESSAGE)
    }
}
