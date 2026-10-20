import Foundation

// =============================================================================
// StatsSnapshot — every number and series the Stats tab draws (spec 6.7, a
// port of pages/3_📊_Dashboard.py), computed in one pass from the store's
// data.
//
// Pure Foundation code (no SwiftUI, no Charts) so it can be unit tested in
// MicTrackerTests/StatsTests.swift. Like the web dashboard, it only looks at
// LOGGED sets — planned rows (no rating yet) would skew every chart.
// =============================================================================

/// A labelled count for bar charts ("Mon" → 3, "Bushwick" → 5, …).
struct StatsCount: Identifiable, Hashable {
    let label: String
    let count: Int

    var id: String { label }
}

/// One logged set, ready to plot.
struct StatsRatingPoint: Identifiable, Hashable {
    /// The set's id.
    let id: Int
    let date: CalendarDate
    let setRating: Double
    /// nil when the set has no crowd rating (left off the crowd chart).
    let crowdRating: Double?
    /// Relative dot size from the crowd size (empty 10 … packed 50).
    let crowdWeight: Double
    /// The color group ("Unknown venue" when the mic is missing).
    let venue: String
    let micName: String
}

/// How often one tag was used in one month (Tags Over Time).
struct StatsTagMonth: Identifiable, Hashable {
    /// "2026-10"
    let month: String
    let tag: String
    let count: Int

    var id: String { "\(month)|\(tag)" }
}

/// The two ends of the OLS trendline, ready to draw as a line.
struct StatsTrendSegment: Hashable {
    let start: CalendarDate
    let startValue: Double
    let end: CalendarDate
    let endValue: Double
}

/// One row of the "My Mic Ratings" table.
struct StatsMicRow: Identifiable, Hashable {
    /// The mic's id.
    let id: Int
    let name: String
    let venue: String
    /// The owner's own 0–10 rating of the mic (nil = not rated).
    let myRating: Double?
    let visits: Int
    let averageSet: Double
    /// nil when none of the visits has a crowd rating.
    let averageCrowd: Double?
    let lastVisit: CalendarDate

    /// "8.5" or "—" (0 counts as "not rated", like Mic Detail's slider).
    var myRatingText: String {
        guard let rating = myRating, rating > 0 else { return "—" }
        return String(format: "%.1f", rating)
    }

    var averageSetText: String { String(format: "%.1f", averageSet) }

    var averageCrowdText: String {
        guard let crowd = averageCrowd else { return "—" }
        return String(format: "%.1f", crowd)
    }

    /// "10/20/2026"
    var lastVisitText: String { lastVisit.mmddyyyy }
}

struct StatsSnapshot {

    // MARK: Card 1 — metrics

    /// Logged sets only.
    let totalSets: Int
    /// Average set rating (0 when there are no sets).
    let averageRating: Double
    /// Rule 12: this month's average minus last month's (nil unless both
    /// months have sets).
    let ratingDelta: Double?
    /// Rule 11.
    let weekStreak: Int
    /// Distinct venues of the mics you've logged sets at.
    let venuesVisited: Int
    /// How many active mics there are ("4 / 57").
    let activeMicCount: Int

    // MARK: Cards 2–3 — rating charts

    /// Oldest first.
    let ratingPoints: [StatsRatingPoint]
    /// Rule 13 (nil with fewer than 2 sets or only one distinct date).
    let trend: StatsTrendSegment?
    /// Number of distinct venues in `ratingPoints` (color groups).
    let venueCount: Int

    // MARK: Cards 4–8 — breakdowns

    /// Monday → Sunday by the mic's day_of_week, zeros included.
    let dayCounts: [StatsCount]
    /// Top 10 neighborhoods, most sets first.
    let neighborhoodCounts: [StatsCount]
    /// Top 5 mics by visit count.
    let topMics: [StatsCount]
    /// Every tag, most used first.
    let tagCounts: [StatsCount]
    /// Month × tag counts, oldest month first.
    let tagsOverTime: [StatsTagMonth]

    // MARK: Cards 9–10 — mic table and insight

