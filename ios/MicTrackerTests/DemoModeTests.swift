import XCTest
@testable import MicTracker

// =============================================================================
// Demo mode (spec 8): the date rules that make the bundled data look
// current, the in-memory backend's RPC semantics, and the launch options CI
// uses for screenshots.
//
// "Today" in these tests is Tuesday 2026-10-20, so:
//   most recent Monday before today = 10/19, Friday = 10/16
//   next Monday on/after today      = 10/26, Friday = 10/23
// =============================================================================
final class DemoModeTests: XCTestCase {

    // MARK: - Helpers

    private let today = CalendarDate(year: 2026, month: 10, day: 20) // Tuesday

    private func day(_ year: Int, _ month: Int, _ day: Int) -> CalendarDate {
        CalendarDate(year: year, month: month, day: day)
    }

    private func newYorkCalendar() throws -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        return calendar
    }

    /// A small DemoData.json-shaped fixture (relative dates, helper keys).
    private let fixtureJSON = """
    {
      "_comment": "test fixture",
      "mics": [
        {"id": 1, "name": "Monday Mic", "venue": "Bar One", "day_of_week": "Monday",
         "start_time": "19:00", "cost": "Free", "is_active": true, "mic_rating": 6.0},
        {"id": 2, "name": "Friday Mic", "venue": "Club Two", "day_of_week": "Friday",
         "start_time": "21:00", "is_active": true, "latitude": 40.72, "longitude": -73.98},
        {"id": 7, "name": "Old Mic", "venue": "Gone", "day_of_week": "Sunday",
         "start_time": "18:00", "is_active": false}
      ],
      "sets": [
        {"id": 1, "open_mic_id": 1, "weeks_ago": 2, "set_rating": 7, "crowd_rating": 6, "tags": "killed"},
        {"id": 2, "open_mic_id": 1, "weeks_ago": 0, "set_rating": 9, "would_return": true},
        {"id": 3, "open_mic_id": 2, "weeks_ago": 0, "would_return": true}
      ],
      "plans": [
        {"id": 1, "open_mic_id": 2, "status": "going", "when": "past", "weeks_ago": 0},
        {"id": 2, "open_mic_id": 1, "status": "going", "when": "upcoming", "weeks_ahead": 0},
        {"id": 3, "open_mic_id": 2, "status": "cancelled", "when": "upcoming", "weeks_ahead": 1}
      ],
      "scraped_mics": [
        {"id": 101, "source": "badslava", "name": "Candy Mic", "venue": "Candy Store",
         "day_of_week": "Tuesday", "start_time": "19:30", "cost": "Free", "signup_url": null,
         "status": "pending",
         "payload": {"name": "Candy Mic", "venue": "Candy Store", "neighborhood": "Williamsburg",
                     "borough": "Brooklyn", "day_of_week": "Tuesday", "start_time": "19:30",
                     "display_time": "7:30 PM", "set_length_min": "30.0", "is_biweekly": "yes",
                     "notes": "   ", "source_url": "not-a-column"}},
        {"id": 102, "source": "firemics", "name": "QED Mic", "venue": "QED",
         "day_of_week": "Thursday", "start_time": "18:00", "status": "pending", "payload": {}}
      ],
      "scrape_log": [
        {"id": 1, "source": "badslava", "days_ago": 0, "hour": 14, "minute": 7, "status": "success"},
        {"id": 2, "source": "firemics", "days_ago": 1, "hour": 14, "minute": 8, "status": "error"}
      ]
    }
    """

    private func fixtureTables() throws -> DemoTables {
        try DemoSeed.resolve(json: Data(fixtureJSON.utf8), today: today, calendar: newYorkCalendar())
    }

    @MainActor
    private func fixtureBackend() throws -> DemoBackend {
        DemoBackend(tables: try fixtureTables())
    }

    /// The real DemoData.json: from the app bundle when the tests run inside
    /// the app (Xcode), else from the source tree (Linux / no test host).
    private func bundledDemoJSON() throws -> Data {
        if let url = Bundle.main.url(forResource: "DemoData", withExtension: "json") {
            return try Data(contentsOf: url)
        }
        let testFile = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath()
        let url = testFile.deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("MicTracker/Resources/DemoData.json")
        return try Data(contentsOf: url)
    }

    // MARK: - DemoDateRules: past dates

    func testPastDateIsMostRecentWeekdayStrictlyBeforeToday() {
        XCTAssertEqual(DemoDateRules.pastDate(on: .monday, weeksAgo: 0, today: today), day(2026, 10, 19))
        XCTAssertEqual(DemoDateRules.pastDate(on: .sunday, weeksAgo: 0, today: today), day(2026, 10, 18))
        XCTAssertEqual(DemoDateRules.pastDate(on: .wednesday, weeksAgo: 0, today: today), day(2026, 10, 14))
        // Same weekday as today → a week ago, never today.
        XCTAssertEqual(DemoDateRules.pastDate(on: .tuesday, weeksAgo: 0, today: today), day(2026, 10, 13))
    }

    func testPastDateSubtractsWholeWeeks() {
        XCTAssertEqual(DemoDateRules.pastDate(on: .monday, weeksAgo: 2, today: today), day(2026, 10, 5))
        XCTAssertEqual(DemoDateRules.pastDate(on: .friday, weeksAgo: 9, today: today), day(2026, 8, 14))
    }

    func testPastDateAcrossYearBoundary() {
        // Friday 2027-01-01: the previous Thursday is 2026-12-31.
        let newYear = day(2027, 1, 1)
        XCTAssertEqual(DemoDateRules.pastDate(on: .thursday, weeksAgo: 0, today: newYear), day(2026, 12, 31))
        XCTAssertEqual(DemoDateRules.pastDate(on: .thursday, weeksAgo: 1, today: newYear), day(2026, 12, 24))
        XCTAssertEqual(DemoDateRules.pastDate(on: .friday, weeksAgo: 0, today: newYear), day(2026, 12, 25))
    }

    func testPastDateInvariantsForEveryWeekdayAndToday() {
        for offset in 0..<14 {
            let someDay = today.adding(days: offset)
            for weekday in Weekday.allCases {
                let date = DemoDateRules.pastDate(on: weekday, weeksAgo: 0, today: someDay)
                XCTAssertEqual(date.weekday, weekday)
                XCTAssertLessThan(date, someDay)
                XCTAssertLessThanOrEqual(date.days(until: someDay), 7)
            }
        }
    }

    // MARK: - DemoDateRules: upcoming dates

    func testUpcomingDateIsNextWeekdayOnOrAfterToday() {
        // Same weekday → today (rule 16).
        XCTAssertEqual(DemoDateRules.upcomingDate(on: .tuesday, weeksAhead: 0, today: today), today)
        XCTAssertEqual(DemoDateRules.upcomingDate(on: .monday, weeksAhead: 0, today: today), day(2026, 10, 26))
        XCTAssertEqual(DemoDateRules.upcomingDate(on: .friday, weeksAhead: 0, today: today), day(2026, 10, 23))
        XCTAssertEqual(DemoDateRules.upcomingDate(on: .tuesday, weeksAhead: 1, today: today), day(2026, 10, 27))
        XCTAssertEqual(DemoDateRules.upcomingDate(on: .saturday, weeksAhead: 1, today: today), day(2026, 10, 31))
    }

    func testUpcomingDateInvariants() {
        for offset in 0..<14 {
            let someDay = today.adding(days: offset)
            for weekday in Weekday.allCases {
                let date = DemoDateRules.upcomingDate(on: weekday, weeksAhead: 0, today: someDay)
                XCTAssertEqual(date.weekday, weekday)
                XCTAssertGreaterThanOrEqual(date, someDay)
                XCTAssertLessThan(someDay.days(until: date), 7)
            }
        }
    }

    // MARK: - DemoDateRules: scrape times

    func testScrapeTimeIsLocalWallClockTime() throws {
        let calendar = try newYorkCalendar()
        // October: New York is UTC−4.
        let yesterday = DemoDateRules.scrapeTime(daysAgo: 1, hour: 14, minute: 7, today: today, calendar: calendar)
        XCTAssertEqual(DemoSeed.utcTimestamp(yesterday), "2026-10-19T18:07:00Z")
        // After the clocks go back (Nov 1, 2026) it's UTC−5.
        let november = DemoDateRules.scrapeTime(daysAgo: 0, hour: 14, minute: 7, today: day(2026, 11, 2), calendar: calendar)
        XCTAssertEqual(DemoSeed.utcTimestamp(november), "2026-11-02T19:07:00Z")
    }

    func testScrapeTimeRoundTripsThroughTimestampParser() throws {
        let calendar = try newYorkCalendar()
        let time = DemoDateRules.scrapeTime(daysAgo: 0, hour: 9, minute: 30, today: today, calendar: calendar)
        XCTAssertEqual(TimestampParser.parse(DemoSeed.utcTimestamp(time)), time)
        XCTAssertEqual(CalendarDate(date: time, calendar: calendar), today)
    }

    // MARK: - DemoSeed

    func testSeedResolvesSetDates() throws {
        let tables = try fixtureTables()
        let dates = Dictionary(uniqueKeysWithValues: tables.sets.map {
            (DemoValue.int($0["id"]) ?? 0, DemoValue.string($0["date_performed"]) ?? "")
        })
        XCTAssertEqual(dates[1], "2026-10-05") // Monday mic, 2 weeks before 10/19
        XCTAssertEqual(dates[2], "2026-10-19") // Monday mic, most recent Monday
        XCTAssertEqual(dates[3], "2026-10-16") // Friday mic, most recent Friday
    }

    func testSeedResolvesPlanDates() throws {
        let tables = try fixtureTables()
        let dates = Dictionary(uniqueKeysWithValues: tables.plans.map {
            (DemoValue.int($0["id"]) ?? 0, DemoValue.string($0["plan_date"]) ?? "")
        })
        XCTAssertEqual(dates[1], "2026-10-16") // past: same day as the planned set
        XCTAssertEqual(dates[2], "2026-10-26") // upcoming Monday
        XCTAssertEqual(dates[3], "2026-10-30") // upcoming Friday + 1 week
    }

    func testSeedResolvesScrapeLogTimes() throws {
        let tables = try fixtureTables()
        let times = tables.scrapeLog.map { DemoValue.string($0["last_scraped"]) }
        XCTAssertEqual(times, ["2026-10-20T18:07:00Z", "2026-10-19T18:08:00Z"])
    }

    func testSeedDropsHelperKeys() throws {
        let tables = try fixtureTables()
        for row in tables.sets + tables.plans + tables.scrapeLog {
            for key in DemoDateRules.helperKeys {
                XCTAssertNil(row[key], "\(key) should be removed")
            }
        }
    }

    func testSeedRejectsNonObjectJSON() {
        XCTAssertThrowsError(try DemoSeed.resolve(json: Data("[1, 2]".utf8), today: today))
        XCTAssertThrowsError(try DemoSeed.resolve(json: Data("not json".utf8), today: today))
    }

    func testBundledDemoDataResolvesConsistently() throws {
        #if os(iOS)
        // project.yml must copy the file into the app (Copy Bundle Resources).
        XCTAssertNotNil(Bundle.main.url(forResource: "DemoData", withExtension: "json"), "DemoData.json is not bundled")
        #endif
        let tables = try DemoSeed.resolve(json: bundledDemoJSON(), today: today, calendar: newYorkCalendar())
        let mics = try decodeRows(tables.mics, as: Mic.self)
        let sets = try decodeRows(tables.sets, as: SetEntry.self)
        let plans = try decodeRows(tables.plans, as: MicPlan.self)
        XCTAssertFalse(mics.isEmpty)
        XCTAssertEqual(sets.count, tables.sets.count, "every set row must decode (has a date)")
        XCTAssertEqual(plans.count, tables.plans.count, "every plan row must decode (has a date)")
        let micsByID = Dictionary(uniqueKeysWithValues: mics.map { ($0.id, $0) })

        // Sets are in the past, on their mic's weekday.
        for entry in sets {
            XCTAssertLessThan(entry.datePerformed, today)
            XCTAssertEqual(entry.datePerformed.weekday, micsByID[entry.openMicId ?? -1]?.weekday)
        }
        // Every plan is on its mic's weekday; each planned set has its "Going" plan.
        for plan in plans {
            XCTAssertEqual(plan.planDate.weekday, micsByID[plan.openMicId]?.weekday)
        }
        for planned in sets where planned.isPlanned {
            XCTAssertTrue(plans.contains { $0.openMicId == planned.openMicId && $0.planDate == planned.datePerformed && $0.status == .going })
        }
        // The `mic-detail` screenshot mic exists and has an upcoming plan.
        let launchMic = try XCTUnwrap(micsByID[LaunchRoute.micDetailID])
        let next = ScheduleRules.nextOccurrence(of: try XCTUnwrap(launchMic.weekday), from: today)
        XCTAssertTrue(plans.contains { $0.openMicId == launchMic.id && $0.planDate == next })
    }

    @MainActor
    private func findMic(_ id: Int, in backend: DemoBackend) async throws -> Mic? {
        try await backend.fetchMics().first { $0.id == id }
    }

    @MainActor
    private func findSet(_ id: Int, in backend: DemoBackend) async throws -> SetEntry? {
        try await backend.fetchSets().first { $0.id == id }
    }

    private func decodeRows<Row: Decodable>(_ rows: [DemoRow], as type: Row.Type) throws -> [Row] {
        let data = try JSONSerialization.data(withJSONObject: rows)
        return try JSONDecoder().decode([LossyDecodable<Row>].self, from: data).compactMap { $0.value }
    }

    // MARK: - DemoBackend: reads

    @MainActor
    func testBackendReadsSortLikeTheRealQueries() async throws {
        let backend = try fixtureBackend()

        let mics = try await backend.fetchMics()
        XCTAssertEqual(mics.map(\.id), [1, 2, 7])
        XCTAssertEqual(mics.last?.isActive, false)

        let sets = try await backend.fetchSets()
        XCTAssertEqual(sets.map(\.id), [2, 3, 1]) // newest first
        XCTAssertTrue(sets[1].isPlanned)

        let plans = try await backend.fetchPlans(from: day(2026, 10, 12), through: day(2026, 10, 27))
        XCTAssertEqual(plans.map(\.id), [1, 2])
        XCTAssertEqual(plans.first?.status, .going)

        let log = try await backend.fetchScrapeLog()
        XCTAssertEqual(log.map(\.id), [1, 2])

        let pending = try await backend.fetchPendingScraped()
        XCTAssertEqual(pending.map(\.id), [101, 102])
    }

    // MARK: - DemoBackend: plans (set_plan_status / clear_plan)

    @MainActor
    func testGoingCreatesOnePlannedSet() async throws {
        let backend = try fixtureBackend()
        let monday = day(2026, 10, 26)

        try await backend.setPlanStatus(micID: 1, date: monday, status: .going)
        try await backend.setPlanStatus(micID: 1, date: monday, status: .going)

        let plans = try await backend.fetchPlans(from: monday, through: monday)
        XCTAssertEqual(plans.count, 1)
        let planned = try await backend.fetchSets().filter { $0.openMicId == 1 && $0.datePerformed == monday }
        XCTAssertEqual(planned.count, 1, "Going twice must not add a second planned set")
        XCTAssertEqual(planned.first?.id, 4, "new ids continue from the largest id")
        XCTAssertEqual(planned.first?.isPlanned, true)
        XCTAssertEqual(planned.first?.wouldReturn, true)
    }

    @MainActor
    func testCancelledUpsertsWithoutAPlannedSet() async throws {
        let backend = try fixtureBackend()
        let friday = day(2026, 10, 23)
        let setCount = try await backend.fetchSets().count

        try await backend.setPlanStatus(micID: 2, date: friday, status: .cancelled)
        let cancelled = try await backend.fetchPlans(from: friday, through: friday)
        XCTAssertEqual(cancelled.first?.status, .cancelled)
        let setsAfterCancel = try await backend.fetchSets()
        XCTAssertEqual(setsAfterCancel.count, setCount)

        // Switching an existing plan to Going updates it (no duplicate plan).
        try await backend.setPlanStatus(micID: 2, date: friday, status: .going)
        let plans = try await backend.fetchPlans(from: friday, through: friday)
        XCTAssertEqual(plans.map(\.status), [.going])
        let setsAfterGoing = try await backend.fetchSets()
        XCTAssertEqual(setsAfterGoing.count, setCount + 1)
    }

    @MainActor
    func testClearPlanKeepsThePlannedSet() async throws {
        let backend = try fixtureBackend()
        let friday = day(2026, 10, 16)

        try await backend.clearPlan(micID: 2, date: friday)

        let plans = try await backend.fetchPlans(from: friday, through: friday)
        XCTAssertTrue(plans.isEmpty)
        let sets = try await backend.fetchSets()
        XCTAssertTrue(sets.contains { $0.id == 3 })
    }

    // MARK: - DemoBackend: sets

    @MainActor
    func testDeletePlannedSetRemovesItsPlanButNeverALoggedSet() async throws {
        let backend = try fixtureBackend()

        let deletedLogged = try await backend.deletePlannedSet(id: 1)
        XCTAssertFalse(deletedLogged, "logged sets are never deleted by delete_planned_set")
        let deletedMissing = try await backend.deletePlannedSet(id: 999)
        XCTAssertFalse(deletedMissing)

        let deleted = try await backend.deletePlannedSet(id: 3)
        XCTAssertTrue(deleted)
        let sets = try await backend.fetchSets()
        XCTAssertFalse(sets.contains { $0.id == 3 })
        let friday = day(2026, 10, 16)
        let plans = try await backend.fetchPlans(from: friday, through: friday)
        XCTAssertTrue(plans.isEmpty)
    }

    @MainActor
    func testInsertUpdateAndDeleteSet() async throws {
        let backend = try fixtureBackend()

        var fields = SetFields(openMicId: 2, datePerformed: day(2026, 10, 9), setRating: 8, tags: ["killed", "crowd_work"])
        try await backend.insertSet(fields)
        let insertedSet = try await findSet(4, in: backend)
        var added = try XCTUnwrap(insertedSet)
        XCTAssertEqual(added.setRating, 8)
        XCTAssertEqual(added.tags, "killed,crowd_work")

        fields.tags = []
        fields.notes = "Great room"
        try await backend.updateSet(id: 4, fields: fields)
        let updatedSet = try await findSet(4, in: backend)
        added = try XCTUnwrap(updatedSet)
        XCTAssertNil(added.tags, "an update sends explicit nulls for cleared fields")
        XCTAssertEqual(added.notes, "Great room")

        try await backend.deleteSet(id: 4)
        let deleted = try await findSet(4, in: backend)
        XCTAssertNil(deleted)
    }

    // MARK: - DemoBackend: mics

    @MainActor
    func testInsertAndUpdateMic() async throws {
        let backend = try fixtureBackend()

        var fields = MicFields()
        fields.name = "New Mic"
        fields.venue = "New Venue"
        fields.dayOfWeek = "Wednesday"
        fields.startTime = "20:00"
        fields.cost = "$5"
        fields.latitude = 40.7
        fields.longitude = -73.9
        let inserted = try await backend.insertMic(fields)
        let mic = try XCTUnwrap(inserted)
        XCTAssertEqual(mic.id, 8, "new ids continue from the largest id (7)")
        XCTAssertTrue(mic.isActive)
        XCTAssertNil(mic.address)

        // Clearing the cost sends null; coordinates stay unless asked.
        fields.cost = nil
        fields.latitude = nil
        fields.longitude = nil
        try await backend.updateMic(id: 8, fields: fields, includeCoordinates: false)
        let afterEdit = try await findMic(8, in: backend)
        var stored = try XCTUnwrap(afterEdit)
        XCTAssertNil(stored.cost)
        XCTAssertEqual(stored.latitude, 40.7)

        try await backend.updateMic(id: 8, fields: fields, includeCoordinates: true)
        let afterCoordinateClear = try await findMic(8, in: backend)
        stored = try XCTUnwrap(afterCoordinateClear)
        XCTAssertNil(stored.latitude)

        try await backend.updateMicRating(id: 8, rating: 8.5)
        try await backend.updateCoordinates(id: 8, latitude: 40.71, longitude: -73.99)
        let afterRating = try await findMic(8, in: backend)
        stored = try XCTUnwrap(afterRating)
        XCTAssertEqual(stored.micRating, 8.5)
        XCTAssertEqual(stored.longitude, -73.99)
    }

    @MainActor
    func testUpdatingAMissingMicThrowsNothingSaved() async throws {
        let backend = try fixtureBackend()
        do {
            try await backend.updateMicRating(id: 999, rating: 5)
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? APIError, .nothingSaved)
        }
    }

    @MainActor
    func testDeleteMicSoftDeletesAndDropsItsPlans() async throws {
        let backend = try fixtureBackend()

        try await backend.deleteMic(id: 2)

        let deletedMic = try await findMic(2, in: backend)
        let mic = try XCTUnwrap(deletedMic)
        XCTAssertFalse(mic.isActive, "soft delete keeps the row for set history")
        let plans = try await backend.fetchPlans(from: day(2026, 1, 1), through: day(2027, 1, 1))
        XCTAssertFalse(plans.contains { $0.openMicId == 2 })
        let sets = try await backend.fetchSets()
        XCTAssertTrue(sets.contains { $0.openMicId == 2 }, "sets are kept")
    }

    // MARK: - DemoBackend: scraper queue

    @MainActor
    func testImportCopiesPayloadIntoMics() async throws {
        let backend = try fixtureBackend()

        let newID = try await backend.importScraped(id: 101)
        XCTAssertEqual(newID, 8)

        let importedMic = try await findMic(newID, in: backend)
        let mic = try XCTUnwrap(importedMic)
        XCTAssertEqual(mic.name, "Candy Mic")
        XCTAssertEqual(mic.neighborhood, "Williamsburg")
        XCTAssertEqual(mic.displayTime, "7:30 PM")
        XCTAssertEqual(mic.setLengthMin, 30)
        XCTAssertTrue(mic.isBiweekly)
        XCTAssertTrue(mic.isActive)
        XCTAssertNil(mic.notes, "blank payload text counts as not provided")

        let pending = try await backend.fetchPendingScraped()
        XCTAssertEqual(pending.map(\.id), [102])
        do {
            _ = try await backend.importScraped(id: 101)
            XCTFail("importing twice should fail like the SQL function")
        } catch {
            XCTAssertNotNil(error as? APIError)
        }
    }

    @MainActor
    func testDismissRemovesFromPending() async throws {
        let backend = try fixtureBackend()
        try await backend.dismissScraped(id: 102)
        let pending = try await backend.fetchPendingScraped()
        XCTAssertEqual(pending.map(\.id), [101])
    }

    // MARK: - AppStore

    @MainActor
    func testNormalLaunchIsNotDemo() async {
        let store = AppStore(config: .empty)
        XCTAssertFalse(store.isDemoMode)
        await store.refreshAll()
        XCTAssertFalse(store.hasLoadedOnce, "an unconfigured, non-demo store loads nothing")
    }

    @MainActor
    func testStoreEntersAndExitsDemo() async throws {
        guard Bundle.main.url(forResource: "DemoData", withExtension: "json") != nil else {
            throw XCTSkip("DemoData.json is only bundled in the iOS app (the test host)")
        }
        let store = AppStore(config: .empty)
        store.enterDemo()
        XCTAssertTrue(store.isDemoMode)
        await store.refreshAll()
        XCTAssertTrue(store.hasLoadedOnce)
        XCTAssertNil(store.errorMessage)
        XCTAssertFalse(store.activeMics.isEmpty)
        XCTAssertEqual(store.plannedSets.count, 1)

        store.exitDemo()
        XCTAssertFalse(store.isDemoMode)
        XCTAssertTrue(store.mics.isEmpty)
        XCTAssertFalse(store.hasLoadedOnce)
    }

    // MARK: - LaunchOptions (spec 8.3)

    func testEveryScreenNameFromTheSpecParses() {
        let names = [
            "sign-in", "tonight", "calendar", "calendar-filters", "mic-detail", "map", "sets-log",
            "sets-history", "set-detail", "stats", "stats-charts", "manage", "import", "mic-form",
        ]
        XCTAssertEqual(LaunchScreen.allCases.map(\.rawValue), names)
        for name in names where name != "sign-in" {
            let options = LaunchOptions(demo: true, screenName: name)
            XCTAssertTrue(options.startsInDemo)
            XCTAssertEqual(options.screen?.rawValue, name)
        }
    }

    func testSignInScreenNeverStartsTheDemo() {
        let options = LaunchOptions(demo: true, screenName: "sign-in")
        XCTAssertFalse(options.startsInDemo)
        XCTAssertTrue(options.showsSignIn)
        XCTAssertTrue(LaunchOptions(demo: false, screenName: "sign-in").showsSignIn)
    }

    func testOtherScreensNeedDemo() {
        XCTAssertNil(LaunchOptions(demo: false, screenName: "stats").screen)
        XCTAssertEqual(LaunchOptions(demo: true, screenName: "nope"), LaunchOptions(demo: true, screenName: nil))
        XCTAssertEqual(LaunchOptions(demo: false, screenName: nil), LaunchOptions.none)
        XCTAssertFalse(LaunchOptions.none.startsInDemo)
        XCTAssertNil(LaunchOptions.none.screen)
    }

    func testLaunchHooksPerScreen() {
        func options(_ name: String) -> LaunchOptions { LaunchOptions(demo: true, screenName: name) }

        XCTAssertTrue(options("calendar-filters").opensCalendarFilters)
        XCTAssertFalse(options("calendar").opensCalendarFilters)
        XCTAssertTrue(options("manage").opensManage)
        XCTAssertTrue(options("import").opensManage)
        XCTAssertTrue(options("mic-form").opensManage)
        XCTAssertFalse(options("tonight").opensManage)
        XCTAssertTrue(options("sets-history").opensSetHistory)
        XCTAssertTrue(options("set-detail").opensSetHistory)
        XCTAssertFalse(options("sets-log").opensSetHistory)
        XCTAssertTrue(options("stats-charts").scrollsToStatsCharts)
        XCTAssertFalse(options("stats").scrollsToStatsCharts)

        XCTAssertEqual(options("mic-detail").initialRoutes(in: .tonight), [.micDetail])
        XCTAssertEqual(options("set-detail").initialRoutes(in: .sets), [.setDetail])
        XCTAssertEqual(options("import").initialRoutes(in: .manage), [.importScrapers])
        XCTAssertEqual(options("mic-form").initialRoutes(in: .manage), [.micForm])
        XCTAssertEqual(options("manage").initialRoutes(in: .manage), [])
        XCTAssertEqual(options("mic-detail").initialRoutes(in: .sets), [])
        XCTAssertEqual(LaunchOptions.none.initialRoutes(in: .tonight), [])
    }

    func testReadsLaunchArgumentsFromUserDefaults() throws {
        let suite = "DemoModeTests.launch"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        XCTAssertEqual(LaunchOptions(defaults: defaults), LaunchOptions.none)

        defaults.set(true, forKey: "demo")
        defaults.set("map", forKey: "screen")
        let options = LaunchOptions(defaults: defaults)
        XCTAssertTrue(options.startsInDemo)
        XCTAssertEqual(options.screen, .map)
    }
}
