import Foundation

// =============================================================================
// Signup rules — deadlines (rule 7), sign-up links (rule 8) and Instagram
// handles (rule 9).
// =============================================================================

// MARK: - Signup deadline (rule 7)

/// When you have to sign up for a mic that books `advance_days` ahead.
enum SignupDeadline: Hashable, Sendable {
    /// The signup date is already behind us.
    case passed
    /// Sign up today.
    case today
    /// Sign up tomorrow.
    case tomorrow
    /// Sign up on `signupBy`, which is `daysUntil` (≥ 2) days away.
    case upcoming(signupBy: CalendarDate, daysUntil: Int)

    /// Only mics with `advance_days > 0` have a deadline.
    ///
    ///     signupBy  = micDate − advanceDays
    ///     daysUntil = signupBy − today
    static func compute(advanceDays: Int?, micDate: CalendarDate, today: CalendarDate) -> SignupDeadline? {
        guard let advanceDays = advanceDays, advanceDays > 0 else { return nil }
        let signupBy = micDate.adding(days: -advanceDays)
        let daysUntil = today.days(until: signupBy)
        if signupBy < today { return .passed }
        if daysUntil == 0 { return .today }
        if daysUntil == 1 { return .tomorrow }
        return .upcoming(signupBy: signupBy, daysUntil: daysUntil)
    }

    /// The exact copy from the web app, e.g. "📅 Sign up by Thu 10/23 (3 days)".
    var text: String {
        switch self {
        case .passed:
            return "⚠️ Signup deadline passed!"
        case .today:
            return "🔴 Sign up TODAY!"
        case .tomorrow:
            return "🟡 Sign up by TOMORROW!"
        case let .upcoming(signupBy, daysUntil):
            return "📅 Sign up by \(signupBy.weekday.shortName) \(signupBy.mmdd) (\(daysUntil) days)"
        }
    }

    /// True for "today" and "tomorrow" — worth highlighting.
    var isUrgent: Bool {
        switch self {
        case .today, .tomorrow: return true
        case .passed, .upcoming: return false
        }
    }
}

// MARK: - Sign-up / website links (rule 8)

enum SignupRules {

    /// Turns the free-text `signup_url` into something safe to open.
    ///
    /// 1. Trim.
    /// 2. Starts with http:// or https:// and has a host → use it.
    /// 3. Otherwise, no spaces and contains a "." → prefix "https://".
    /// 4. Otherwise → nil (show the raw text as a note, no button).
    static func signupURL(from raw: String?) -> URL? {
        guard let text = TextRules.trimmedOrNil(raw) else { return nil }
        let lower = text.lowercased()

        if lower.hasPrefix("http://") || lower.hasPrefix("https://") {
            return urlWithHost(text)
        }
        if !text.contains(where: { $0.isWhitespace }) && text.contains(".") {
            return urlWithHost("https://" + text)
        }
        return nil
    }

    /// The raw signup text to show as a note when it isn't a usable link
    /// (e.g. "Sign up at the bar"). nil when it *is* a link or is blank.
    static func signupNote(from raw: String?) -> String? {
        guard let text = TextRules.trimmedOrNil(raw) else { return nil }
        return signupURL(from: text) == nil ? text : nil
    }

    private static func urlWithHost(_ text: String) -> URL? {
        guard let url = URL(string: text),
              let host = URLComponents(url: url, resolvingAgainstBaseURL: false)?.host,
              !host.isEmpty
        else { return nil }
        return url
    }
}

// MARK: - Instagram (rule 9)

enum InstagramRules {

