import XCTest
@testable import MicTracker

/// CalendarDate is the app's "calendar day without a time zone" type (spec
/// 3.5). These tests pin down parsing, arithmetic, weekdays, ISO weeks and
/// the round trip through `Date` in several time zones.
final class CalendarDateTests: XCTestCase {

    // MARK: - Parsing & formatting

    func testParsesDatabaseDates() throws {
        let date = try XCTUnwrap(CalendarDate(string: "2026-10-20"))
        XCTAssertEqual(date.year, 2026)
        XCTAssertEqual(date.month, 10)
        XCTAssertEqual(date.day, 20)
        XCTAssertEqual(date.isoString, "2026-10-20")
        XCTAssertEqual(CalendarDate(string: " 2026-10-20T00:00:00 "), date)
    }

    func testRejectsInvalidDates() {
        XCTAssertNil(CalendarDate(string: "2026-02-30"))
        XCTAssertNil(CalendarDate(string: "2026-13-01"))
        XCTAssertNil(CalendarDate(string: "2026-00-10"))
        XCTAssertNil(CalendarDate(string: "20261020"))
        XCTAssertNil(CalendarDate(string: "10/20/2026"))
        XCTAssertNil(CalendarDate(string: "hello"))
        XCTAssertNil(CalendarDate(string: ""))
        XCTAssertNil(CalendarDate(string: nil))
        XCTAssertNotNil(CalendarDate(string: "2024-02-29"))
        XCTAssertNil(CalendarDate(string: "2023-02-29"))
    }

    func testFormatting() {
        let date = CalendarDate(year: 2026, month: 10, day: 20)
        XCTAssertEqual(date.mmdd, "10/20")
        XCTAssertEqual(date.mmddyyyy, "10/20/2026")
        XCTAssertEqual(date.monthKey, "2026-10")
        XCTAssertEqual(date.longText, "Tuesday, October 20, 2026")
        XCTAssertEqual(date.shortText, "Oct 20")
        XCTAssertEqual(date.shortTextWithYear, "Oct 20, 2026")
        XCTAssertEqual(date.weekdayAndMMDD, "Tue 10/20")
        XCTAssertEqual(CalendarDate(year: 2027, month: 1, day: 5).mmddyyyy, "01/05/2027")
        XCTAssertEqual(date.description, "2026-10-20")
    }

    // MARK: - Arithmetic

    func testDayNumbers() {
        XCTAssertEqual(CalendarDate(year: 1970, month: 1, day: 1).dayNumber, 0)
        XCTAssertEqual(CalendarDate(year: 2000, month: 1, day: 1).dayNumber, 10_957)
        XCTAssertEqual(CalendarDate(year: 2026, month: 10, day: 20).dayNumber, 20_746)
        XCTAssertEqual(CalendarDate(dayNumber: 20_746), CalendarDate(year: 2026, month: 10, day: 20))
        XCTAssertEqual(CalendarDate(dayNumber: -1), CalendarDate(year: 1969, month: 12, day: 31))
    }

    func testAddingDaysAcrossBoundaries() {
        XCTAssertEqual(CalendarDate(year: 2026, month: 12, day: 31).adding(days: 1), CalendarDate(year: 2027, month: 1, day: 1))
        XCTAssertEqual(CalendarDate(year: 2024, month: 2, day: 28).adding(days: 1), CalendarDate(year: 2024, month: 2, day: 29))
        XCTAssertEqual(CalendarDate(year: 2023, month: 2, day: 28).adding(days: 1), CalendarDate(year: 2023, month: 3, day: 1))
        XCTAssertEqual(CalendarDate(year: 2026, month: 3, day: 1).adding(days: -1), CalendarDate(year: 2026, month: 2, day: 28))
        XCTAssertEqual(CalendarDate(year: 2026, month: 10, day: 20).adding(weeks: -1), CalendarDate(year: 2026, month: 10, day: 13))
        XCTAssertEqual(CalendarDate(year: 2026, month: 10, day: 20).days(until: CalendarDate(year: 2026, month: 10, day: 23)), 3)
        XCTAssertEqual(CalendarDate(year: 2027, month: 1, day: 1).days(until: CalendarDate(year: 2026, month: 12, day: 25)), -7)
    }

    func testComponentNormalization() {
        XCTAssertEqual(CalendarDate(year: 2026, month: 13, day: 1), CalendarDate(year: 2027, month: 1, day: 1))
        XCTAssertEqual(CalendarDate(year: 2026, month: 2, day: 30), CalendarDate(year: 2026, month: 3, day: 2))
        XCTAssertEqual(CalendarDate(year: 2026, month: 0, day: 1), CalendarDate(year: 2025, month: 12, day: 1))
        XCTAssertEqual(CalendarDate(year: 2026, month: 3, day: 0), CalendarDate(year: 2026, month: 2, day: 28))
    }

    func testAddingMonthsClampsDay() {
        XCTAssertEqual(CalendarDate(year: 2026, month: 1, day: 31).adding(months: 1), CalendarDate(year: 2026, month: 2, day: 28))
        XCTAssertEqual(CalendarDate(year: 2024, month: 1, day: 31).adding(months: 1), CalendarDate(year: 2024, month: 2, day: 29))
        XCTAssertEqual(CalendarDate(year: 2026, month: 1, day: 15).adding(months: -1), CalendarDate(year: 2025, month: 12, day: 15))
        XCTAssertEqual(CalendarDate(year: 2026, month: 10, day: 20).startOfMonth, CalendarDate(year: 2026, month: 10, day: 1))
    }

