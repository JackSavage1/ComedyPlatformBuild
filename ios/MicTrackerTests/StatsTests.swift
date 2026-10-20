import XCTest
@testable import MicTracker

// =============================================================================
// Tests for the Stats tab's numbers (Features/Stats/StatsSnapshot.swift,
// spec 6.7). 2026-10-20 is a Tuesday.
// =============================================================================
final class StatsTests: XCTestCase {

    // MARK: - Fixtures

    private func day(_ year: Int, _ month: Int, _ day: Int) -> CalendarDate {
        CalendarDate(year: year, month: month, day: day)
    }

    private let today = CalendarDate(year: 2026, month: 10, day: 20)

    private var mics: [Int: Mic] {
        let list = [
            Mic(id: 1, name: "Creek Mic", venue: "The Creek", neighborhood: "LIC", borough: "Queens", dayOfWeek: "Monday", startTime: "19:00", micRating: 8),
            Mic(id: 2, name: "Gutter Mic", venue: "The Gutter", neighborhood: "Williamsburg", borough: "Brooklyn", dayOfWeek: "Wednesday", startTime: "20:30", micRating: 6),
            Mic(id: 3, name: "Old Favorite", venue: "The Creek", neighborhood: " ", dayOfWeek: "Friday", startTime: "18:00", micRating: 9, isActive: false),
        ]
        return Dictionary(uniqueKeysWithValues: list.map { ($0.id, $0) })
    }

    private var sets: [SetEntry] {
        [
            SetEntry(id: 1, openMicId: 1, datePerformed: day(2026, 10, 19), setRating: 8, crowdRating: 6, crowdSize: "packed", tags: "killed,crowd_work"),
            SetEntry(id: 2, openMicId: 1, datePerformed: day(2026, 10, 5), setRating: 6, crowdRating: nil, crowdSize: nil, tags: "nervous"),
            SetEntry(id: 3, openMicId: 2, datePerformed: day(2026, 9, 16), setRating: 4, crowdRating: 3, crowdSize: "sparse", tags: "bombed,nervous"),
            SetEntry(id: 4, openMicId: 3, datePerformed: day(2026, 8, 7), setRating: 9, crowdRating: 9, crowdSize: "decent"),
            // Planned rows are ignored by every stat.
            SetEntry(id: 5, openMicId: 2, datePerformed: day(2026, 10, 21)),
        ]
    }

    private func snapshot() -> StatsSnapshot {
        StatsSnapshot(sets: sets, micsByID: mics, activeMicCount: 2, today: today)
    }

    // MARK: - Card 1

    func testMetrics() {
        let stats = snapshot()
        XCTAssertFalse(stats.isEmpty)
        XCTAssertEqual(stats.totalSets, 4)
        XCTAssertEqual(stats.averageText, "6.8/10") // (8 + 6 + 4 + 9) / 4 = 6.75
        // October (8, 6) avg 7 − September (4) = +3.0
        XCTAssertEqual(stats.ratingDelta, 3.0)
        XCTAssertEqual(stats.deltaText, "+3.0 vs last month")
        // This week (10/19) only — last week (10/12) is empty.
        XCTAssertEqual(stats.weekStreak, 1)
        // "The Creek" (two mics) + "The Gutter".
        XCTAssertEqual(stats.venuesVisited, 2)
        XCTAssertEqual(stats.venuesText, "2 / 2")
    }

    func testEmptySnapshot() {
        let stats = StatsSnapshot(sets: [SetEntry(id: 1, openMicId: 1, datePerformed: today)], micsByID: mics, activeMicCount: 2, today: today)
        XCTAssertTrue(stats.isEmpty)
        XCTAssertNil(stats.trend)
        XCTAssertNil(stats.deltaText)
        XCTAssertNil(stats.insightText)
        XCTAssertTrue(stats.micRows.isEmpty)
    }

    // MARK: - Cards 2–3

    func testRatingPointsAreOldestFirstWithCrowdWeights() {
        let points = snapshot().ratingPoints
        XCTAssertEqual(points.map { $0.id }, [4, 3, 2, 1])
        XCTAssertEqual(points.first?.venue, "The Creek")
        XCTAssertEqual(points.last?.crowdWeight, 50)       // packed
        XCTAssertEqual(points[2].crowdWeight, 20)           // unknown
        XCTAssertNil(points[2].crowdRating)
        XCTAssertEqual(snapshot().venueCount, 2)
    }

    func testTrendSegmentSpansFirstToLastDate() {
        guard let trend = snapshot().trend else {
            return XCTFail("expected a trendline")
        }
        XCTAssertEqual(trend.start, day(2026, 8, 7))
        XCTAssertEqual(trend.end, day(2026, 10, 19))
        XCTAssertTrue((0...10).contains(trend.startValue))
        XCTAssertTrue((0...10).contains(trend.endValue))
    }

    func testNoTrendWithOneDate() {
        let sameDay = [
            SetEntry(id: 1, openMicId: 1, datePerformed: today, setRating: 3),
            SetEntry(id: 2, openMicId: 1, datePerformed: today, setRating: 9),
        ]
        XCTAssertNil(StatsSnapshot(sets: sameDay, micsByID: mics, activeMicCount: 2, today: today).trend)
    }

