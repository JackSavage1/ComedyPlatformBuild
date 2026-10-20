import Foundation

// =============================================================================
// MicRules — how a mic's raw database fields turn into what the UI shows.
// Spec section 4, rules 1, 2, 3, 10, 19 and the "sort within a day" rule.
// =============================================================================

// MARK: - Urgency (rule 3)

/// How fast a mic's signup list fills up. Anything that isn't "high" or
/// "medium" (including null and unknown strings) counts as normal.
enum Urgency: String, CaseIterable, Hashable, Sendable {
    case high
    case medium
    case normal

    init(raw: String?) {
        let key = TextRules.trimmedOrNil(raw)?.lowercased() ?? ""
        switch key {
        case "high": self = .high
        case "medium": self = .medium
        default: self = .normal
        }
    }

    /// 🔴 / 🟡 / 🟢
    var dot: String {
        switch self {
        case .high: return "🔴"
        case .medium: return "🟡"
        case .normal: return "🟢"
        }
    }

    /// Shown when the mic has no `urgency_note` of its own.
    var defaultNote: String? {
        switch self {
        case .high: return "Signs up fast — book ahead!"
        case .medium: return "Book a few days early"
        case .normal: return nil
        }
    }

    /// Hex color for the urgency dot (spec section 1).
    var colorHex: String {
        switch self {
        case .high: return "#E74C3C"
        case .medium: return "#F1C40F"
        case .normal: return "#2ECC71"
        }
    }

    /// "Normal" / "Medium" / "High" for pickers.
    var title: String { TextRules.prettify(rawValue) }
}

// MARK: - Cost badge (rule 2)

/// The three cost buckets used for the little badge next to a mic.
enum CostKind: Hashable, Sendable {
    case free
    case drinkMin
    case paid

    /// cost (null ⇒ "free") lowercased: contains "free" → free,
    /// contains "drink" → drink minimum, anything else → paid.
    init(cost: String?) {
        let lower = (TextRules.trimmedOrNil(cost) ?? "free").lowercased()
        if lower.contains("free") {
            self = .free
        } else if lower.contains("drink") {
            self = .drinkMin
        } else {
            self = .paid
        }
    }

    var emoji: String {
        switch self {
        case .free: return "🆓"
        case .drinkMin: return "🍺"
        case .paid: return "💵"
        }
    }

    var label: String {
        switch self {
        case .free: return "Free"
        case .drinkMin: return "Drink min"
        case .paid: return "Paid"
        }
    }
}

// MARK: - Signup method

/// How you get on a mic's list. Unknown strings from scrapers are kept as
/// `.other("raw text")` and shown prettified.
enum SignupMethod: Hashable, Sendable {
    case inPerson
    case online
    case email
    case instagramDM
    case other(String)

    /// The four methods offered in pickers, in the web app's order.
    static let standardCases: [SignupMethod] = [.inPerson, .online, .email, .instagramDM]

    /// nil for null/blank.
    init?(raw: String?) {
        guard let cleaned = TextRules.trimmedOrNil(raw) else { return nil }
        switch cleaned.lowercased() {
        case "in_person": self = .inPerson
        case "online": self = .online
        case "email": self = .email
        case "instagram_dm": self = .instagramDM
        default: self = .other(cleaned)
        }
    }

    /// The value stored in `open_mics.signup_method`.
    var rawValue: String {
        switch self {
        case .inPerson: return "in_person"
        case .online: return "online"
        case .email: return "email"
        case .instagramDM: return "instagram_dm"
        case .other(let raw): return raw
        }
    }

    var displayName: String {
        switch self {
        case .inPerson: return "In Person"
        case .online: return "Online"
        case .email: return "Email"
        case .instagramDM: return "Instagram DM"
        case .other(let raw): return TextRules.prettify(raw)
        }
    }
}

// MARK: - Display rules

enum MicRules {

    /// Rule 1: `display_time` if present, else `start_time` (else "").
    static func timeText(displayTime: String?, startTime: String?) -> String {
        TextRules.trimmedOrNil(displayTime) ?? TextRules.trimmedOrNil(startTime) ?? ""
    }

    /// Rule 2: the cost text, or "Free/TBD" when there isn't one.
    static func costText(_ cost: String?) -> String {
        TextRules.trimmedOrNil(cost) ?? "Free/TBD"
    }

    /// Rule 10: "5 min sets" or "Set length: N/A".
    static func setLengthText(_ minutes: Int?) -> String {
        if let minutes = minutes, minutes > 0 {
            return "\(minutes) min sets"
        }
        return "Set length: N/A"
    }

    /// Minutes after midnight for a start time, used for sorting.
    ///
    /// Accepts the stored 24-hour "HH:MM" (also "H:MM" and "HH:MM:SS"), and —
    /// because scrapers sometimes store it — "7:00 PM" / "7pm".
    /// Returns nil for anything else, so malformed times sort last.
    static func minutesSinceMidnight(_ raw: String?) -> Int? {
        guard var text = TextRules.trimmedOrNil(raw)?.lowercased() else { return nil }

        var meridiem: String?
        if text.hasSuffix("am") || text.hasSuffix("pm") {
            meridiem = String(text.suffix(2))
            text = String(text.dropLast(2)).trimmingCharacters(in: .whitespaces)
        }

        let parts = text.split(separator: ":", omittingEmptySubsequences: false)
        let allDigits = parts.allSatisfy { part in
            !part.isEmpty && part.allSatisfy { character in character.isASCII && character.isNumber }
        }
        guard (1...3).contains(parts.count), allDigits, let hour = Int(parts[0]) else { return nil }

        var minute = 0
        if parts.count >= 2 {
            guard parts[1].count == 2, let parsedMinute = Int(parts[1]) else { return nil }
            minute = parsedMinute
        } else if meridiem == nil {
            return nil // a bare "19" isn't a valid stored time
        }
        if parts.count == 3 {
            guard parts[2].count == 2, let seconds = Int(parts[2]), seconds < 60 else { return nil }
        }
        guard minute < 60 else { return nil }

        if let meridiem = meridiem {
            guard (1...12).contains(hour) else { return nil }
            let hour24 = (hour % 12) + (meridiem == "pm" ? 12 : 0)
            return hour24 * 60 + minute
        }
        guard hour < 24 else { return nil }
        return hour * 60 + minute
    }

