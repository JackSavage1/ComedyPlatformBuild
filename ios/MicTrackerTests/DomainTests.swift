import XCTest
@testable import MicTracker

// =============================================================================
// Tests for the pure business rules in MicTracker/Domain (spec section 4).
// Dates used below: 2026-10-20 is a Tuesday; 2026 has 53 ISO weeks.
// =============================================================================
final class DomainTests: XCTestCase {

    // MARK: - Helpers

    private func day(_ year: Int, _ month: Int, _ day: Int) -> CalendarDate {
        CalendarDate(year: year, month: month, day: day)
    }

    private func rated(_ date: CalendarDate, _ rating: Double) -> DatedRating {
        DatedRating(date: date, rating: rating)
    }

    // MARK: - Rule 1: time display

    func testTimeTextPrefersDisplayTime() {
        XCTAssertEqual(MicRules.timeText(displayTime: "7:00 PM", startTime: "19:00"), "7:00 PM")
        XCTAssertEqual(MicRules.timeText(displayTime: nil, startTime: "19:00"), "19:00")
        XCTAssertEqual(MicRules.timeText(displayTime: "   ", startTime: "19:00"), "19:00")
        XCTAssertEqual(MicRules.timeText(displayTime: nil, startTime: nil), "")
    }

    // MARK: - Rule 2: cost text and badge

    func testCostText() {
        XCTAssertEqual(MicRules.costText(nil), "Free/TBD")
        XCTAssertEqual(MicRules.costText("  "), "Free/TBD")
        XCTAssertEqual(MicRules.costText("$5"), "$5")
    }

    func testCostBadge() {
        XCTAssertEqual(CostKind(cost: nil), .free)
        XCTAssertEqual(CostKind(cost: "   "), .free)
        XCTAssertEqual(CostKind(cost: "FREE!"), .free)
        XCTAssertEqual(CostKind(cost: "1 Drink Minimum"), .drinkMin)
        XCTAssertEqual(CostKind(cost: "$5"), .paid)
        XCTAssertEqual(CostKind(cost: "Free with a drink"), .free) // "free" wins
        XCTAssertEqual(CostKind.free.emoji, "🆓")
        XCTAssertEqual(CostKind.drinkMin.label, "Drink min")
        XCTAssertEqual(CostKind.paid.emoji, "💵")
    }

    // MARK: - Rule 3: urgency

    func testUrgencyParsingAndDefaults() {
        XCTAssertEqual(Urgency(raw: "high"), .high)
        XCTAssertEqual(Urgency(raw: " HIGH "), .high)
        XCTAssertEqual(Urgency(raw: "medium"), .medium)
        XCTAssertEqual(Urgency(raw: "normal"), .normal)
        XCTAssertEqual(Urgency(raw: "urgent!!"), .normal)
        XCTAssertEqual(Urgency(raw: nil), .normal)
        XCTAssertEqual(Urgency.high.dot, "🔴")
        XCTAssertEqual(Urgency.medium.dot, "🟡")
        XCTAssertEqual(Urgency.normal.dot, "🟢")
        XCTAssertEqual(Urgency.high.defaultNote, "Signs up fast — book ahead!")
        XCTAssertEqual(Urgency.medium.defaultNote, "Book a few days early")
        XCTAssertNil(Urgency.normal.defaultNote)
    }

    func testMicUrgencyMessageUsesOwnNoteFirst() {
        let withNote = Mic(id: 1, urgency: "high", urgencyNote: "Fills in 2 minutes")
        XCTAssertEqual(withNote.urgencyMessage, "Fills in 2 minutes")
        let withoutNote = Mic(id: 2, urgency: "medium")
        XCTAssertEqual(withoutNote.urgencyMessage, "Book a few days early")
    }

    // MARK: - Rule 4: cost filter

    func testCostFilterAll() {
        XCTAssertTrue(CostFilter.all.matches(cost: nil))
        XCTAssertTrue(CostFilter.all.matches(cost: "$20"))
    }

    func testCostFilterFreeOnly() {
        XCTAssertTrue(CostFilter.freeOnly.matches(cost: nil))
        XCTAssertTrue(CostFilter.freeOnly.matches(cost: "  "))
        XCTAssertTrue(CostFilter.freeOnly.matches(cost: "FREE"))
        XCTAssertFalse(CostFilter.freeOnly.matches(cost: "$5"))
        XCTAssertFalse(CostFilter.freeOnly.matches(cost: "1 drink min"))
    }

