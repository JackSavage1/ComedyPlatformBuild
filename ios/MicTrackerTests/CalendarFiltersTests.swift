import XCTest
@testable import MicTracker

// Tests for the Weekly Calendar's week model and filters (spec 6.3).
// 2026-10-20 is a Tuesday.
final class CalendarFiltersTests: XCTestCase {

    private let tuesday = CalendarDate(year: 2026, month: 10, day: 20)

    private var sampleMics: [Mic] {
        [
            Mic(id: 1, name: "Free Manhattan", neighborhood: "East Village", borough: "Manhattan",
                dayOfWeek: "Monday", startTime: "19:00", cost: "Free", signupMethod: "in_person"),
            Mic(id: 2, name: "Pricey Brooklyn", neighborhood: "Bushwick", borough: "Brooklyn",
                dayOfWeek: "Tuesday", startTime: "20:00", cost: "$10", signupMethod: "online"),
            Mic(id: 3, name: "Cheap Brooklyn", neighborhood: "Williamsburg", borough: "brooklyn",
                dayOfWeek: "Tuesday", startTime: "18:00", cost: "$5", signupMethod: "online"),
            Mic(id: 4, name: "Drink Queens", neighborhood: "Astoria", borough: "Queens",
                dayOfWeek: "Friday", startTime: "21:00", cost: "1 drink min", signupMethod: "email"),
        ]
    }

    private func ids(_ mics: [Mic]) -> [Int] { mics.map { $0.id } }

    // MARK: - CalendarWeek

    func testThisWeekRunsMondayToSundayAndSelectsToday() {
        let week = CalendarWeek(offset: 0, today: tuesday)
        XCTAssertEqual(week.monday, CalendarDate(year: 2026, month: 10, day: 19))
        XCTAssertEqual(week.sunday, CalendarDate(year: 2026, month: 10, day: 25))
        XCTAssertEqual(week.dates.count, 7)
        XCTAssertEqual(week.title, "This Week")
        XCTAssertEqual(week.rangeText, "Oct 19 – Oct 25, 2026")
        XCTAssertEqual(week.selection(preferring: nil), tuesday)
        XCTAssertTrue(week.isPast(week.monday))
        XCTAssertFalse(week.isPast(tuesday))
    }

    func testOtherWeeksSelectMondayAndKeepAValidPick() {
        let next = CalendarWeek(offset: 1, today: tuesday)
        XCTAssertEqual(next.title, "Next Week")
        XCTAssertEqual(next.selection(preferring: nil), CalendarDate(year: 2026, month: 10, day: 26))
        // A pick from another week falls back to the default…
        XCTAssertEqual(next.selection(preferring: tuesday), next.monday)
        // …a pick inside the week is kept.
        let friday = CalendarDate(year: 2026, month: 10, day: 30)
        XCTAssertEqual(next.selection(preferring: friday), friday)
    }

    func testWeekOffsetIsClampedToTheAllowedRange() {
        XCTAssertEqual(CalendarWeek(offset: -5, today: tuesday).offset, -1)
        XCTAssertEqual(CalendarWeek(offset: 9, today: tuesday).offset, 3)
        XCTAssertFalse(CalendarWeek(offset: -1, today: tuesday).canGoBack)
        XCTAssertFalse(CalendarWeek(offset: 3, today: tuesday).canGoForward)
        XCTAssertTrue(CalendarWeek(offset: 0, today: tuesday).canGoBack)
        XCTAssertEqual(CalendarWeek(offset: 2, today: tuesday).title, "2 Weeks Out")
        XCTAssertEqual(CalendarWeek(offset: -1, today: tuesday).title, "Last Week")
    }

    func testMicCountsAndDayListsFollowWeekdays() {
        let week = CalendarWeek(offset: 0, today: tuesday)
        let counts = week.micCounts(sampleMics)
        XCTAssertEqual(counts[week.monday], 1)
        XCTAssertEqual(counts[tuesday], 2)
        XCTAssertEqual(counts[week.sunday], 0)
        // Sorted by start time: 18:00 before 20:00.
        XCTAssertEqual(ids(CalendarWeek.mics(sampleMics, on: tuesday)), [3, 2])
    }