    /// Sorts mics the way every day list shows them: by start time, with
    /// unparseable times last, then by name (case-insensitive), then by id so
    /// the order never jumps around between refreshes.
    static func sortedByStartTime(_ mics: [Mic]) -> [Mic] {
        mics.sorted { lhs, rhs in
            let left = minutesSinceMidnight(lhs.startTime)
            let right = minutesSinceMidnight(rhs.startTime)
            switch (left, right) {
            case let (l?, r?) where l != r:
                return l < r
            case (.some, .none):
                return true
            case (.none, .some):
                return false
            default:
                let leftName = lhs.name.lowercased()
                let rightName = rhs.name.lowercased()
                if leftName != rightName { return leftName < rightName }
                return lhs.id < rhs.id
            }
        }
    }

    /// Monday → Sunday (unknown days last), and by start time within a day.
    static func sortedByDayAndTime(_ mics: [Mic]) -> [Mic] {
        let byTime = sortedByStartTime(mics)
        let pairs = byTime.enumerated().map { (index, mic) in (mic.id, index) }
        let position = Dictionary(pairs, uniquingKeysWith: { first, _ in first })
        return byTime.sorted { lhs, rhs in
            let leftDay = lhs.weekday?.rawValue ?? 8
            let rightDay = rhs.weekday?.rawValue ?? 8
            if leftDay != rightDay { return leftDay < rightDay }
            return (position[lhs.id] ?? 0) < (position[rhs.id] ?? 0)
        }
    }
}

// MARK: - Time picker conversion (rule 19)

enum TimeRules {

    /// The stored, zero-padded 24-hour start time: (19, 0) -> "19:00".
    static func startTimeString(hour: Int, minute: Int) -> String {
        String(format: "%02d:%02d", hour, minute)
    }

    /// The friendly display time: (19, 0) -> "7:00 PM", (0, 5) -> "12:05 AM".
    static func displayTimeString(hour: Int, minute: Int) -> String {
        let hour12 = hour % 12 == 0 ? 12 : hour % 12
        let suffix = hour < 12 ? "AM" : "PM"
        return "\(hour12):" + String(format: "%02d", minute) + " " + suffix
    }

    /// Splits a stored start time back into hour and minute for the time
    /// picker. nil when the stored value can't be parsed.
    static func hourAndMinute(from startTime: String?) -> (hour: Int, minute: Int)? {
        guard let total = MicRules.minutesSinceMidnight(startTime) else { return nil }
        return (total / 60, total % 60)
    }
}

// MARK: - Mic conveniences

extension Mic {
    /// Parsed `day_of_week`; nil when the stored text isn't a weekday.
    var weekday: Weekday? { Weekday(name: dayOfWeek) }

    var urgencyLevel: Urgency { Urgency(raw: urgency) }

    /// Rule 1.
    var timeText: String { MicRules.timeText(displayTime: displayTime, startTime: startTime) }

    /// Rule 2: cost or "Free/TBD".
    var costText: String { MicRules.costText(cost) }

    /// Rule 2: badge bucket.
    var costKind: CostKind { CostKind(cost: cost) }

    /// Rule 10.
    var setLengthText: String { MicRules.setLengthText(setLengthMin) }

    var startMinutes: Int? { MicRules.minutesSinceMidnight(startTime) }

    var signupMethodKind: SignupMethod? { SignupMethod(raw: signupMethod) }

    /// Rule 3: the mic's own urgency note, or the default for its level.
    var urgencyMessage: String? { TextRules.trimmedOrNil(urgencyNote) ?? urgencyLevel.defaultNote }

    /// Rule 8: a tappable sign-up URL, or nil.
    var signupLink: URL? { SignupRules.signupURL(from: signupUrl) }

    /// Rule 9: a valid Instagram handle (no "@"), or nil.
    var instagramHandle: String? { InstagramRules.handle(from: instagram) }

    /// Venue website, normalized the same way as sign-up links.
    var venueLink: URL? { SignupRules.signupURL(from: venueUrl) }

    var hasCoordinates: Bool { latitude != nil && longitude != nil }

    /// "East Village, Manhattan" (either part may be missing; "" if both are).
    var locationText: String { TextRules.joinNonBlank([neighborhood, borough], separator: ", ") }

    /// "Mondays at 7:00 PM" (falls back to the raw day text).
    var scheduleText: String {
        let dayText = weekday?.pluralName ?? (TextRules.trimmedOrNil(dayOfWeek) ?? "")
        let time = timeText
        if dayText.isEmpty { return time }
        return time.isEmpty ? dayText : "\(dayText) at \(time)"
    }

    /// Rule 7 for this mic happening on `date`.
    func signupDeadline(on date: CalendarDate, today: CalendarDate) -> SignupDeadline? {
        SignupDeadline.compute(advanceDays: advanceDays, micDate: date, today: today)
    }
}