    func testCostFilterUnderSix() {
        XCTAssertTrue(CostFilter.underSix.matches(cost: nil))
        XCTAssertTrue(CostFilter.underSix.matches(cost: "Free"))
        XCTAssertTrue(CostFilter.underSix.matches(cost: "$5"))
        XCTAssertTrue(CostFilter.underSix.matches(cost: "$5.50 + 1 drink"))
        XCTAssertTrue(CostFilter.underSix.matches(cost: "Suggested $3 donation"))
        // FIX vs. Streamlit: "$10" used to match because of `\$[1-5]`.
        XCTAssertFalse(CostFilter.underSix.matches(cost: "$10"))
        XCTAssertFalse(CostFilter.underSix.matches(cost: "$15 or $5 with drink")) // first amount counts
        XCTAssertFalse(CostFilter.underSix.matches(cost: "$6"))
        XCTAssertFalse(CostFilter.underSix.matches(cost: "1 drink min"))
    }

    func testCostFilterDrinkMin() {
        XCTAssertTrue(CostFilter.drinkMinOnly.matches(cost: "1 Drink Min"))
        XCTAssertFalse(CostFilter.drinkMinOnly.matches(cost: "Free"))
        XCTAssertFalse(CostFilter.drinkMinOnly.matches(cost: nil))
    }

    func testFirstDollarAmount() {
        XCTAssertEqual(CostFilter.firstDollarAmount(in: "$5"), 5)
        XCTAssertEqual(CostFilter.firstDollarAmount(in: "only $5.50!"), 5.5)
        XCTAssertEqual(CostFilter.firstDollarAmount(in: "$$7"), 7)
        XCTAssertEqual(CostFilter.firstDollarAmount(in: "$10, $3"), 10)
        XCTAssertEqual(CostFilter.firstDollarAmount(in: "$5."), 5)
        XCTAssertNil(CostFilter.firstDollarAmount(in: "five dollars"))
        XCTAssertNil(CostFilter.firstDollarAmount(in: "$ 5"))
    }

    // MARK: - Rule 5: signup filter

    func testSignupFilter() {
        XCTAssertTrue(SignupFilter.all.matches(signupMethod: nil))
        XCTAssertTrue(SignupFilter.online.matches(signupMethod: "online"))
        XCTAssertTrue(SignupFilter.inPerson.matches(signupMethod: " In_Person "))
        XCTAssertTrue(SignupFilter.email.matches(signupMethod: "email"))
        XCTAssertTrue(SignupFilter.instagramDM.matches(signupMethod: "instagram_dm"))
        XCTAssertFalse(SignupFilter.online.matches(signupMethod: "in_person"))
        XCTAssertFalse(SignupFilter.online.matches(signupMethod: nil))
        XCTAssertEqual(SignupFilter.allCases.map { $0.title }, ["All", "Online", "In-person", "Email", "Instagram DM"])
    }

    // MARK: - Rule 6: visited = logged sets only

    func testLoggedVisitCountsIgnorePlannedRows() {
        let date = day(2026, 10, 20)
        let sets = [
            SetEntry(id: 1, openMicId: 10, datePerformed: date, setRating: 7),
            SetEntry(id: 2, openMicId: 10, datePerformed: date, setRating: nil), // planned
            SetEntry(id: 3, openMicId: 10, datePerformed: date.adding(days: -7), setRating: 4),
            SetEntry(id: 4, openMicId: 11, datePerformed: date, setRating: nil), // planned only
            SetEntry(id: 5, openMicId: nil, datePerformed: date, setRating: 5),
        ]
        let counts = SetRules.loggedVisitCounts(sets)
        XCTAssertEqual(counts[10], 2)
        XCTAssertNil(counts[11])
        XCTAssertEqual(counts.count, 1)
        XCTAssertTrue(sets[1].isPlanned)
        XCTAssertTrue(sets[0].isLogged)
    }

    // MARK: - Rule 7: signup deadline