    // MARK: - CalendarFilters

    func testNoFiltersMatchEverything() {
        let filters = CalendarFilters()
        XCTAssertEqual(filters.activeCount, 0)
        XCTAssertFalse(filters.isActive)
        XCTAssertEqual(ids(filters.apply(to: sampleMics, visitedMicIDs: [], goingMicIDs: [])), [1, 2, 3, 4])
    }

    func testBoroughFilterIsCaseInsensitive() {
        var filters = CalendarFilters()
        filters.boroughs = ["Brooklyn"]
        XCTAssertEqual(ids(filters.apply(to: sampleMics, visitedMicIDs: [], goingMicIDs: [])), [2, 3])
        XCTAssertEqual(filters.activeCount, 1)
    }

    func testNeighborhoodFilter() {
        var filters = CalendarFilters()
        filters.neighborhoods = ["Astoria", "Bushwick"]
        XCTAssertEqual(ids(filters.apply(to: sampleMics, visitedMicIDs: [], goingMicIDs: [])), [2, 4])
    }

    func testCostFilterUnderSixExcludesTenDollars() {
        var filters = CalendarFilters()
        filters.cost = .underSix
        XCTAssertEqual(ids(filters.apply(to: sampleMics, visitedMicIDs: [], goingMicIDs: [])), [1, 3])
        filters.cost = .drinkMinOnly
        XCTAssertEqual(ids(filters.apply(to: sampleMics, visitedMicIDs: [], goingMicIDs: [])), [4])
    }

    func testSignupFilter() {
        var filters = CalendarFilters()
        filters.signup = .online
        XCTAssertEqual(ids(filters.apply(to: sampleMics, visitedMicIDs: [], goingMicIDs: [])), [2, 3])
    }

    func testUnvisitedAndGoingToggles() {
        var filters = CalendarFilters()
        filters.onlyUnvisited = true
        XCTAssertEqual(ids(filters.apply(to: sampleMics, visitedMicIDs: [1, 4], goingMicIDs: [])), [2, 3])

        filters = CalendarFilters()
        filters.onlyGoing = true
        XCTAssertEqual(ids(filters.apply(to: sampleMics, visitedMicIDs: [], goingMicIDs: [3])), [3])
        // Nobody marked Going → nothing to show.
        XCTAssertEqual(filters.apply(to: sampleMics, visitedMicIDs: [], goingMicIDs: []).count, 0)
    }

    func testActiveCountAndReset() {
        var filters = CalendarFilters()
        filters.boroughs = ["Queens"]
        filters.neighborhoods = ["Astoria"]
        filters.cost = .freeOnly
        filters.signup = .email
        filters.onlyUnvisited = true
        filters.onlyGoing = true
        XCTAssertEqual(filters.activeCount, 6)
        filters.reset()
        XCTAssertEqual(filters, CalendarFilters())
    }

    func testNeighborhoodOptionsFollowSelectedBoroughs() {
        XCTAssertEqual(
            CalendarFilters.neighborhoodOptions(from: sampleMics, boroughs: []),
            ["Astoria", "Bushwick", "East Village", "Williamsburg"]
        )
        XCTAssertEqual(
            CalendarFilters.neighborhoodOptions(from: sampleMics, boroughs: ["Brooklyn"]),
            ["Bushwick", "Williamsburg"]
        )
    }

    func testTogglingABoroughDropsNeighborhoodsNoLongerOffered() {
        var filters = CalendarFilters()
        filters.neighborhoods = ["Astoria", "Bushwick"]
        filters.toggleBorough("Brooklyn", availableMics: sampleMics)
        XCTAssertEqual(filters.boroughs, ["Brooklyn"])
        XCTAssertEqual(filters.neighborhoods, ["Bushwick"])
        filters.toggleBorough("Brooklyn", availableMics: sampleMics)
        XCTAssertTrue(filters.boroughs.isEmpty)
        XCTAssertEqual(filters.neighborhoods, ["Bushwick"])
    }

    func testVisitedIDsComeFromLoggedCounts() {
        XCTAssertEqual(CalendarFilters.visitedMicIDs(from: [1: 2, 2: 0, 3: 1]), [1, 3])
    }
}