    func testTrendIsClampedToChartRange() {
        XCTAssertEqual(StatsSnapshot.clampRating(-2), 0)
        XCTAssertEqual(StatsSnapshot.clampRating(12.5), 10)
        XCTAssertEqual(StatsSnapshot.clampRating(6.5), 6.5)
    }

    // MARK: - Cards 4–8

    func testDayCountsIncludeZeros() {
        let counts = snapshot().dayCounts
        XCTAssertEqual(counts.map { $0.label }, ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"])
        XCTAssertEqual(counts.map { $0.count }, [2, 0, 1, 0, 1, 0, 0])
    }

    func testNeighborhoodAndTopMics() {
        let stats = snapshot()
        XCTAssertEqual(stats.neighborhoodCounts, [StatsCount(label: "LIC", count: 2), StatsCount(label: "Williamsburg", count: 1)])
        XCTAssertEqual(stats.topMics.first, StatsCount(label: "Creek Mic", count: 2))
        XCTAssertEqual(stats.topMics.count, 3)
    }

    func testTagCountsAndTagsOverTime() {
        let stats = snapshot()
        XCTAssertEqual(stats.tagCounts.first, StatsCount(label: "nervous", count: 2))
        XCTAssertEqual(stats.tagCounts.count, 4)
        XCTAssertEqual(stats.tagsOverTime.map { $0.id }, [
            "2026-09|bombed", "2026-09|nervous",
            "2026-10|crowd_work", "2026-10|killed", "2026-10|nervous",
        ])
        XCTAssertEqual(StatsSnapshot.largestMonthTotal(stats.tagsOverTime), 3)
    }

    func testTopMicsCountsPerMicWithUniqueLabels() {
        let twins = [
            1: Mic(id: 1, name: "Open Mic", venue: "The Creek"),
            2: Mic(id: 2, name: "Open Mic", venue: "The Gutter"),
            3: Mic(id: 3, name: "Open Mic", venue: "The Gutter"),
        ]
        let logged = [
            SetEntry(id: 1, openMicId: 1, datePerformed: today, setRating: 5),
            SetEntry(id: 2, openMicId: 1, datePerformed: today, setRating: 5),
            SetEntry(id: 3, openMicId: 2, datePerformed: today, setRating: 5),
            SetEntry(id: 4, openMicId: 3, datePerformed: today, setRating: 5),
            SetEntry(id: 5, openMicId: 3, datePerformed: today), // planned: not a visit
        ]
        let top = StatsSnapshot.topMics(logged, micsByID: twins)
        XCTAssertEqual(top.map { $0.label }, ["Open Mic @ The Creek", "Open Mic @ The Gutter (#2)", "Open Mic @ The Gutter (#3)"])
        XCTAssertEqual(top.map { $0.count }, [2, 1, 1])
        XCTAssertEqual(StatsSnapshot.uniqueMicLabels([Mic(id: 9, name: "Solo", venue: "X")]), ["Solo"])
    }

    func testRankedTieBreaksAlphabeticallyAndLimits() {
        let ranked = StatsSnapshot.ranked(["b": 2, "a": 2, "c": 5, "d": 1], limit: 3)
        XCTAssertEqual(ranked.map { $0.label }, ["c", "a", "b"])
    }

    func testCountAxisStep() {
        XCTAssertEqual(StatsSnapshot.countAxisStep(maxCount: 0), 1)
        XCTAssertEqual(StatsSnapshot.countAxisStep(maxCount: 5), 1)
        XCTAssertEqual(StatsSnapshot.countAxisStep(maxCount: 6), 2)
        XCTAssertEqual(StatsSnapshot.countAxisStep(maxCount: 23), 5)
    }

    // MARK: - Cards 9–10

    func testMicRowsSortedByVisits() {
        let rows = snapshot().micRows
        XCTAssertEqual(rows.map { $0.id }, [1, 2, 3])
        let creek = rows[0]
        XCTAssertEqual(creek.visits, 2)
        XCTAssertEqual(creek.averageSetText, "7.0")
        XCTAssertEqual(creek.averageCrowdText, "6.0") // the visit without a crowd rating is skipped
        XCTAssertEqual(creek.lastVisitText, "10/19/2026")
        XCTAssertEqual(creek.myRatingText, "8.0")
    }

    func testInsightListsHighlyRatedMicsNotVisitedFor30Days() {
        let stats = snapshot()
        // Old Favorite: rated 9, last visit Aug 7. Creek: rated 8 but visited yesterday.
        // Gutter: last visit Sep 16 (34 days) but only rated 6.
        XCTAssertEqual(stats.neglectedFavorites, ["Old Favorite"])
        XCTAssertEqual(stats.insightText, "💡 Mics you rated highly but haven't visited in 30+ days: Old Favorite")
    }

    func testExactly30DaysIsNotNeglected() {
        let row = StatsMicRow(id: 1, name: "A", venue: "", myRating: 7, visits: 1, averageSet: 5, averageCrowd: nil, lastVisit: today.adding(days: -30))
        XCTAssertTrue(StatsSnapshot.neglectedFavorites([row], today: today).isEmpty)
        let older = StatsMicRow(id: 2, name: "B", venue: "", myRating: 7, visits: 1, averageSet: 5, averageCrowd: nil, lastVisit: today.adding(days: -31))
        XCTAssertEqual(StatsSnapshot.neglectedFavorites([older], today: today), ["B"])
    }
}