    func testSignupDeadline() {
        let today = day(2026, 10, 20) // Tuesday

        // No deadline without advance days.
        XCTAssertNil(SignupDeadline.compute(advanceDays: 0, micDate: day(2026, 10, 27), today: today))
        XCTAssertNil(SignupDeadline.compute(advanceDays: nil, micDate: day(2026, 10, 27), today: today))
        XCTAssertNil(SignupDeadline.compute(advanceDays: -2, micDate: day(2026, 10, 27), today: today))

        let passed = SignupDeadline.compute(advanceDays: 3, micDate: day(2026, 10, 21), today: today)
        XCTAssertEqual(passed, .passed)
        XCTAssertEqual(passed?.text, "⚠️ Signup deadline passed!")

        let todayDeadline = SignupDeadline.compute(advanceDays: 2, micDate: day(2026, 10, 22), today: today)
        XCTAssertEqual(todayDeadline, .today)
        XCTAssertEqual(todayDeadline?.text, "🔴 Sign up TODAY!")

        let tomorrow = SignupDeadline.compute(advanceDays: 1, micDate: day(2026, 10, 22), today: today)
        XCTAssertEqual(tomorrow, .tomorrow)
        XCTAssertEqual(tomorrow?.text, "🟡 Sign up by TOMORROW!")

        let upcoming = SignupDeadline.compute(advanceDays: 4, micDate: day(2026, 10, 27), today: today)
        XCTAssertEqual(upcoming, .upcoming(signupBy: day(2026, 10, 23), daysUntil: 3))
        XCTAssertEqual(upcoming?.text, "📅 Sign up by Fri 10/23 (3 days)")

        let twoDays = SignupDeadline.compute(advanceDays: 7, micDate: day(2026, 10, 29), today: today)
        XCTAssertEqual(twoDays?.text, "📅 Sign up by Thu 10/22 (2 days)")
    }

    // MARK: - Rule 8: sign-up URL

    func testSignupURL() {
        XCTAssertEqual(SignupRules.signupURL(from: "https://example.com/signup")?.absoluteString, "https://example.com/signup")
        XCTAssertEqual(SignupRules.signupURL(from: "  http://x.co  ")?.absoluteString, "http://x.co")
        XCTAssertEqual(SignupRules.signupURL(from: "www.eventbrite.com/e/123")?.absoluteString, "https://www.eventbrite.com/e/123")
        XCTAssertEqual(SignupRules.signupURL(from: "bit.ly/abc")?.absoluteString, "https://bit.ly/abc")
        XCTAssertNotNil(SignupRules.signupURL(from: "HTTPS://Example.com/x"))
        XCTAssertNil(SignupRules.signupURL(from: "Sign up at the bar"))
        XCTAssertNil(SignupRules.signupURL(from: "Sign up at bar.com tonight"))
        XCTAssertNil(SignupRules.signupURL(from: "https://"))
        XCTAssertNil(SignupRules.signupURL(from: "nodots"))
        XCTAssertNil(SignupRules.signupURL(from: "   "))
        XCTAssertNil(SignupRules.signupURL(from: nil))
    }

    func testSignupNoteShownOnlyForNonLinks() {
        XCTAssertEqual(SignupRules.signupNote(from: " Sign up at the bar "), "Sign up at the bar")
        XCTAssertNil(SignupRules.signupNote(from: "www.example.com"))
        XCTAssertNil(SignupRules.signupNote(from: nil))
    }

    // MARK: - Rule 9: Instagram

    func testInstagramHandleParsing() {
        XCTAssertEqual(InstagramRules.handle(from: "@nycmic"), "nycmic")
        XCTAssertEqual(InstagramRules.handle(from: "nycmic"), "nycmic")
        XCTAssertEqual(InstagramRules.handle(from: "  @@nyc.mic_  "), "nyc.mic_")
        XCTAssertEqual(InstagramRules.handle(from: "https://www.instagram.com/nyc.mic_/?hl=en"), "nyc.mic_")
        XCTAssertEqual(InstagramRules.handle(from: "instagram.com/comedyspot"), "comedyspot")
        XCTAssertEqual(InstagramRules.handle(from: "HTTPS://INSTAGRAM.COM/Caps_Handle/"), "Caps_Handle")
        XCTAssertEqual(InstagramRules.handle(from: "@handle?igsh=abc123"), "handle")
        XCTAssertEqual(InstagramRules.handle(from: "handle/"), "handle")
        XCTAssertNil(InstagramRules.handle(from: "DM the host"))
        XCTAssertNil(InstagramRules.handle(from: "@"))
        XCTAssertNil(InstagramRules.handle(from: "instagram.com/"))
        XCTAssertNil(InstagramRules.handle(from: "bad-handle"))
        XCTAssertNil(InstagramRules.handle(from: nil))
        XCTAssertEqual(InstagramRules.handle(from: String(repeating: "a", count: 30))?.count, 30)
        XCTAssertNil(InstagramRules.handle(from: String(repeating: "a", count: 31)))
    }

