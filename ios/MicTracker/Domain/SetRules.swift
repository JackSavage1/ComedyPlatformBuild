import Foundation

// =============================================================================
// Set rules — tags (rule 14), rating emoji (rule 15), visits (rule 6),
// week streak (rule 11), month-over-month delta (rule 12) and the OLS
// trendline (rule 13).
// =============================================================================

// MARK: - Crowd size / recording type

/// `my_sets.crowd_size`. Unknown strings are kept as `.other`.
enum CrowdSize: Hashable, Sendable {
    case empty
    case sparse
    case decent
    case packed
    case other(String)

    /// Picker options in order; the form defaults to `.decent`.
    static let standardCases: [CrowdSize] = [.empty, .sparse, .decent, .packed]

    init?(raw: String?) {
        guard let cleaned = TextRules.trimmedOrNil(raw) else { return nil }
        switch cleaned.lowercased() {
        case "empty": self = .empty
        case "sparse": self = .sparse
        case "decent": self = .decent
        case "packed": self = .packed
        default: self = .other(cleaned)
        }
    }

    var rawValue: String {
        switch self {
        case .empty: return "empty"
        case .sparse: return "sparse"
        case .decent: return "decent"
        case .packed: return "packed"
        case .other(let raw): return raw
        }
    }

    var displayName: String { TextRules.prettify(rawValue) }

    /// Relative point size for the Crowd-vs-Set chart (stats card 3):
    /// empty 10, sparse 20, decent 35, packed 50, unknown 20.
    var chartWeight: Double {
        switch self {
        case .empty: return 10
        case .sparse: return 20
        case .decent: return 35
        case .packed: return 50
        case .other: return 20
        }
    }

    /// Same as `chartWeight`, but for a raw (possibly null) database value.
    static func chartWeight(forRaw raw: String?) -> Double {
        CrowdSize(raw: raw)?.chartWeight ?? 20
    }
}

/// `my_sets.recording_type`. ("none" is spelled `.noRecording` so it can't be
/// confused with `Optional.none`.)
enum RecordingType: Hashable, Sendable {
    case noRecording
    case video
    case audio
    case other(String)

    /// Picker options in order; the form defaults to `.noRecording`.
    static let standardCases: [RecordingType] = [.noRecording, .video, .audio]

    init?(raw: String?) {
        guard let cleaned = TextRules.trimmedOrNil(raw) else { return nil }
        switch cleaned.lowercased() {
        case "none": self = .noRecording
        case "video": self = .video
        case "audio": self = .audio
        default: self = .other(cleaned)
        }
    }

    var rawValue: String {
        switch self {
        case .noRecording: return "none"
        case .video: return "video"
        case .audio: return "audio"
        case .other(let raw): return raw
        }
    }

    var displayName: String { TextRules.prettify(rawValue) }
}

// MARK: - Tags (rule 14)

enum TagSentiment: Hashable, Sendable {
    case positive
    case negative
    case neutral

    /// Green / red / blue.
    var colorHex: String {
        switch self {
        case .positive: return "#2ECC71"
        case .negative: return "#E74C3C"
        case .neutral: return "#3498DB"
        }
    }
}

enum TagRules {
    /// The fixed tag options, in the web app's order.
    static let options: [String] = [
        "killed", "bombed", "new_bit", "crowd_work", "got_laughs",
        "nervous", "confident", "short_set", "long_set", "got_spotted",
    ]

    static let positiveTags: Set<String> = ["killed", "got_laughs", "confident", "crowd_work", "got_spotted"]
    static let negativeTags: Set<String> = ["bombed", "nervous"]

