import Foundation

// =============================================================================
// Calendar models — the pure (Foundation-only, unit-tested) logic behind the
// Weekly Calendar screen (spec 6.3):
//
//   * CalendarWeek    — which 7 days are on screen for a week offset, the
//                       header text, and which day is selected by default.
//   * CalendarFilters — the filter sheet's state and how it narrows the list.
//
// The individual rules (cost filter, signup filter, visited, week titles)
// come from the Domain layer; this file only combines them.
// =============================================================================

// MARK: - CalendarWeek

/// The week being shown: `offset` weeks from the week that contains `today`
/// (−1 = last week … +3 = three weeks out).
struct CalendarWeek: Hashable {
    let offset: Int
    let today: CalendarDate
    /// Monday of the shown week.
    let monday: CalendarDate
    /// Monday…Sunday.
    let dates: [CalendarDate]

    init(offset: Int, today: CalendarDate) {
        let range = ScheduleRules.weekOffsetRange
        let clamped = min(max(offset, range.lowerBound), range.upperBound)
        let monday = ScheduleRules.monday(weekOffset: clamped, today: today)
        self.offset = clamped
        self.today = today
        self.monday = monday
        self.dates = ScheduleRules.weekDates(startingAt: monday)
    }

    var sunday: CalendarDate { monday.adding(days: 6) }

    /// "This Week", "Next Week", "2 Weeks Out", "Last Week".
    var title: String { ScheduleRules.weekTitle(offset: offset) }

    /// "Oct 20 – Oct 26, 2026"
    var rangeText: String { ScheduleRules.weekRangeText(monday: monday) }

    var canGoBack: Bool { offset > ScheduleRules.weekOffsetRange.lowerBound }
    var canGoForward: Bool { offset < ScheduleRules.weekOffsetRange.upperBound }

    func contains(_ date: CalendarDate) -> Bool {
        date >= monday && date <= sunday
    }

    func isToday(_ date: CalendarDate) -> Bool { date == today }
    func isPast(_ date: CalendarDate) -> Bool { date < today }

    /// The day to show: the user's pick if it's in this week, otherwise
    /// today (when today is in this week), otherwise Monday.
    func selection(preferring candidate: CalendarDate?) -> CalendarDate {
        if let candidate = candidate, contains(candidate) { return candidate }
        if contains(today) { return today }
        return monday
    }

    /// How many of `mics` happen on each date of the week.
    func micCounts(_ mics: [Mic]) -> [CalendarDate: Int] {
        var countsByWeekday: [Weekday: Int] = [:]
        for mic in mics {
            if let weekday = mic.weekday {
                countsByWeekday[weekday, default: 0] += 1
            }
        }
        var result: [CalendarDate: Int] = [:]
        for date in dates {
            result[date] = countsByWeekday[date.weekday] ?? 0
        }
        return result
    }

    /// The mics happening on `date`, sorted by start time.
    static func mics(_ mics: [Mic], on date: CalendarDate) -> [Mic] {
        MicRules.sortedByStartTime(mics.filter { $0.weekday == date.weekday })
    }
}

// MARK: - CalendarFilters

/// Everything the Calendar's filter sheet can set.
struct CalendarFilters: Hashable {
    /// Selected boroughs (empty = all).
    var boroughs: Set<String> = []
    /// Selected neighborhoods (empty = all).
    var neighborhoods: Set<String> = []
    var cost: CostFilter = .all
    var signup: SignupFilter = .all
    /// Hide mics with at least one logged set (rule 6).
    var onlyUnvisited = false
    /// Show only mics marked Going on some day of the shown week.
    var onlyGoing = false

    init() {}

    /// The number on the filter button's badge.
    var activeCount: Int {
        var count = 0
        if !boroughs.isEmpty { count += 1 }
        if !neighborhoods.isEmpty { count += 1 }
        if cost != .all { count += 1 }
        if signup != .all { count += 1 }
        if onlyUnvisited { count += 1 }
        if onlyGoing { count += 1 }
        return count
    }

    var isActive: Bool { activeCount > 0 }

    mutating func reset() {
        self = CalendarFilters()
    }

    /// Does `mic` pass every active filter?
    ///
    /// - Parameters:
    ///   - visitedMicIDs: mics with ≥1 logged set (rule 6).
    ///   - goingMicIDs: mics marked Going during the shown week.
    func matches(_ mic: Mic, visitedMicIDs: Set<Int>, goingMicIDs: Set<Int>) -> Bool {
        if !boroughs.isEmpty {
            guard let borough = TextRules.trimmedOrNil(mic.borough)?.lowercased(),
                  boroughs.contains(where: { $0.lowercased() == borough })
            else { return false }
        }
        if !neighborhoods.isEmpty {
            guard let neighborhood = TextRules.trimmedOrNil(mic.neighborhood),
                  neighborhoods.contains(neighborhood)
            else { return false }
        }
        if !cost.matches(cost: mic.cost) { return false }
        if !signup.matches(signupMethod: mic.signupMethod) { return false }
        if onlyUnvisited && visitedMicIDs.contains(mic.id) { return false }
        if onlyGoing && !goingMicIDs.contains(mic.id) { return false }
        return true
    }

    /// The mics that pass every filter, in their original order.
    func apply(to mics: [Mic], visitedMicIDs: Set<Int>, goingMicIDs: Set<Int>) -> [Mic] {
        mics.filter { matches($0, visitedMicIDs: visitedMicIDs, goingMicIDs: goingMicIDs) }
    }

    // MARK: Editing helpers (used by the filter sheet)

    /// Turns a borough on/off and drops any selected neighborhood that is no
    /// longer offered (the neighborhood list follows the borough selection).
    mutating func toggleBorough(_ borough: String, availableMics: [Mic]) {
        if boroughs.contains(borough) {
            boroughs.remove(borough)
        } else {
            boroughs.insert(borough)
        }
        let stillOffered = Set(CalendarFilters.neighborhoodOptions(from: availableMics, boroughs: boroughs))
        neighborhoods = neighborhoods.intersection(stillOffered)
    }

    mutating func toggleNeighborhood(_ neighborhood: String) {
        if neighborhoods.contains(neighborhood) {
            neighborhoods.remove(neighborhood)
        } else {
            neighborhoods.insert(neighborhood)
        }
    }

    /// Neighborhood choices: those of mics in the selected boroughs, or of
    /// every mic when no borough is selected. Distinct, non-blank, sorted.
    static func neighborhoodOptions(from mics: [Mic], boroughs: Set<String>) -> [String] {
        let wanted = Set(boroughs.map { $0.lowercased() })
        let source: [Mic]
        if wanted.isEmpty {
            source = mics
        } else {
            source = mics.filter { mic in
                guard let borough = TextRules.trimmedOrNil(mic.borough)?.lowercased() else { return false }
                return wanted.contains(borough)
            }
        }
        let names = Set(source.compactMap { TextRules.trimmedOrNil($0.neighborhood) })
        return names.sorted { lhs, rhs in
            let left = lhs.lowercased()
            let right = rhs.lowercased()
            return left != right ? left < right : lhs < rhs
        }
    }

    /// Mic ids with at least one logged set, from `AppStore.loggedVisitCounts`.
    static func visitedMicIDs(from loggedVisitCounts: [Int: Int]) -> Set<Int> {
        Set(loggedVisitCounts.filter { $0.value > 0 }.map { $0.key })
    }
}
