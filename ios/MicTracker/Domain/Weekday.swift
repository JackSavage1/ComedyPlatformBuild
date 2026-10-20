import Foundation

// =============================================================================
// Weekday — Monday…Sunday, numbered the ISO way (Monday = 1, Sunday = 7).
//
// The database stores `day_of_week` as a full English name ("Monday"). Weeks
// in this app start on Monday (spec 3.5), which is why the raw values follow
// ISO-8601 instead of Apple's Calendar (where Sunday = 1).
// =============================================================================
enum Weekday: Int, CaseIterable, Identifiable, Comparable, Hashable, Sendable {
    case monday = 1
    case tuesday = 2
    case wednesday = 3
    case thursday = 4
    case friday = 5
    case saturday = 6
    case sunday = 7

    var id: Int { rawValue }

    /// "Monday" — exactly what the database stores.
    var name: String {
        switch self {
        case .monday: return "Monday"
        case .tuesday: return "Tuesday"
        case .wednesday: return "Wednesday"
        case .thursday: return "Thursday"
        case .friday: return "Friday"
        case .saturday: return "Saturday"
        case .sunday: return "Sunday"
        }
    }

    /// "Mon"
    var shortName: String { String(name.prefix(3)) }

    /// "Mondays" — used for "Mondays at 7:00 PM".
    var pluralName: String { name + "s" }

    /// The day after this one (Sunday wraps to Monday).
    var next: Weekday { Weekday(rawValue: rawValue % 7 + 1) ?? .monday }

    /// The day before this one (Monday wraps to Sunday).
    var previous: Weekday { Weekday(rawValue: (rawValue + 5) % 7 + 1) ?? .sunday }

    /// Parses "Monday", "monday", " MONDAY ", or the 3-letter "Mon".
    /// Returns nil for anything else (scrapers sometimes send junk).
    init?(name: String?) {
        guard let cleaned = TextRules.trimmedOrNil(name)?.lowercased() else { return nil }
        for day in Weekday.allCases {
            let full = day.name.lowercased()
            if cleaned == full || cleaned == String(full.prefix(3)) {
                self = day
                return
            }
        }
        return nil
    }

    static func < (lhs: Weekday, rhs: Weekday) -> Bool {
        lhs.rawValue < rhs.rawValue
    }
}