    /// "killed, crowd_work,,"  ->  ["killed", "crowd_work"]
    static func parse(_ raw: String?) -> [String] {
        guard let raw = raw else { return [] }
        return raw
            .split(separator: ",", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    /// ["killed", "crowd_work"] -> "killed,crowd_work"; nil when empty
    /// (so the database stores null, like the web app).
    static func join(_ tags: [String]) -> String? {
        let cleaned = tags
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return cleaned.isEmpty ? nil : cleaned.joined(separator: ",")
    }

    static func sentiment(of tag: String) -> TagSentiment {
        let key = tag.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if positiveTags.contains(key) { return .positive }
        if negativeTags.contains(key) { return .negative }
        return .neutral
    }
}

// MARK: - Sets

/// A (date, rating) pair used by the streak / delta / trendline math.
/// Kept as a tiny struct so the rules can be tested without building
/// whole `SetEntry` values.
struct DatedRating: Hashable, Sendable {
    let date: CalendarDate
    let rating: Double
}

/// y = intercept + slope · x, where x = days since `origin` (the first set).
struct OLSTrendLine: Hashable, Sendable {
    let origin: CalendarDate
    let slope: Double
    let intercept: Double

    func value(at date: CalendarDate) -> Double {
        intercept + slope * Double(origin.days(until: date))
    }
}

enum SetRules {

    /// Rule 15: 📋 planned; ≥ 8 🔥; ≥ 5 👍; else 😬.
    static func ratingEmoji(setRating: Int?) -> String {
        guard let rating = setRating else { return "📋" }
        if rating >= 8 { return "🔥" }
        if rating >= 5 { return "👍" }
        return "😬"
    }

    /// Rule 6: logged sets (set_rating != null) per mic id. Planned rows
    /// don't count — FIX vs. Streamlit, which counted them.
    static func loggedVisitCounts(_ sets: [SetEntry]) -> [Int: Int] {
        var counts: [Int: Int] = [:]
        for entry in sets where entry.isLogged {
            if let micID = entry.openMicId {
                counts[micID, default: 0] += 1
            }
        }
        return counts
    }

    /// Rule 11: consecutive Monday-start weeks with ≥ 1 logged set.
    ///
    /// Start at the current week; if it has no sets yet, start at last week
    /// (the streak isn't broken on Monday morning). Walk backwards one week
    /// at a time using real date math, so 53-week years and year boundaries
    /// just work.
    static func weekStreak(loggedDates: [CalendarDate], today: CalendarDate) -> Int {
        let weeksWithSets = Set(loggedDates.map { $0.startOfWeek })
        guard !weeksWithSets.isEmpty else { return 0 }

        var week = today.startOfWeek
        if !weeksWithSets.contains(week) {
            week = week.adding(weeks: -1)
        }
        var streak = 0
        while weeksWithSets.contains(week) {
            streak += 1
            week = week.adding(weeks: -1)
        }
        return streak
    }

    /// Rule 12: average rating this calendar month minus last calendar month,
    /// rounded to one decimal. nil unless both months have sets.
    static func monthOverMonthDelta(_ ratings: [DatedRating], today: CalendarDate) -> Double? {
        let thisMonth = today.startOfMonth
        let lastMonth = thisMonth.adding(months: -1)

        let current = ratings.filter { $0.date.startOfMonth == thisMonth }.map { $0.rating }
        let previous = ratings.filter { $0.date.startOfMonth == lastMonth }.map { $0.rating }
        guard !current.isEmpty, !previous.isEmpty else { return nil }

        let delta = average(current) - average(previous)
        let rounded = (delta * 10).rounded() / 10
        return rounded == 0 ? 0 : rounded // avoid "-0.0"
    }

    /// "+0.4 vs last month" / "-1.2 vs last month"
    static func deltaText(_ delta: Double) -> String {
        String(format: "%+.1f", delta) + " vs last month"
    }

    /// Rule 13: least-squares line through (days since first set, rating).
    /// Needs at least 2 points on at least 2 different dates.
    static func olsTrend(_ ratings: [DatedRating]) -> OLSTrendLine? {
        guard ratings.count >= 2, let origin = ratings.map({ $0.date }).min() else { return nil }
        let distinctDates = Set(ratings.map { $0.date })
        guard distinctDates.count >= 2 else { return nil }

        let xs = ratings.map { Double(origin.days(until: $0.date)) }
        let ys = ratings.map { $0.rating }
        let meanX = average(xs)
        let meanY = average(ys)

        var covariance = 0.0
        var varianceX = 0.0
        for (x, y) in zip(xs, ys) {
            covariance += (x - meanX) * (y - meanY)
            varianceX += (x - meanX) * (x - meanX)
        }
        guard varianceX > 0 else { return nil }

        let slope = covariance / varianceX
        return OLSTrendLine(origin: origin, slope: slope, intercept: meanY - slope * meanX)
    }

    static func average(_ values: [Double]) -> Double {
        values.isEmpty ? 0 : values.reduce(0, +) / Double(values.count)
    }
}

// MARK: - SetEntry conveniences

extension SetEntry {
    /// A row created by "Going" that hasn't been logged yet.
    var isPlanned: Bool { setRating == nil }

    /// A real, logged performance.
    var isLogged: Bool { setRating != nil }

    /// Rule 14: parsed tags.
    var tagList: [String] { TagRules.parse(tags) }

    /// Rule 15.
    var ratingEmoji: String { SetRules.ratingEmoji(setRating: setRating) }

    var crowdSizeKind: CrowdSize? { CrowdSize(raw: crowdSize) }

    var recordingKind: RecordingType? { RecordingType(raw: recordingType) }

    /// (date, rating) for logged sets; nil for planned rows.
    var datedRating: DatedRating? {
        guard let rating = setRating else { return nil }
        return DatedRating(date: datePerformed, rating: Double(rating))
    }
}