    func testInstagramProfileURL() {
        XCTAssertEqual(InstagramRules.profileURL(handle: "nycmic")?.absoluteString, "https://instagram.com/nycmic")
        let mic = Mic(id: 1, instagram: "@nycmic")
        XCTAssertEqual(ExternalLinks.instagramURL(for: mic)?.absoluteString, "https://instagram.com/nycmic")
        XCTAssertNil(ExternalLinks.instagramURL(for: Mic(id: 2, instagram: "ask the host")))
    }

    // MARK: - Rule 10: set length

    func testSetLengthText() {
        XCTAssertEqual(MicRules.setLengthText(5), "5 min sets")
        XCTAssertEqual(MicRules.setLengthText(0), "Set length: N/A")
        XCTAssertEqual(MicRules.setLengthText(nil), "Set length: N/A")
        XCTAssertEqual(MicRules.setLengthText(-3), "Set length: N/A")
    }

    // MARK: - Rule 11: week streak

    func testWeekStreakEmpty() {
        XCTAssertEqual(SetRules.weekStreak(loggedDates: [], today: day(2026, 10, 21)), 0)
    }

    func testWeekStreakCountsConsecutiveWeeks() {
        let today = day(2026, 10, 21) // Wednesday; week starts Mon 10/19
        let dates = [day(2026, 10, 19), day(2026, 10, 20), day(2026, 10, 14), day(2026, 10, 6)]
        XCTAssertEqual(SetRules.weekStreak(loggedDates: dates, today: today), 3)
    }

    func testWeekStreakNotBrokenOnMondayMorning() {
        let today = day(2026, 10, 19) // Monday, nothing logged yet this week
        let dates = [day(2026, 10, 16), day(2026, 10, 8)]
        XCTAssertEqual(SetRules.weekStreak(loggedDates: dates, today: today), 2)
    }

    func testWeekStreakStopsAtGap() {
        let today = day(2026, 10, 21)
        XCTAssertEqual(SetRules.weekStreak(loggedDates: [day(2026, 10, 20), day(2026, 10, 7)], today: today), 1)
        XCTAssertEqual(SetRules.weekStreak(loggedDates: [day(2026, 10, 1)], today: today), 0)
    }

    func testWeekStreakAcrossYearBoundaryAnd53WeekYear() {
        // 2026 has an ISO week 53 (Dec 28 – Jan 3). The old Dashboard code
        // jumped from week 1 straight to week 52 and broke here.
        let today = day(2027, 1, 6) // Wednesday of 2027-W01
        let dates = [
            day(2027, 1, 4),   // 2027-W01
            day(2026, 12, 30), // 2026-W53
            day(2026, 12, 22), // 2026-W52
            day(2026, 12, 15), // 2026-W51
            day(2026, 12, 1),  // 2026-W49 (W50 missing → not counted)
        ]
        XCTAssertEqual(SetRules.weekStreak(loggedDates: dates, today: today), 4)

        // Monday Jan 4, 2027 with nothing yet this week: start from W53.
        XCTAssertEqual(
            SetRules.weekStreak(loggedDates: [day(2026, 12, 31), day(2026, 12, 24)], today: day(2027, 1, 4)),
            2
        )
    }

    // MARK: - Rule 12: month-over-month delta

    func testMonthOverMonthDelta() throws {
        let today = day(2026, 10, 20)
        let ratings = [
            rated(day(2026, 10, 1), 8), rated(day(2026, 10, 15), 6),   // Oct avg 7
            rated(day(2026, 9, 10), 5), rated(day(2026, 9, 30), 6),    // Sep avg 5.5
            rated(day(2026, 8, 1), 1),                                 // ignored
        ]
        let delta = try XCTUnwrap(SetRules.monthOverMonthDelta(ratings, today: today))
        XCTAssertEqual(delta, 1.5, accuracy: 1e-9)
        XCTAssertEqual(SetRules.deltaText(delta), "+1.5 vs last month")
    }

