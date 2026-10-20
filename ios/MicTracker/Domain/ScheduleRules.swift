import Foundation

// =============================================================================
// Schedule rules — next occurrence (rule 16) and the Calendar's week labels
// (spec 6.3).
// =============================================================================
enum ScheduleRules {

    /// Rule 16: today if `today` already is that weekday, else the next date
    /// that is.
    static func nextOccurrence(of weekday: Weekday, from today: CalendarDate) -> CalendarDate {
        let difference = CalendarDate.floorMod(weekday.rawValue - today.weekday.rawValue, 7)
        return today.adding(days: difference)
    }

    /// The Calendar allows browsing from last week (−1) to three weeks out (+3).
    static let weekOffsetRange: ClosedRange<Int> = -1...3

    /// The Monday of the week `offset` weeks from the one containing `today`.
    static func monday(weekOffset offset: Int, today: CalendarDate) -> CalendarDate {
        today.startOfWeek.adding(weeks: offset)
    }

    /// The seven dates Monday…Sunday starting at `monday`.
    static func weekDates(startingAt monday: CalendarDate) -> [CalendarDate] {
        (0..<7).map { monday.adding(days: $0) }
    }

    /// "Last Week" / "This Week" / "Next Week" / "2 Weeks Out".
    static func weekTitle(offset: Int) -> String {
        switch offset {
        case 0: return "This Week"
        case 1: return "Next Week"
        case let n where n > 1: return "\(n) Weeks Out"
        case -1: return "Last Week"
        default: return "\(-offset) Weeks Ago"
        }
    }

    /// "Oct 20 – Oct 26, 2026" for the week starting `monday`.
    static func weekRangeText(monday: CalendarDate) -> String {
        "\(monday.shortText) – \(monday.adding(days: 6).shortTextWithYear)"
    }
}

// =============================================================================
// Borough colors (rule 17) and NYC geocoding bounds (rule 18).
// =============================================================================

/// A latitude/longitude pair (kept free of CoreLocation so it's testable).
struct GeoPoint: Hashable, Sendable {
    let latitude: Double
    let longitude: Double
}

enum BoroughRules {
    /// The five boroughs, in the order every picker shows them.
    static let all: [String] = ["Manhattan", "Brooklyn", "Queens", "Bronx", "Staten Island"]

    /// Rule 17 map / legend colors. Unknown or missing → gray.
    static func colorHex(for borough: String?) -> String {
        let key = TextRules.trimmedOrNil(borough)?.lowercased() ?? ""
        switch key {
        case "manhattan": return "#FF6347"
        case "brooklyn": return "#1E90FF"
        case "queens": return "#32CD32"
        case "bronx", "the bronx": return "#FFA500"
        case "staten island": return "#9370DB"
        default: return "#808080"
        }
    }
}

enum GeoRules {
    /// Rule 18: 40.4 < lat < 41.0 and −74.3 < lon < −73.5.
    static func isInNYC(latitude: Double, longitude: Double) -> Bool {
        latitude > 40.4 && latitude < 41.0 && longitude > -74.3 && longitude < -73.5
    }

    static func isInNYC(_ point: GeoPoint) -> Bool {
        isInNYC(latitude: point.latitude, longitude: point.longitude)
    }

    /// The address strings to try, in order (spec 6.8):
    /// "{address}, {borough}, New York, NY" then "{address}, New York, NY".
    /// Empty when there's no address.
    static func geocodeQueries(address: String?, borough: String?) -> [String] {
        guard let address = TextRules.trimmedOrNil(address) else { return [] }
        var queries: [String] = []
        if let borough = TextRules.trimmedOrNil(borough) {
            queries.append("\(address), \(borough), New York, NY")
        }
        let plain = "\(address), New York, NY"
        if !queries.contains(plain) {
            queries.append(plain)
        }
        return queries
    }
}