    func testComparable() {
        let a = CalendarDate(year: 2026, month: 1, day: 31)
        let b = CalendarDate(year: 2026, month: 2, day: 1)
        let c = CalendarDate(year: 2027, month: 1, day: 1)
        XCTAssertTrue(a < b)
        XCTAssertTrue(b < c)
        XCTAssertFalse(c < a)
        XCTAssertEqual([c, a, b].sorted(), [a, b, c])
    }

    // MARK: - Weekdays & weeks

    func testWeekdays() {
        XCTAssertEqual(CalendarDate(year: 1970, month: 1, day: 1).weekday, .thursday)
        XCTAssertEqual(CalendarDate(year: 2000, month: 2, day: 29).weekday, .tuesday)
        XCTAssertEqual(CalendarDate(year: 2026, month: 10, day: 19).weekday, .monday)
        XCTAssertEqual(CalendarDate(year: 2026, month: 10, day: 20).weekday, .tuesday)
        XCTAssertEqual(CalendarDate(year: 2026, month: 10, day: 25).weekday, .sunday)
        XCTAssertEqual(CalendarDate(year: 1969, month: 12, day: 28).weekday, .sunday)
    }

    func testStartOfWeekIsMonday() {
        let monday = CalendarDate(year: 2026, month: 10, day: 19)
        XCTAssertEqual(CalendarDate(year: 2026, month: 10, day: 19).startOfWeek, monday)
        XCTAssertEqual(CalendarDate(year: 2026, month: 10, day: 22).startOfWeek, monday)
        XCTAssertEqual(CalendarDate(year: 2026, month: 10, day: 25).startOfWeek, monday) // Sunday
        XCTAssertEqual(monday.endOfWeek, CalendarDate(year: 2026, month: 10, day: 25))
        XCTAssertEqual(CalendarDate(year: 2027, month: 1, day: 1).startOfWeek, CalendarDate(year: 2026, month: 12, day: 28))
    }

    func testISOWeeks() {
        XCTAssertEqual(CalendarDate(year: 2026, month: 10, day: 20).isoWeek, ISOWeek(year: 2026, week: 43))
        XCTAssertEqual(CalendarDate(year: 2026, month: 1, day: 1).isoWeek, ISOWeek(year: 2026, week: 1))
        XCTAssertEqual(CalendarDate(year: 2026, month: 12, day: 31).isoWeek, ISOWeek(year: 2026, week: 53))
        XCTAssertEqual(CalendarDate(year: 2027, month: 1, day: 1).isoWeek, ISOWeek(year: 2026, week: 53))
        XCTAssertEqual(CalendarDate(year: 2027, month: 1, day: 4).isoWeek, ISOWeek(year: 2027, week: 1))
        XCTAssertEqual(CalendarDate(year: 2021, month: 1, day: 3).isoWeek, ISOWeek(year: 2020, week: 53))
        XCTAssertEqual(CalendarDate(year: 2024, month: 12, day: 30).isoWeek, ISOWeek(year: 2025, week: 1))
        XCTAssertEqual(ISOWeek(year: 2026, week: 5).description, "2026-W05")
    }

    func testDayOfYear() {
        XCTAssertEqual(CalendarDate(year: 2026, month: 1, day: 1).dayOfYear, 1)
        XCTAssertEqual(CalendarDate(year: 2026, month: 12, day: 31).dayOfYear, 365)
        XCTAssertEqual(CalendarDate(year: 2024, month: 12, day: 31).dayOfYear, 366)
    }

    // MARK: - Date round trip (never shift the day)

    func testRoundTripThroughDateInManyTimeZones() throws {
        let zones = ["America/New_York", "Pacific/Auckland", "Pacific/Kiritimati", "America/Sao_Paulo", "Asia/Kolkata", "UTC"]
        let dates = [
            CalendarDate(year: 2026, month: 10, day: 20),
            CalendarDate(year: 2026, month: 3, day: 8),   // US DST starts
            CalendarDate(year: 2026, month: 11, day: 1),  // US DST ends
            CalendarDate(year: 2018, month: 2, day: 17),  // São Paulo DST ended at midnight
            CalendarDate(year: 2026, month: 12, day: 31),
        ]
        for identifier in zones {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = try XCTUnwrap(TimeZone(identifier: identifier))
            for date in dates {
                let instant = date.date(in: calendar)
                XCTAssertEqual(CalendarDate(date: instant, calendar: calendar), date, "\(date) in \(identifier)")
            }
        }
    }

    func testLocalCalendarIsGregorianInLocalZone() {
        let calendar = CalendarDate.localCalendar
        XCTAssertEqual(calendar.identifier, .gregorian)
        XCTAssertEqual(calendar.timeZone, TimeZone.current)
        let today = CalendarDate.today()
        XCTAssertEqual(CalendarDate(date: today.date()), today)
    }

    // MARK: - Codable

    func testCodableUsesPlainDateString() throws {
        let date = CalendarDate(year: 2026, month: 10, day: 20)
        let data = try JSONEncoder().encode([date])
        XCTAssertEqual(String(decoding: data, as: UTF8.self), "[\"2026-10-20\"]")
        let decoded = try JSONDecoder().decode([CalendarDate].self, from: Data("[\"2027-01-04\"]".utf8))
        XCTAssertEqual(decoded, [CalendarDate(year: 2027, month: 1, day: 4)])
        XCTAssertThrowsError(try JSONDecoder().decode([CalendarDate].self, from: Data("[\"nope\"]".utf8)))
    }
}