    /// Sorted by visits (most first).
    let micRows: [StatsMicRow]
    /// Mics rated ≥ 7 that you last visited more than 30 days ago.
    let neglectedFavorites: [String]

    // MARK: - Building

    /// - Parameters:
    ///   - sets: every `my_sets` row (planned rows are ignored here).
    ///   - micsByID: every mic, including inactive ones.
    ///   - activeMicCount: the number of active mics.
    ///   - today: the device's local date.
    init(sets: [SetEntry], micsByID: [Int: Mic], activeMicCount: Int, today: CalendarDate) {
        let logged = sets.filter { $0.isLogged }
        let ratings = logged.compactMap { $0.datedRating }
        let points = StatsSnapshot.ratingPoints(logged, micsByID: micsByID)
        let rows = StatsSnapshot.micRows(logged, micsByID: micsByID)

        self.totalSets = logged.count
        self.averageRating = SetRules.average(ratings.map { $0.rating })
        self.ratingDelta = SetRules.monthOverMonthDelta(ratings, today: today)
        self.weekStreak = SetRules.weekStreak(loggedDates: logged.map { $0.datePerformed }, today: today)
        self.venuesVisited = StatsSnapshot.distinctVenueCount(logged, micsByID: micsByID)
        self.activeMicCount = activeMicCount

        self.ratingPoints = points
        self.trend = StatsSnapshot.trendSegment(ratings)
        self.venueCount = Set(points.map { $0.venue }).count

        self.dayCounts = StatsSnapshot.dayCounts(logged, micsByID: micsByID)
        self.neighborhoodCounts = StatsSnapshot.neighborhoodCounts(logged, micsByID: micsByID)
        self.topMics = StatsSnapshot.topMics(logged, micsByID: micsByID)
        self.tagCounts = StatsSnapshot.tagCounts(logged)
        self.tagsOverTime = StatsSnapshot.tagsOverTime(logged)

        self.micRows = rows
        self.neglectedFavorites = StatsSnapshot.neglectedFavorites(rows, today: today)
    }

    // MARK: - Display text

    /// True before the first logged set (shows the empty state).
    var isEmpty: Bool { totalSets == 0 }

    /// "7.4/10"
    var averageText: String { String(format: "%.1f/10", averageRating) }

    /// "+0.4 vs last month" (nil when hidden).
    var deltaText: String? {
        guard let delta = ratingDelta else { return nil }
        return SetRules.deltaText(delta)
    }

    /// "4 / 57"
    var venuesText: String { "\(venuesVisited) / \(activeMicCount)" }

    /// The insight card's text, or nil when there's nothing to say.
    var insightText: String? {
        guard !neglectedFavorites.isEmpty else { return nil }
        return "💡 Mics you rated highly but haven't visited in 30+ days: " + neglectedFavorites.joined(separator: ", ")
    }

    // MARK: - Pieces (static so each can be tested on its own)

    static func mic(for entry: SetEntry, in micsByID: [Int: Mic]) -> Mic? {
        guard let id = entry.openMicId else { return nil }
        return micsByID[id]
    }

    /// Distinct non-blank venue names of the mics with logged sets.
    static func distinctVenueCount(_ logged: [SetEntry], micsByID: [Int: Mic]) -> Int {
        let venues = logged.compactMap { entry -> String? in
            TextRules.trimmedOrNil(StatsSnapshot.mic(for: entry, in: micsByID)?.venue)
        }
        return Set(venues).count
    }

    /// Plot points, oldest first (ties by set id).
    static func ratingPoints(_ logged: [SetEntry], micsByID: [Int: Mic]) -> [StatsRatingPoint] {
        let ordered = logged.sorted { lhs, rhs in
            lhs.datePerformed != rhs.datePerformed ? lhs.datePerformed < rhs.datePerformed : lhs.id < rhs.id
        }
        return ordered.compactMap { entry -> StatsRatingPoint? in
            guard let rating = entry.setRating else { return nil }
            let owner = StatsSnapshot.mic(for: entry, in: micsByID)
            return StatsRatingPoint(
                id: entry.id,
                date: entry.datePerformed,
                setRating: Double(rating),
                crowdRating: entry.crowdRating.map { Double($0) },
                crowdWeight: CrowdSize.chartWeight(forRaw: entry.crowdSize),
                venue: TextRules.trimmedOrNil(owner?.venue) ?? "Unknown venue",
                micName: owner?.name ?? "Unknown mic"
            )
        }
    }