    /// Extracts a valid handle (without "@") from whatever was stored:
    /// "@nycmic", "nycmic", "https://instagram.com/nycmic/?hl=en", …
    /// Returns nil when the result isn't a valid handle.
    static func handle(from raw: String?) -> String? {
        guard var text = TextRules.trimmedOrNil(raw) else { return nil }

        // A full profile URL: keep only the first path segment after the domain.
        if let range = text.range(of: "instagram.com/", options: .caseInsensitive) {
            let rest = text[range.upperBound...]
            let segments = rest.split(separator: "/", omittingEmptySubsequences: true)
            text = segments.first.map { String($0) } ?? ""
        }

        // Drop any "?query".
        if let questionMark = text.firstIndex(of: "?") {
            text = String(text[..<questionMark])
        }

        // Strip leading "@" and trailing "/".
        while text.hasPrefix("@") { text.removeFirst() }
        while text.hasSuffix("/") { text.removeLast() }
        text = text.trimmingCharacters(in: .whitespaces)

        return isValidHandle(text) ? text : nil
    }

    /// Instagram usernames: 1–30 of A–Z, a–z, 0–9, "." and "_".
    static func isValidHandle(_ handle: String) -> Bool {
        guard (1...30).contains(handle.count) else { return false }
        return handle.allSatisfy { character in
            character.isASCII && (character.isLetter || character.isNumber || character == "." || character == "_")
        }
    }

    /// https://instagram.com/{handle}
    static func profileURL(handle: String) -> URL? {
        URL(string: "https://instagram.com/\(handle)")
    }
}

// MARK: - Filters (rules 4 and 5)

/// The Calendar's cost filter (rule 4).
enum CostFilter: String, CaseIterable, Identifiable, Hashable, Sendable {
    case all = "All"
    case freeOnly = "Free only"
    case underSix = "Under $6"
    case drinkMinOnly = "Drink min only"

    var id: String { rawValue }
    var title: String { rawValue }

    func matches(cost: String?) -> Bool {
        let cleaned = TextRules.trimmedOrNil(cost)
        let lower = cleaned?.lowercased() ?? ""
        switch self {
        case .all:
            return true
        case .freeOnly:
            return cleaned == nil || lower.contains("free")
        case .underSix:
            // FIX vs. Streamlit: its regex `\$[1-5]` also matched "$10".
            if cleaned == nil || lower.contains("free") { return true }
            guard let amount = CostFilter.firstDollarAmount(in: lower) else { return false }
            return amount < 6
        case .drinkMinOnly:
            return lower.contains("drink")
        }
    }

    /// The first "$N" or "$N.NN" amount in the text, e.g. "$5 + 1 drink" → 5.
    /// A "$" not followed by a digit is skipped.
    static func firstDollarAmount(in text: String) -> Double? {
        let characters = Array(text)
        var index = 0
        while index < characters.count {
            if characters[index] == "$" {
                var cursor = index + 1
                var number = ""
                while cursor < characters.count, isDigit(characters[cursor]) {
                    number.append(characters[cursor])
                    cursor += 1
                }
                if !number.isEmpty {
                    if cursor + 1 < characters.count, characters[cursor] == ".", isDigit(characters[cursor + 1]) {
                        number.append(".")
                        cursor += 1
                        while cursor < characters.count, isDigit(characters[cursor]) {
                            number.append(characters[cursor])
                            cursor += 1
                        }
                    }
                    return Double(number)
                }
            }
            index += 1
        }
        return nil
    }

    private static func isDigit(_ character: Character) -> Bool {
        character.isASCII && character.isNumber
    }
}

/// The Calendar's signup-type filter (rule 5).
enum SignupFilter: String, CaseIterable, Identifiable, Hashable, Sendable {
    case all = "All"
    case online = "Online"
    case inPerson = "In-person"
    case email = "Email"
    case instagramDM = "Instagram DM"

    var id: String { rawValue }
    var title: String { rawValue }

    /// The `signup_method` value this option selects (nil for All).
    var methodRawValue: String? {
        switch self {
        case .all: return nil
        case .online: return "online"
        case .inPerson: return "in_person"
        case .email: return "email"
        case .instagramDM: return "instagram_dm"
        }
    }

    func matches(signupMethod: String?) -> Bool {
        guard let wanted = methodRawValue else { return true }
        return TextRules.trimmedOrNil(signupMethod)?.lowercased() == wanted
    }
}