    func testMonthOverMonthDeltaAcrossYearAndRounding() throws {
        let january = try XCTUnwrap(SetRules.monthOverMonthDelta(
            [rated(day(2027, 1, 2), 6), rated(day(2026, 12, 5), 7), rated(day(2026, 12, 31), 8)],
            today: day(2027, 1, 10)
        ))
        XCTAssertEqual(january, -1.5, accuracy: 1e-9)
        XCTAssertEqual(SetRules.deltaText(january), "-1.5 vs last month")

        let rounded = try XCTUnwrap(SetRules.monthOverMonthDelta(
            [rated(day(2026, 10, 1), 8), rated(day(2026, 10, 2), 7),
             rated(day(2026, 9, 1), 7), rated(day(2026, 9, 2), 7), rated(day(2026, 9, 3), 8)],
            today: day(2026, 10, 20)
        ))
        XCTAssertEqual(rounded, 0.2, accuracy: 1e-9) // 7.5 − 7.333… = 0.1666… → 0.2

        let zero = try XCTUnwrap(SetRules.monthOverMonthDelta(
            [rated(day(2026, 10, 1), 7), rated(day(2026, 9, 1), 7)],
            today: day(2026, 10, 20)
        ))
        XCTAssertEqual(SetRules.deltaText(zero), "+0.0 vs last month")
    }

    func testMonthOverMonthDeltaHiddenWithoutBothMonths() {
        XCTAssertNil(SetRules.monthOverMonthDelta([rated(day(2026, 10, 1), 8)], today: day(2026, 10, 20)))
        XCTAssertNil(SetRules.monthOverMonthDelta([rated(day(2026, 9, 1), 8)], today: day(2026, 10, 20)))
        XCTAssertNil(SetRules.monthOverMonthDelta([], today: day(2026, 10, 20)))
    }

    // MARK: - Rule 13: OLS trendline

    func testOLSTrendLine() throws {
        let start = day(2026, 10, 1)
        let line = try XCTUnwrap(SetRules.olsTrend([
            rated(start.adding(days: 4), 8),
            rated(start, 4),
            rated(start.adding(days: 2), 6),
        ]))
        XCTAssertEqual(line.origin, start) // x = days since the FIRST set
        XCTAssertEqual(line.slope, 1, accuracy: 1e-9)
        XCTAssertEqual(line.intercept, 4, accuracy: 1e-9)
        XCTAssertEqual(line.value(at: start.adding(days: 6)), 10, accuracy: 1e-9)
    }

    func testOLSTrendLineWithRepeatedDate() throws {
        let start = day(2026, 10, 1)
        let line = try XCTUnwrap(SetRules.olsTrend([
            rated(start, 4), rated(start, 6), rated(start.adding(days: 2), 8),
        ]))
        XCTAssertEqual(line.slope, 1.5, accuracy: 1e-9)
        XCTAssertEqual(line.intercept, 5, accuracy: 1e-9)
    }

    func testOLSTrendLineNeedsTwoDistinctDates() {
        let start = day(2026, 10, 1)
        XCTAssertNil(SetRules.olsTrend([]))
        XCTAssertNil(SetRules.olsTrend([rated(start, 5)]))
        XCTAssertNil(SetRules.olsTrend([rated(start, 5), rated(start, 7)]))
    }

    // MARK: - Rule 14: tags

    func testTagParsingAndJoining() {
        XCTAssertEqual(TagRules.parse("killed, crowd_work,,  "), ["killed", "crowd_work"])
        XCTAssertEqual(TagRules.parse("killed"), ["killed"])
        XCTAssertEqual(TagRules.parse(""), [])
        XCTAssertEqual(TagRules.parse(nil), [])
        XCTAssertEqual(TagRules.join(["killed", "crowd_work"]), "killed,crowd_work")
        XCTAssertEqual(TagRules.join([" new_bit ", ""]), "new_bit")
        XCTAssertNil(TagRules.join([]))
        XCTAssertNil(TagRules.join(["  "]))
        XCTAssertEqual(SetEntry(id: 1, datePerformed: day(2026, 10, 1), tags: "bombed, nervous").tagList, ["bombed", "nervous"])
    }