    /// Rule 13's line between the first and last set dates, with both ends
    /// kept inside the chart's 0–10 range.
    static func trendSegment(_ ratings: [DatedRating]) -> StatsTrendSegment? {
        guard let line = SetRules.olsTrend(ratings),
              let first = ratings.map({ $0.date }).min(),
              let last = ratings.map({ $0.date }).max()
        else { return nil }
        return StatsTrendSegment(
            start: first,
            startValue: clampRating(line.value(at: first)),
            end: last,
            endValue: clampRating(line.value(at: last))
        )
    }

    static func clampRating(_ value: Double) -> Double {
        min(10, max(0, value))
    }

    /// Mon…Sun by the mic's `day_of_week` (zeros included). Sets whose mic
    /// is missing or has an unreadable day aren't counted.
    static func dayCounts(_ logged: [SetEntry], micsByID: [Int: Mic]) -> [StatsCount] {
        var counts: [Weekday: Int] = [:]
        for entry in logged {
            if let day = StatsSnapshot.mic(for: entry, in: micsByID)?.weekday {
                counts[day, default: 0] += 1
            }
        }
        return Weekday.allCases.map { StatsCount(label: $0.shortName, count: counts[$0] ?? 0) }
    }

    /// Top 10 non-blank neighborhoods.
    static func neighborhoodCounts(_ logged: [SetEntry], micsByID: [Int: Mic]) -> [StatsCount] {
        var counts: [String: Int] = [:]
        for entry in logged {
            if let neighborhood = TextRules.trimmedOrNil(StatsSnapshot.mic(for: entry, in: micsByID)?.neighborhood) {
                counts[neighborhood, default: 0] += 1
            }
        }
        return ranked(counts, limit: 10)
    }

    /// Top 5 mics by visit count — rule 6's logged-visit count per mic, so
    /// it matches Mic Detail's "Been here N×". Ties: name A→Z, then id.
    static func topMics(_ logged: [SetEntry], micsByID: [Int: Mic]) -> [StatsCount] {
        var visited: [Mic] = []
        let counts = SetRules.loggedVisitCounts(logged)
        for micID in counts.keys {
            if let mic = micsByID[micID] {
                visited.append(mic)
            }
        }
        visited.sort { lhs, rhs in
            let left = counts[lhs.id] ?? 0
            let right = counts[rhs.id] ?? 0
            if left != right { return left > right }
            if lhs.name.lowercased() != rhs.name.lowercased() { return lhs.name.lowercased() < rhs.name.lowercased() }
            return lhs.id < rhs.id
        }
        let top = Array(visited.prefix(5))
        let labels = uniqueMicLabels(top)
        return top.indices.map { index in
            StatsCount(label: labels[index], count: counts[top[index].id] ?? 0)
        }
    }

    /// Chart labels for mics, made unique (each bar needs its own label):
    /// "Open Mic", or "Open Mic @ The Creek" when two mics share a name,
    /// plus " (#id)" if even that is ambiguous.
    static func uniqueMicLabels(_ mics: [Mic]) -> [String] {
        var nameCounts: [String: Int] = [:]
        for mic in mics {
            nameCounts[mic.name, default: 0] += 1
        }
        var labels = mics.map { mic -> String in
            let isShared = (nameCounts[mic.name] ?? 0) > 1
            return isShared && !mic.venue.isEmpty ? "\(mic.name) @ \(mic.venue)" : mic.name
        }
        var labelCounts: [String: Int] = [:]
        for label in labels {
            labelCounts[label, default: 0] += 1
        }
        for index in labels.indices where (labelCounts[labels[index]] ?? 0) > 1 {
            labels[index] += " (#\(mics[index].id))"
        }
        return labels
    }

