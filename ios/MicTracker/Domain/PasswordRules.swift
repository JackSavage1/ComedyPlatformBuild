import Foundation

// =============================================================================
// PasswordRules — the "Change password" checks and copy (spec 9).
//
// Everything here is pure (no network, no UI) so it can be unit-tested.
// The form runs `validationError` BEFORE any network call; only when it
// returns nil does AuthService talk to Supabase.
//
// Passwords are compared exactly as typed: no trimming, because a space is
// a legal password character.
// =============================================================================
enum PasswordRules {

    /// Spec 9: "At least 8 characters."
    static let minimumLength = 8

    // MARK: - Copy (exact strings from spec 9)

    static let tooShortMessage = "Use at least 8 characters."
    static let mismatchMessage = "The new passwords don't match."
    static let unchangedMessage = "Pick a password different from your current one."
    static let incorrectCurrentMessage = "Your current password is incorrect."
    static let genericFailureMessage = "Couldn't change your password. Check your connection and try again."
    static let successMessage = "Password changed."
    static let footnote = "At least 8 characters. This only changes your Mic Tracker password, not your Supabase account."

    // MARK: - Rules

    /// Save is enabled only when every field is filled in.
    static func canSubmit(current: String, new: String, confirm: String) -> Bool {
        !current.isEmpty && !new.isEmpty && !confirm.isEmpty
    }

    /// Spec 9's validation, checked in this order (the first problem wins):
    ///   1. new password shorter than 8 characters
    ///   2. new and confirm differ
    ///   3. new equals current
    /// Returns the message to show, or nil when it's OK to send.
    ///
    ///     validationError(current: "oldpass1", new: "short", confirm: "other")
    ///       -> "Use at least 8 characters."   (rule 1 beats rule 2)
    static func validationError(current: String, new: String, confirm: String) -> String? {
        if new.count < minimumLength {
            return tooShortMessage
        }
        if new != confirm {
            return mismatchMessage
        }
        if new == current {
            return unchangedMessage
        }
        return nil
    }
}