    func testTagSentimentAndColors() {
        for tag in ["killed", "got_laughs", "confident", "crowd_work", "got_spotted"] {
            XCTAssertEqual(TagRules.sentiment(of: tag), .positive, tag)
        }
        XCTAssertEqual(TagRules.sentiment(of: "bombed"), .negative)
        XCTAssertEqual(TagRules.sentiment(of: "nervous"), .negative)
        XCTAssertEqual(TagRules.sentiment(of: "new_bit"), .neutral)
        XCTAssertEqual(TagRules.sentiment(of: " KILLED "), .positive)
        XCTAssertEqual(TagSentiment.positive.colorHex, "#2ECC71")
        XCTAssertEqual(TagSentiment.negative.colorHex, "#E74C3C")
        XCTAssertEqual(TagSentiment.neutral.colorHex, "#3498DB")
        XCTAssertEqual(TagRules.options.count, 10)
    }

    // MARK: - Rule 15: rating emoji

    func testRatingEmoji() {
        XCTAssertEqual(SetRules.ratingEmoji(setRating: nil), "📋")
        XCTAssertEqual(SetRules.ratingEmoji(setRating: 10), "🔥")
        XCTAssertEqual(SetRules.ratingEmoji(setRating: 8), "🔥")
        XCTAssertEqual(SetRules.ratingEmoji(setRating: 7), "👍")
        XCTAssertEqual(SetRules.ratingEmoji(setRating: 5), "👍")
        XCTAssertEqual(SetRules.ratingEmoji(setRating: 4), "😬")
        XCTAssertEqual(SetRules.ratingEmoji(setRating: 1), "😬")
    }

    // MARK: - Rule 16: next occurrence

    func testNextOccurrence() {
        let tuesday = day(2026, 10, 20)
        XCTAssertEqual(ScheduleRules.nextOccurrence(of: .tuesday, from: tuesday), tuesday)
        XCTAssertEqual(ScheduleRules.nextOccurrence(of: .wednesday, from: tuesday), day(2026, 10, 21))
        XCTAssertEqual(ScheduleRules.nextOccurrence(of: .sunday, from: tuesday), day(2026, 10, 25))
        XCTAssertEqual(ScheduleRules.nextOccurrence(of: .monday, from: tuesday), day(2026, 10, 26))
        XCTAssertEqual(ScheduleRules.nextOccurrence(of: .monday, from: day(2026, 10, 25)), day(2026, 10, 26))
        XCTAssertEqual(ScheduleRules.nextOccurrence(of: .friday, from: day(2026, 12, 30)), day(2027, 1, 1))
    }

    // MARK: - Rule 17: borough colors

    func testBoroughColors() {
        XCTAssertEqual(BoroughRules.colorHex(for: "Manhattan"), "#FF6347")
        XCTAssertEqual(BoroughRules.colorHex(for: "brooklyn"), "#1E90FF")
        XCTAssertEqual(BoroughRules.colorHex(for: "Queens"), "#32CD32")
        XCTAssertEqual(BoroughRules.colorHex(for: "Bronx"), "#FFA500")
        XCTAssertEqual(BoroughRules.colorHex(for: "Staten Island"), "#9370DB")
        XCTAssertEqual(BoroughRules.colorHex(for: "Jersey City"), "#808080")
        XCTAssertEqual(BoroughRules.colorHex(for: nil), "#808080")
        XCTAssertEqual(BoroughRules.all, ["Manhattan", "Brooklyn", "Queens", "Bronx", "Staten Island"])
    }

    // MARK: - Rule 18: NYC bounds and geocode queries

    func testNYCBounds() {
        XCTAssertTrue(GeoRules.isInNYC(latitude: 40.7128, longitude: -74.0060))
        XCTAssertTrue(GeoRules.isInNYC(GeoPoint(latitude: 40.6782, longitude: -73.9442)))
        XCTAssertFalse(GeoRules.isInNYC(latitude: 40.4, longitude: -74.0))   // bounds are strict
        XCTAssertFalse(GeoRules.isInNYC(latitude: 41.0, longitude: -74.0))
        XCTAssertFalse(GeoRules.isInNYC(latitude: 40.7, longitude: -73.5))
        XCTAssertFalse(GeoRules.isInNYC(latitude: 40.7, longitude: -74.3))
        XCTAssertFalse(GeoRules.isInNYC(latitude: 34.05, longitude: -118.24)) // Los Angeles
    }