    /// Every tag (rule 14 parsing), most used first.
    static func tagCounts(_ logged: [SetEntry]) -> [StatsCount] {
        var counts: [String: Int] = [:]
        for entry in logged {
            for tag in entry.tagList {
                counts[tag, default: 0] += 1
            }
        }
        return ranked(counts, limit: nil)
    }

    /// Month ("yyyy-MM") × tag counts, oldest month first, tags A→Z.
    static func tagsOverTime(_ logged: [SetEntry]) -> [StatsTagMonth] {
        var counts: [String: [String: Int]] = [:]
        for entry in logged {
            let month = entry.datePerformed.monthKey
            for tag in entry.tagList {
                counts[month, default: [:]][tag, default: 0] += 1
            }
        }
        var result: [StatsTagMonth] = []
        for month in counts.keys.sorted() {
            let tags = counts[month] ?? [:]
            for tag in tags.keys.sorted() {
                result.append(StatsTagMonth(month: month, tag: tag, count: tags[tag] ?? 0))
            }
        }
        return result
    }

    /// The biggest monthly total in `tagsOverTime` (for the y-axis).
    static func largestMonthTotal(_ items: [StatsTagMonth]) -> Int {
        var totals: [String: Int] = [:]
        for item in items {
            totals[item.month, default: 0] += item.count
        }
        return totals.values.max() ?? 0
    }

    /// One row per mic you've logged a set at.
    static func micRows(_ logged: [SetEntry], micsByID: [Int: Mic]) -> [StatsMicRow] {
        var grouped: [Int: [SetEntry]] = [:]
        for entry in logged {
            if let id = entry.openMicId, micsByID[id] != nil {
                grouped[id, default: []].append(entry)
            }
        }

        let rows = grouped.compactMap { group -> StatsMicRow? in
            let micID = group.key
            let entries = group.value
            guard let mic = micsByID[micID],
                  let lastVisit = entries.map({ $0.datePerformed }).max()
            else { return nil }
            let setRatings = entries.compactMap { $0.setRating }.map { Double($0) }
            let crowdRatings = entries.compactMap { $0.crowdRating }.map { Double($0) }
            return StatsMicRow(
                id: micID,
                name: mic.name,
                venue: mic.venue,
                myRating: mic.micRating,
                visits: entries.count,
                averageSet: SetRules.average(setRatings),
                averageCrowd: crowdRatings.isEmpty ? nil : SetRules.average(crowdRatings),
                lastVisit: lastVisit
            )
        }

        // Most visits first; then most recent; then by name so the order is stable.
        return rows.sorted { lhs, rhs in
            if lhs.visits != rhs.visits { return lhs.visits > rhs.visits }
            if lhs.lastVisit != rhs.lastVisit { return lhs.lastVisit > rhs.lastVisit }
            if lhs.name.lowercased() != rhs.name.lowercased() { return lhs.name.lowercased() < rhs.name.lowercased() }
            return lhs.id < rhs.id
        }
    }

    /// Mic rating ≥ 7 and the last visit more than 30 days before today.
    static func neglectedFavorites(_ rows: [StatsMicRow], today: CalendarDate) -> [String] {
        rows
            .filter { row in
                guard let rating = row.myRating, rating >= 7 else { return false }
                return row.lastVisit.days(until: today) > 30
            }
            .map { $0.name }
    }

    /// Counts → bars, most first, ties A→Z, optionally cut to `limit`.
    static func ranked(_ counts: [String: Int], limit: Int?) -> [StatsCount] {
        let sorted = counts
            .map { StatsCount(label: $0.key, count: $0.value) }
            .sorted { lhs, rhs in
                lhs.count != rhs.count ? lhs.count > rhs.count : lhs.label.lowercased() < rhs.label.lowercased()
            }
        guard let limit = limit else { return sorted }
        return Array(sorted.prefix(limit))
    }

    /// A whole-number tick step for a count axis, so small charts don't
    /// show "0.5 sets": 1 up to 5, then roughly five ticks.
    static func countAxisStep(maxCount: Int) -> Double {
        guard maxCount > 5 else { return 1 }
        return Double((maxCount + 4) / 5)
    }
}
