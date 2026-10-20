import Foundation

// =============================================================================
// TextRules — the "blank = null" rule and small text helpers.
//
// Every text column in the database can come back as null, "", or "   ".
// The Streamlit app's safe_str() treated all three as "not there"; this is the
// Swift version of that idea (spec section 3.5).
// =============================================================================
enum TextRules {

    /// Trims whitespace/newlines and turns an empty result into `nil`.
    ///
    ///     trimmedOrNil("  Free ")  -> "Free"
    ///     trimmedOrNil("   ")      -> nil
    ///     trimmedOrNil(nil)        -> nil
    static func trimmedOrNil(_ value: String?) -> String? {
        guard let value = value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// True when the string is nil or whitespace-only.
    static func isBlank(_ value: String?) -> Bool {
        trimmedOrNil(value) == nil
    }

    /// Turns a raw database code into readable text, like Python's
    /// `.replace("_", " ").title()`:
    ///
    ///     prettify("in_person")  -> "In Person"
    ///     prettify("DECENT")     -> "Decent"
    static func prettify(_ raw: String) -> String {
        let words = raw
            .replacingOccurrences(of: "_", with: " ")
            .split(separator: " ", omittingEmptySubsequences: true)
        return words
            .map { word -> String in
                let lower = word.lowercased()
                guard let first = lower.first else { return lower }
                return String(first).uppercased() + String(lower.dropFirst())
            }
            .joined(separator: " ")
    }

    /// Joins the non-blank parts with a separator, skipping nil/blank values.
    ///
    ///     joinNonBlank(["East Village", nil, "Manhattan"], separator: ", ")
    ///       -> "East Village, Manhattan"
    static func joinNonBlank(_ parts: [String?], separator: String) -> String {
        parts.compactMap { trimmedOrNil($0) }.joined(separator: separator)
    }
}