    func testGeocodeQueries() {
        XCTAssertEqual(
            GeoRules.geocodeQueries(address: " 123 Main St ", borough: "Brooklyn"),
            ["123 Main St, Brooklyn, New York, NY", "123 Main St, New York, NY"]
        )
        XCTAssertEqual(GeoRules.geocodeQueries(address: "123 Main St", borough: nil), ["123 Main St, New York, NY"])
        XCTAssertEqual(GeoRules.geocodeQueries(address: "  ", borough: "Queens"), [])
        XCTAssertEqual(GeoRules.geocodeQueries(address: nil, borough: nil), [])
    }

    // MARK: - Rule 19: picked time → stored / display time

    func testTimeConversions() throws {
        XCTAssertEqual(TimeRules.displayTimeString(hour: 19, minute: 0), "7:00 PM")
        XCTAssertEqual(TimeRules.displayTimeString(hour: 0, minute: 5), "12:05 AM")
        XCTAssertEqual(TimeRules.displayTimeString(hour: 12, minute: 0), "12:00 PM")
        XCTAssertEqual(TimeRules.displayTimeString(hour: 9, minute: 30), "9:30 AM")
        XCTAssertEqual(TimeRules.startTimeString(hour: 7, minute: 5), "07:05")
        XCTAssertEqual(TimeRules.startTimeString(hour: 19, minute: 0), "19:00")
        let parsed = try XCTUnwrap(TimeRules.hourAndMinute(from: "19:30"))
        XCTAssertEqual(parsed.hour, 19)
        XCTAssertEqual(parsed.minute, 30)
        XCTAssertNil(TimeRules.hourAndMinute(from: "late"))
    }

    // MARK: - Sorting mics within a day

    func testMinutesSinceMidnight() {
        XCTAssertEqual(MicRules.minutesSinceMidnight("19:00"), 1140)
        XCTAssertEqual(MicRules.minutesSinceMidnight("7:05"), 425)
        XCTAssertEqual(MicRules.minutesSinceMidnight("07:05:30"), 425)
        XCTAssertEqual(MicRules.minutesSinceMidnight(" 00:00 "), 0)
        XCTAssertEqual(MicRules.minutesSinceMidnight("7:00 PM"), 1140)
        XCTAssertEqual(MicRules.minutesSinceMidnight("12:00 AM"), 0)
        XCTAssertEqual(MicRules.minutesSinceMidnight("12:30 pm"), 750)
        XCTAssertEqual(MicRules.minutesSinceMidnight("7pm"), 1140)
        XCTAssertNil(MicRules.minutesSinceMidnight("24:00"))
        XCTAssertNil(MicRules.minutesSinceMidnight("19:60"))
        XCTAssertNil(MicRules.minutesSinceMidnight("19:0"))
        XCTAssertNil(MicRules.minutesSinceMidnight("19"))
        XCTAssertNil(MicRules.minutesSinceMidnight("TBD"))
        XCTAssertNil(MicRules.minutesSinceMidnight(""))
        XCTAssertNil(MicRules.minutesSinceMidnight(nil))
    }

    func testSortedByStartTime() {
        let mics = [
            Mic(id: 1, name: "Late", startTime: "21:00"),
            Mic(id: 2, name: "beta", startTime: "19:00"),
            Mic(id: 3, name: "Alpha", startTime: "19:00"),
            Mic(id: 4, name: "Zed", startTime: "whenever"),
            Mic(id: 5, name: "Early", startTime: "18:30"),
            Mic(id: 6, name: "Aaa", startTime: ""),
        ]
        let names = MicRules.sortedByStartTime(mics).map { $0.name }
        XCTAssertEqual(names, ["Early", "Alpha", "beta", "Late", "Aaa", "Zed"])
    }

    func testSortedByDayAndTime() {
        let mics = [
            Mic(id: 1, name: "A", dayOfWeek: "Tuesday", startTime: "19:00"),
            Mic(id: 2, name: "B", dayOfWeek: "Monday", startTime: "21:00"),
            Mic(id: 3, name: "C", dayOfWeek: "monday", startTime: "18:00"),
            Mic(id: 4, name: "D", dayOfWeek: "Funday", startTime: "10:00"),
        ]
        XCTAssertEqual(MicRules.sortedByDayAndTime(mics).map { $0.name }, ["C", "B", "A", "D"])
    }

    // MARK: - Enum fallbacks and text

    func testPrettifyAndUnknownEnums() {
        XCTAssertEqual(TextRules.prettify("in_person"), "In Person")
        XCTAssertEqual(TextRules.prettify("DECENT"), "Decent")
        XCTAssertEqual(TextRules.prettify("walk__up"), "Walk Up")
        XCTAssertEqual(SignupMethod(raw: "instagram_dm")?.displayName, "Instagram DM")
        XCTAssertEqual(SignupMethod(raw: "in_person"), .inPerson)
        XCTAssertEqual(SignupMethod(raw: "walk_up"), .other("walk_up"))
        XCTAssertEqual(SignupMethod(raw: "walk_up")?.displayName, "Walk Up")
        XCTAssertNil(SignupMethod(raw: "  "))
        XCTAssertEqual(CrowdSize(raw: "PACKED"), .packed)
        XCTAssertEqual(CrowdSize(raw: "huge")?.displayName, "Huge")
        XCTAssertEqual(CrowdSize.chartWeight(forRaw: "decent"), 35)
        XCTAssertEqual(CrowdSize.chartWeight(forRaw: nil), 20)
        XCTAssertEqual(CrowdSize.chartWeight(forRaw: "huge"), 20)
        XCTAssertEqual(RecordingType(raw: "none"), .noRecording)
        XCTAssertEqual(RecordingType(raw: "video")?.rawValue, "video")
        XCTAssertEqual(RecordingType(raw: "tiktok"), .other("tiktok"))
    }

    func testWeekdayParsing() {
        XCTAssertEqual(Weekday(name: "Monday"), .monday)
        XCTAssertEqual(Weekday(name: " sunday "), .sunday)
        XCTAssertEqual(Weekday(name: "Wed"), .wednesday)
        XCTAssertNil(Weekday(name: "Funday"))
        XCTAssertNil(Weekday(name: nil))
        XCTAssertEqual(Weekday.sunday.next, .monday)
        XCTAssertEqual(Weekday.monday.previous, .sunday)
        XCTAssertEqual(Weekday.thursday.shortName, "Thu")
        XCTAssertEqual(Weekday.allCases.map { $0.name }.first, "Monday")
    }

    func testMicConveniences() {
        let mic = Mic(
            id: 7, name: "Open Mic", venue: "The Club", neighborhood: "East Village", borough: "Manhattan",
            dayOfWeek: "Monday", startTime: "19:00", displayTime: "7:00 PM", setLengthMin: 5,
            signupUrl: "www.example.com", latitude: 40.72, longitude: -73.98
        )
        XCTAssertEqual(mic.weekday, .monday)
        XCTAssertEqual(mic.scheduleText, "Mondays at 7:00 PM")
        XCTAssertEqual(mic.locationText, "East Village, Manhattan")
        XCTAssertEqual(mic.setLengthText, "5 min sets")
        XCTAssertEqual(mic.signupLink?.absoluteString, "https://www.example.com")
        XCTAssertTrue(mic.hasCoordinates)
        XCTAssertFalse(Mic(id: 8, latitude: 40.7).hasCoordinates)
    }

    // MARK: - Calendar week labels

    func testWeekTitlesAndRange() {
        XCTAssertEqual(ScheduleRules.weekTitle(offset: -1), "Last Week")
        XCTAssertEqual(ScheduleRules.weekTitle(offset: 0), "This Week")
        XCTAssertEqual(ScheduleRules.weekTitle(offset: 1), "Next Week")
        XCTAssertEqual(ScheduleRules.weekTitle(offset: 3), "3 Weeks Out")
        XCTAssertEqual(ScheduleRules.weekRangeText(monday: day(2026, 10, 19)), "Oct 19 – Oct 25, 2026")
        XCTAssertEqual(ScheduleRules.weekRangeText(monday: day(2026, 12, 28)), "Dec 28 – Jan 3, 2027")
        XCTAssertEqual(ScheduleRules.monday(weekOffset: 1, today: day(2026, 10, 20)), day(2026, 10, 26))
        XCTAssertEqual(ScheduleRules.weekDates(startingAt: day(2026, 10, 19)).last, day(2026, 10, 25))
    }
}
